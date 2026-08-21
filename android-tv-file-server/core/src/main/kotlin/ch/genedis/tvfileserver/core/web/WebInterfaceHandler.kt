package ch.genedis.tvfileserver.core.web

import ch.genedis.tvfileserver.core.auth.AuthResult
import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.config.CoreConfig
import ch.genedis.tvfileserver.core.http.ByteRange
import ch.genedis.tvfileserver.core.http.CountingOutputStream
import ch.genedis.tvfileserver.core.http.HttpBody
import ch.genedis.tvfileserver.core.http.HttpDates
import ch.genedis.tvfileserver.core.http.HttpHandler
import ch.genedis.tvfileserver.core.http.HttpHeaderNames
import ch.genedis.tvfileserver.core.http.HttpRequest
import ch.genedis.tvfileserver.core.http.HttpResponse
import ch.genedis.tvfileserver.core.http.HttpStatus
import ch.genedis.tvfileserver.core.http.MimeTypes
import ch.genedis.tvfileserver.core.http.MultipartParser
import ch.genedis.tvfileserver.core.http.RangeParser
import ch.genedis.tvfileserver.core.http.Router
import ch.genedis.tvfileserver.core.http.UrlCodec
import ch.genedis.tvfileserver.core.transfer.TransferDirection
import ch.genedis.tvfileserver.core.transfer.TransferInfo
import ch.genedis.tvfileserver.core.transfer.TransferProtocol
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.copyStream
import ch.genedis.tvfileserver.core.vfs.VPath
import ch.genedis.tvfileserver.core.vfs.VfsEntry
import ch.genedis.tvfileserver.core.vfs.VfsException
import ch.genedis.tvfileserver.core.vfs.VirtualFileSystem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * The HTTP API and static file server behind the browser UI.
 *
 * Everything the SPA needs lives here: authentication, browsing, streaming uploads and
 * downloads, bulk operations and a live progress feed.
 */
class WebInterfaceHandler(
    private val vfs: VirtualFileSystem,
    private val auth: HttpAuthenticator,
    private val assets: StaticAssetSource,
    private val transfers: TransferRegistry,
    private val configProvider: () -> CoreConfig,
    private val infoProvider: () -> ServerInfo,
) {

    private val router: Router by lazy { buildRouter() }

    fun asHandler(): HttpHandler = router.asHandler()

    private fun buildRouter(): Router = Router()
        .get("/health") { HttpResponse.text("ok") }
        .get("/") { index(it) }
        .get("/index.html") { index(it) }
        .get("/favicon.ico") { asset(it, "favicon.svg", cacheable = true) }
        .get("/favicon.svg") { asset(it, "favicon.svg", cacheable = true) }
        .get("/assets/*") { request ->
            asset(request, request.path.removePrefix("/assets/"), cacheable = true)
        }
        .get("/api/session") { session(it) }
        .post("/api/login") { login(it) }
        .post("/api/logout") { logout(it) }
        .get("/api/info") { guarded(it, write = false) { _ -> info() } }
        .get("/api/roots") { guarded(it, write = false) { _ -> roots() } }
        .get("/api/list") { request -> guarded(request, write = false) { _ -> list(request) } }
        .get("/api/download") { request -> guarded(request, write = false) { _ -> download(request, inline = false) } }
        .get("/api/raw") { request -> guarded(request, write = false) { _ -> download(request, inline = true) } }
        .get("/api/zip") { request -> guarded(request, write = false) { _ -> zip(request) } }
        .get("/api/transfers") { guarded(it, write = false) { _ -> transferSnapshot() } }
        .get("/api/events") { guarded(it, write = false) { _ -> events() } }
        .post("/api/upload") { request -> guarded(request, write = true) { _ -> upload(request) } }
        .post("/api/mkdir") { request -> guarded(request, write = true) { _ -> mkdir(request) } }
        .post("/api/delete") { request -> guarded(request, write = true) { _ -> delete(request) } }
        .post("/api/rename") { request -> guarded(request, write = true) { _ -> rename(request) } }
        .post("/api/move") { request -> guarded(request, write = true) { _ -> transplant(request, move = true) } }
        .post("/api/copy") { request -> guarded(request, write = true) { _ -> transplant(request, move = false) } }
        .notFound { request ->
            if (request.path.startsWith("/api/")) {
                HttpResponse.jsonError(HttpStatus.NOT_FOUND, "Unknown endpoint")
            } else {
                HttpResponse.error(HttpStatus.NOT_FOUND)
            }
        }

    // ------------------------------------------------------------------ auth plumbing

    /**
     * Wraps a route with authentication, read-only enforcement, CSRF defence and error
     * mapping, so no individual route can forget one of them.
     */
    private inline fun guarded(
        request: HttpRequest,
        write: Boolean,
        block: (AuthResult) -> HttpResponse,
    ): HttpResponse {
        val identity = auth.authenticate(request)
        if (!identity.authenticated) return unauthorized(request)
        if (write) {
            if (identity.readOnly || vfs.readOnly) {
                return HttpResponse.jsonError(HttpStatus.FORBIDDEN, "The server is in read-only mode")
            }
            csrfFailure(request)?.let { return it }
        }
        return try {
            block(identity)
        } catch (error: VfsException) {
            CoreLog.d(TAG, "${request.method} ${request.path}: ${error.reason} ${error.message}")
            HttpResponse.jsonError(statusFor(error.reason), error.message ?: error.reason.name)
        } catch (error: IOException) {
            CoreLog.w(TAG, "${request.method} ${request.path} failed", error)
            HttpResponse.jsonError(HttpStatus.INTERNAL_SERVER_ERROR, error.message ?: "I/O error")
        }
    }

    /**
     * A browser must see our own login screen, not the browser's Basic prompt; anything that
     * already sent an `Authorization` header, or that is not asking for HTML, gets a real
     * challenge so curl, rclone and Finder still work.
     */
    private fun unauthorized(request: HttpRequest): HttpResponse {
        val hasAuthorization = request.header(HttpHeaderNames.AUTHORIZATION) != null
        val wantsHtml = request.header(HttpHeaderNames.ACCEPT)?.contains("text/html") == true
        return if (hasAuthorization || !wantsHtml) {
            auth.challenge()
        } else {
            HttpResponse.jsonError(HttpStatus.UNAUTHORIZED, "unauthorized")
        }
    }

    /**
     * Cookie-authenticated writes must carry a custom header.
     *
     * A cross-site form post cannot set one, and the browser will not send our
     * `SameSite=Strict` cookie anyway; requiring the header covers older browsers too.
     * Callers using Basic (scripts, rclone) are exempt because they are not cookie-driven.
     */
    private fun csrfFailure(request: HttpRequest): HttpResponse? {
        if (request.header(HttpHeaderNames.AUTHORIZATION) != null) return null
        if (!auth.policy.enabled) return null
        val marker = request.header(HttpHeaderNames.X_REQUESTED_WITH)
        return if (marker == CSRF_TOKEN) {
            null
        } else {
            HttpResponse.jsonError(HttpStatus.FORBIDDEN, "csrf")
        }
    }

    private fun session(request: HttpRequest): HttpResponse {
        val identity = auth.authenticate(request)
        return HttpResponse.json(
            JsonWriter.obj {
                name("authenticated").value(identity.authenticated)
                name("username").value(identity.username)
                name("authEnabled").value(auth.policy.enabled)
                name("readOnly").value(identity.readOnly || vfs.readOnly)
                name("version").value(configProvider().appVersion)
            },
        )
    }

    private fun login(request: HttpRequest): HttpResponse {
        val username = request.form("username").orEmpty()
        val password = request.form("password").orEmpty()
        if (auth.isThrottled(request.remoteAddress)) {
            return HttpResponse.jsonError(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts")
                .header(HttpHeaderNames.RETRY_AFTER, auth.retryAfterSeconds(request.remoteAddress).toString())
        }
        val token = auth.login(request, username, password)
            ?: return HttpResponse.jsonError(HttpStatus.UNAUTHORIZED, "Wrong user name or password")
        val response = HttpResponse.json(
            JsonWriter.obj {
                name("ok").value(true)
                name("username").value(username)
            },
        )
        return auth.setSessionCookie(response, token)
    }

    private fun logout(request: HttpRequest): HttpResponse {
        auth.sessions.invalidate(request.cookie(HttpAuthenticator.SESSION_COOKIE))
        return auth.clearSessionCookie(HttpResponse.json(JsonWriter.obj { name("ok").value(true) }))
    }

    // ------------------------------------------------------------------ static assets

    private fun index(request: HttpRequest): HttpResponse {
        // The QR code on the TV screen carries a one-shot login token so a phone that scans
        // it lands in the UI already signed in.
        val key = request.query("k")
        if (!key.isNullOrEmpty() && auth.policy.enabled &&
            HttpAuthenticator.constantTimeEquals(key, auth.sessions.autoLoginToken)
        ) {
            val token = auth.sessions.create(auth.policy.credentials.username)
            return auth.setSessionCookie(HttpResponse.redirect("/"), token)
        }
        return asset(request, "index.html", cacheable = false)
    }

    private fun asset(request: HttpRequest, rawName: String, cacheable: Boolean): HttpResponse {
        val name = rawName.trimStart('/')
        if (name.isEmpty() || name.split('/').any { it == ".." || it == "." }) {
            return HttpResponse.error(HttpStatus.BAD_REQUEST, "Invalid asset path")
        }
        if (!assets.exists(name)) return HttpResponse.error(HttpStatus.NOT_FOUND, "No such asset")

        val size = assets.size(name)
        val etag = "\"a-${name.hashCode().toLong() and 0xFFFFFFFFL}-$size\""
        if (request.header(HttpHeaderNames.IF_NONE_MATCH)?.split(',')?.any { it.trim() == etag } == true) {
            return HttpResponse.status(HttpStatus.NOT_MODIFIED).header(HttpHeaderNames.ETAG, etag)
        }

        val body = HttpBody.Streaming(if (size >= 0) size else null) { out ->
            val stream = assets.open(name) ?: return@Streaming
            stream.use { copyStream(it, out, ByteArray(32 * 1024)) }
        }
        return HttpResponse(HttpStatus.OK, body = body)
            .header(HttpHeaderNames.CONTENT_TYPE, MimeTypes.forFileName(name))
            .header(HttpHeaderNames.ETAG, etag)
            .header(
                HttpHeaderNames.CACHE_CONTROL,
                if (cacheable) "public, max-age=86400" else "no-cache",
            )
    }

    // ------------------------------------------------------------------ browsing

    private fun info(): HttpResponse {
        val serverInfo = infoProvider()
        val config = configProvider()
        return HttpResponse.json(
            JsonWriter.obj {
                name("serverName").value(serverInfo.serverName)
                name("deviceName").value(serverInfo.deviceName)
                name("version").value(serverInfo.appVersion)
                name("httpPort").value(serverInfo.httpPort)
                name("ftpPort").value(serverInfo.ftpPort)
                name("ftpEnabled").value(serverInfo.ftpEnabled)
                name("webdavEnabled").value(serverInfo.webdavEnabled)
                name("webdavMount").value(serverInfo.webdavMount)
                name("readOnly").value(serverInfo.readOnly || vfs.readOnly)
                name("authEnabled").value(serverInfo.authEnabled)
                name("hideDotFiles").value(config.hideDotFiles)
                name("addresses").beginArray()
                for (address in serverInfo.addresses) value(address)
                endArray()
            },
        )
    }

    private fun roots(): HttpResponse = HttpResponse.json(
        JsonWriter.arr {
            for (root in vfs.roots()) {
                val path = VPath.ROOT.child(root.id)
                beginObject()
                name("id").value(root.id)
                name("name").value(root.displayName)
                name("path").value(path.value)
                name("type").value(root.type.name)
                name("writable").value(root.writable && !vfs.readOnly)
                name("free").value(vfs.freeSpace(path))
                name("total").value(vfs.totalSpace(path))
                endObject()
            }
        },
    )

    private fun list(request: HttpRequest): HttpResponse {
        val path = requirePath(request, "path", allowRoot = true)
        val entry = vfs.stat(path) ?: throw VfsException.notFound(path)
        if (!entry.isDirectory) {
            throw VfsException(VfsException.Reason.NOT_A_DIRECTORY, "Not a directory: $path")
        }
        val hideDotFiles = configProvider().hideDotFiles
        val entries = vfs.list(path).filter { !hideDotFiles || !it.isHidden }

        return HttpResponse.json(
            JsonWriter.obj {
                name("path").value(path.value)
                name("name").value(if (path.isRoot) "/" else entry.name)
                name("parent").value(path.parent?.value)
                name("writable").value(!path.isRoot && vfs.isWritable(path))
                name("free").value(vfs.freeSpace(path))
                name("total").value(vfs.totalSpace(path))
                name("entries").beginArray()
                for (child in entries) appendEntry(this, child)
                endArray()
            },
        )
    }

    private fun appendEntry(writer: JsonWriter, entry: VfsEntry) {
        writer.beginObject()
        writer.name("name").value(entry.name)
        writer.name("path").value(entry.path.value)
        writer.name("dir").value(entry.isDirectory)
        writer.name("size").value(entry.size)
        writer.name("modified").value(entry.lastModified)
        writer.name("mime").value(entry.mimeType)
        writer.name("hidden").value(entry.isHidden)
        writer.name("writable").value(entry.writable)
        writer.endObject()
    }

    // ------------------------------------------------------------------ downloads

    private fun download(request: HttpRequest, inline: Boolean): HttpResponse {
        val path = requirePath(request, "path", allowRoot = false)
        val entry = vfs.stat(path) ?: throw VfsException.notFound(path)
        if (entry.isDirectory) {
            throw VfsException(VfsException.Reason.IS_A_DIRECTORY, "Use the ZIP endpoint for folders")
        }

        val etag = etagOf(entry)
        val lastModified = entry.lastModified
        conditionalResponse(request, etag, lastModified)?.let { return it }

        val length = entry.size
        val ranges = if (rangeApplies(request, etag, lastModified)) {
            RangeParser.parse(request.header(HttpHeaderNames.RANGE), length)
        } else {
            null
        }
        if (ranges != null && ranges.isEmpty()) {
            return HttpResponse.status(HttpStatus.RANGE_NOT_SATISFIABLE)
                .header(HttpHeaderNames.CONTENT_RANGE, RangeParser.unsatisfiedContentRange(length))
        }

        val range = ranges?.firstOrNull() ?: ByteRange(0, maxOf(length - 1, 0))
        val partial = ranges?.firstOrNull() != null
        val bodyLength = if (length == 0L) 0L else range.length

        val response = HttpResponse(
            if (partial) HttpStatus.PARTIAL_CONTENT else HttpStatus.OK,
            body = HttpBody.Streaming(bodyLength) { out ->
                streamFile(request, path, entry, range.start, bodyLength, out)
            },
        )
        if (partial) {
            response.header(HttpHeaderNames.CONTENT_RANGE, RangeParser.contentRange(range, length))
        }
        response.header(HttpHeaderNames.CONTENT_TYPE, entry.mimeType)
        response.header(HttpHeaderNames.ETAG, etag)
        response.header(HttpHeaderNames.LAST_MODIFIED, HttpDates.format(lastModified))
        response.header(HttpHeaderNames.ACCEPT_RANGES, "bytes")
        response.header(HttpHeaderNames.CONTENT_DISPOSITION, disposition(entry.name, inline))
        return response
    }

    private fun streamFile(
        request: HttpRequest,
        path: VPath,
        entry: VfsEntry,
        offset: Long,
        length: Long,
        out: OutputStream,
    ) {
        if (length <= 0) return
        val handle = transfers.begin(
            name = entry.name,
            path = path.value,
            direction = TransferDirection.DOWNLOAD,
            protocol = TransferProtocol.HTTP,
            client = request.remoteAddress,
            total = length,
        )
        try {
            val buffer = ByteArray(configProvider().bufferSize)
            vfs.openRead(path, offset).use { input ->
                copyStream(input, CountingOutputStream(out) { handle.advance(it) }, buffer, limit = length)
            }
            handle.complete()
        } catch (error: IOException) {
            handle.fail(error)
            throw error
        }
    }

    private fun zip(request: HttpRequest): HttpResponse {
        val basePath = requirePath(request, "path", allowRoot = true)
        val explicit = request.query("paths")
        val targets = if (explicit.isNullOrEmpty()) {
            listOf(basePath)
        } else {
            parseJsonStringArray(explicit).map { VPath.of(it) }
        }
        if (targets.isEmpty()) {
            return HttpResponse.jsonError(HttpStatus.BAD_REQUEST, "Nothing selected")
        }
        for (target in targets) {
            if (vfs.stat(target) == null) throw VfsException.notFound(target)
        }

        val archiveName = request.query("name")?.takeIf { it.isNotBlank() }
            ?: (if (targets.size == 1) targets[0].name.ifEmpty { "storage" } else "selection")
        val config = configProvider()
        val parentOfSelection = if (explicit.isNullOrEmpty()) basePath.parent ?: VPath.ROOT else basePath

        val handle = transfers.begin(
            name = "$archiveName.zip",
            path = basePath.value,
            direction = TransferDirection.DOWNLOAD,
            protocol = TransferProtocol.HTTP,
            client = request.remoteAddress,
            total = -1,
        )
        // Length is unknown until the archive is built, so the response is chunked.
        val body = HttpBody.Streaming(null) { out ->
            try {
                ZipStreamer(vfs, config.bufferSize, config.hideDotFiles) { handle.advance(it) }
                    .write(out, targets, parentOfSelection)
                handle.complete()
            } catch (error: IOException) {
                handle.fail(error)
                throw error
            }
        }
        return HttpResponse(HttpStatus.OK, body = body)
            .header(HttpHeaderNames.CONTENT_TYPE, "application/zip")
            .header(HttpHeaderNames.CONTENT_DISPOSITION, disposition("$archiveName.zip", inline = false))
            .header(HttpHeaderNames.CACHE_CONTROL, "no-store")
    }

    // ------------------------------------------------------------------ uploads

    private fun upload(request: HttpRequest): HttpResponse {
        val boundary = MultipartParser.boundaryOf(request.contentType)
            ?: return HttpResponse.jsonError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected multipart/form-data")

        val overwrite = request.query("overwrite") == "true"
        var destination = VPath.of(request.query("path") ?: "/")
        val uploaded = ArrayList<String>(4)
        val failed = ArrayList<Pair<String, String>>(0)
        val config = configProvider()
        val buffer = ByteArray(config.bufferSize)

        MultipartParser(request.body, boundary, config.bufferSize).forEachPart { part ->
            if (!part.isFile) {
                // A `path` field lets the client override the query parameter, which keeps the
                // upload URL stable while the user browses.
                if (part.name == "path") destination = VPath.of(part.readText())
                return@forEachPart
            }
            val rawName = part.fileName.orEmpty()
            val segments = MultipartParser.sanitizeRelativePath(rawName)
            if (segments.isEmpty()) {
                failed.add(rawName to "Invalid file name")
                return@forEachPart
            }
            try {
                val target = storeUploadedPart(part.stream, destination, segments, overwrite, buffer, request)
                uploaded.add(target.value)
            } catch (error: VfsException) {
                CoreLog.w(TAG, "Upload of $rawName failed: ${error.message}")
                failed.add(rawName to (error.message ?: error.reason.name))
            } catch (error: IOException) {
                CoreLog.w(TAG, "Upload of $rawName failed", error)
                failed.add(rawName to (error.message ?: "I/O error"))
            }
        }

        val status = if (uploaded.isEmpty() && failed.isNotEmpty()) HttpStatus.BAD_REQUEST else HttpStatus.OK
        return HttpResponse.json(
            JsonWriter.obj {
                name("uploaded").beginArray()
                for (path in uploaded) value(path)
                endArray()
                name("failed").beginArray()
                for ((fileName, reason) in failed) {
                    beginObject()
                    name("name").value(fileName)
                    name("error").value(reason)
                    endObject()
                }
                endArray()
            },
            status,
        )
    }

    /**
     * Writes one uploaded part.
     *
     * The bytes land in a `.part` sibling and are renamed on success, so an interrupted
     * upload never leaves a truncated file that looks complete to a media player.
     */
    private fun storeUploadedPart(
        source: InputStream,
        destination: VPath,
        segments: List<String>,
        overwrite: Boolean,
        buffer: ByteArray,
        request: HttpRequest,
    ): VPath {
        var directory = destination
        for (index in 0 until segments.size - 1) {
            directory = directory.child(segments[index])
            if (vfs.stat(directory) == null) vfs.mkdir(directory)
        }
        val fileName = segments.last()
        val target = if (overwrite) directory.child(fileName) else uniqueName(directory, fileName)
        val partial = target.parent!!.child(target.name + ".part")

        val handle = transfers.begin(
            name = target.name,
            path = target.value,
            direction = TransferDirection.UPLOAD,
            protocol = TransferProtocol.HTTP,
            client = request.remoteAddress,
            total = request.header(X_FILE_SIZE)?.toLongOrNull() ?: -1L,
        )
        try {
            vfs.openWrite(partial, append = false).use { output ->
                copyStream(source, output, buffer, onProgress = { handle.advance(it) })
            }
            vfs.move(partial, target, overwrite = true)
            handle.complete()
        } catch (error: IOException) {
            handle.fail(error)
            runCatching { vfs.delete(partial, recursive = false) }
            throw error
        }
        return target
    }

    /** Appends ` (2)`, ` (3)` … until the name is free, mirroring what a desktop does. */
    private fun uniqueName(directory: VPath, fileName: String): VPath {
        var candidate = directory.child(fileName)
        if (vfs.stat(candidate) == null) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val suffix = if (dot > 0) fileName.substring(dot) else ""
        var counter = 2
        while (counter < 1000) {
            candidate = directory.child("$stem ($counter)$suffix")
            if (vfs.stat(candidate) == null) return candidate
            counter++
        }
        throw VfsException(VfsException.Reason.CONFLICT, "Cannot find a free name for $fileName")
    }

    // ------------------------------------------------------------------ mutations

    private fun mkdir(request: HttpRequest): HttpResponse {
        val parent = requirePath(request, "path", allowRoot = false)
        val name = request.param("name")?.trim().orEmpty()
        if (!VPath.isValidSegment(name)) {
            return HttpResponse.jsonError(HttpStatus.BAD_REQUEST, "Invalid folder name")
        }
        val created = vfs.mkdir(parent.child(name))
        return HttpResponse.json(JsonWriter.obj { name("path").value(created.path.value) })
    }

    private fun delete(request: HttpRequest): HttpResponse {
        val paths = requestedPaths(request)
        if (paths.isEmpty()) return HttpResponse.jsonError(HttpStatus.BAD_REQUEST, "Nothing selected")
        val deleted = ArrayList<String>(paths.size)
        val failed = ArrayList<Pair<String, String>>(0)
        for (path in paths) {
            if (path.isRoot || path.segments.size < 2) {
                failed.add(path.value to "Storage roots cannot be deleted")
                continue
            }
            try {
                vfs.delete(path, recursive = true)
                deleted.add(path.value)
            } catch (error: VfsException) {
                failed.add(path.value to (error.message ?: error.reason.name))
            }
        }
        return bulkResult(deleted, failed)
    }

    private fun rename(request: HttpRequest): HttpResponse {
        val path = requirePath(request, "path", allowRoot = false)
        val name = request.param("name")?.trim().orEmpty()
        if (!VPath.isValidSegment(name)) {
            return HttpResponse.jsonError(HttpStatus.BAD_REQUEST, "Invalid name")
        }
        val parent = path.parent ?: throw VfsException.accessDenied(path)
        val target = parent.child(name)
        vfs.move(path, target, overwrite = false)
        return HttpResponse.json(JsonWriter.obj { name("path").value(target.value) })
    }

    private fun transplant(request: HttpRequest, move: Boolean): HttpResponse {
        val paths = requestedPaths(request)
        if (paths.isEmpty()) return HttpResponse.jsonError(HttpStatus.BAD_REQUEST, "Nothing selected")
        val destinationRaw = request.param("destination")
            ?: return HttpResponse.jsonError(HttpStatus.BAD_REQUEST, "Missing destination")
        val destination = VPath.of(destinationRaw)
        val destinationEntry = vfs.stat(destination) ?: throw VfsException.notFound(destination)
        if (!destinationEntry.isDirectory) {
            throw VfsException(VfsException.Reason.NOT_A_DIRECTORY, "Destination is not a folder")
        }
        val overwrite = request.param("overwrite") == "true"

        val done = ArrayList<String>(paths.size)
        val failed = ArrayList<Pair<String, String>>(0)
        for (source in paths) {
            val target = destination.child(source.name)
            try {
                if (move) {
                    vfs.move(source, target, overwrite)
                } else {
                    vfs.copy(source, target, overwrite, recursive = true)
                }
                done.add(target.value)
            } catch (error: VfsException) {
                failed.add(source.value to (error.message ?: error.reason.name))
            }
        }
        return bulkResult(done, failed)
    }

    private fun bulkResult(done: List<String>, failed: List<Pair<String, String>>): HttpResponse {
        val status = if (done.isEmpty() && failed.isNotEmpty()) HttpStatus.CONFLICT else HttpStatus.OK
        return HttpResponse.json(
            JsonWriter.obj {
                name("ok").value(failed.isEmpty())
                name("done").beginArray()
                for (path in done) value(path)
                endArray()
                name("failed").beginArray()
                for ((path, reason) in failed) {
                    beginObject()
                    name("path").value(path)
                    name("error").value(reason)
                    endObject()
                }
                endArray()
            },
            status,
        )
    }

    // ------------------------------------------------------------------ progress feed

    private fun transferSnapshot(): HttpResponse = HttpResponse.json(transfersJson())

    private fun transfersJson(): String = JsonWriter.obj {
        val totals = transfers.totals.value
        name("active").beginArray()
        for (transfer in transfers.snapshot()) appendTransfer(this, transfer)
        endArray()
        name("recent").beginArray()
        for (transfer in transfers.recent.value) appendTransfer(this, transfer)
        endArray()
        name("totals").beginObject()
        name("activeCount").value(totals.activeCount)
        name("bytesUploaded").value(totals.bytesUploaded)
        name("bytesDownloaded").value(totals.bytesDownloaded)
        name("uploadBps").value(totals.uploadBps)
        name("downloadBps").value(totals.downloadBps)
        endObject()
    }

    private fun appendTransfer(writer: JsonWriter, transfer: TransferInfo) {
        writer.beginObject()
        writer.name("id").value(transfer.id)
        writer.name("name").value(transfer.name)
        writer.name("path").value(transfer.path)
        writer.name("direction").value(transfer.direction.name)
        writer.name("protocol").value(transfer.protocol.name)
        writer.name("client").value(transfer.client)
        writer.name("transferred").value(transfer.transferred)
        writer.name("total").value(transfer.total)
        writer.name("bps").value(transfer.bytesPerSecond)
        writer.name("startedAt").value(transfer.startedAtMillis)
        writer.name("error").value(transfer.error)
        writer.endObject()
    }

    /**
     * Server-sent events carrying the transfer table once a second.
     *
     * Cheaper than the SPA polling, and it lets one browser watch an upload started from
     * Finder or Kodi.
     */
    private fun events(): HttpResponse {
        val body = HttpBody.Streaming(null) { out ->
            val deadline = System.currentTimeMillis() + SSE_MAX_DURATION_MS
            out.write("retry: 3000\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
            while (System.currentTimeMillis() < deadline) {
                val frame = "data: ${transfersJson()}\n\n"
                out.write(frame.toByteArray(Charsets.UTF_8))
                out.flush()
                try {
                    Thread.sleep(SSE_INTERVAL_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Streaming
                }
            }
        }
        return HttpResponse(HttpStatus.OK, body = body, closeConnection = true)
            .header(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8")
            .header(HttpHeaderNames.CACHE_CONTROL, "no-store")
            .header("X-Accel-Buffering", "no")
    }

    // ------------------------------------------------------------------ helpers

    private fun requirePath(request: HttpRequest, parameter: String, allowRoot: Boolean): VPath {
        val raw = request.param(parameter) ?: if (allowRoot) "/" else ""
        if (raw.isEmpty()) throw VfsException.invalidPath(raw)
        val path = VPath.ofOrNull(raw) ?: throw VfsException.invalidPath(raw)
        if (!allowRoot && path.isRoot) throw VfsException.invalidPath(raw)
        return path
    }

    private fun requestedPaths(request: HttpRequest): List<VPath> {
        val raw = request.param("paths") ?: return emptyList()
        return parseJsonStringArray(raw).mapNotNull { VPath.ofOrNull(it) }.filter { !it.isRoot }
    }

    /**
     * Reads a JSON array of strings.
     *
     * The only structured input the API accepts is this one shape, so a 40-line reader beats
     * pulling a JSON library into the APK.
     */
    private fun parseJsonStringArray(raw: String): List<String> {
        val text = raw.trim()
        if (!text.startsWith("[")) return if (text.isEmpty()) emptyList() else listOf(text)
        val result = ArrayList<String>(4)
        var index = 1
        while (index < text.length) {
            when (text[index]) {
                '"' -> {
                    val builder = StringBuilder()
                    index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\' && index + 1 < text.length) {
                            when (val escaped = text[index + 1]) {
                                'n' -> builder.append('\n')
                                'r' -> builder.append('\r')
                                't' -> builder.append('\t')
                                'u' -> {
                                    if (index + 5 < text.length) {
                                        val code = text.substring(index + 2, index + 6).toIntOrNull(16)
                                        if (code != null) builder.append(code.toChar())
                                        index += 4
                                    }
                                }
                                else -> builder.append(escaped)
                            }
                            index += 2
                            continue
                        }
                        builder.append(text[index])
                        index++
                    }
                    result.add(builder.toString())
                    index++
                }
                ']' -> return result
                else -> index++
            }
        }
        return result
    }

    private fun conditionalResponse(request: HttpRequest, etag: String, lastModified: Long): HttpResponse? {
        val ifNoneMatch = request.header(HttpHeaderNames.IF_NONE_MATCH)
        if (ifNoneMatch != null && (ifNoneMatch == "*" || ifNoneMatch.split(',').any { it.trim() == etag })) {
            return HttpResponse.status(HttpStatus.NOT_MODIFIED).header(HttpHeaderNames.ETAG, etag)
        }
        val since = HttpDates.parse(request.header(HttpHeaderNames.IF_MODIFIED_SINCE))
        if (since != null && lastModified / 1000 <= since / 1000) {
            return HttpResponse.status(HttpStatus.NOT_MODIFIED).header(HttpHeaderNames.ETAG, etag)
        }
        return null
    }

    private fun rangeApplies(request: HttpRequest, etag: String, lastModified: Long): Boolean {
        val ifRange = request.header(HttpHeaderNames.IF_RANGE) ?: return true
        if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) return ifRange.trim() == etag
        val date = HttpDates.parse(ifRange) ?: return false
        return lastModified / 1000 <= date / 1000
    }

    /** RFC 6266 disposition with both an ASCII fallback and a UTF-8 name. */
    private fun disposition(fileName: String, inline: Boolean): String {
        val ascii = fileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }
            .joinToString("")
        val encoded = UrlCodec.encodeComponent(fileName)
        val kind = if (inline) "inline" else "attachment"
        return "$kind; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private companion object {
        const val TAG = "WebInterfaceHandler"
        const val CSRF_TOKEN = "TvFileServer"
        const val X_FILE_SIZE = "X-File-Size"
        const val SSE_INTERVAL_MS = 1000L
        const val SSE_MAX_DURATION_MS = 30 * 60_000L

        fun etagOf(entry: VfsEntry): String =
            "\"" + java.lang.Long.toHexString(entry.size) + "-" +
                java.lang.Long.toHexString(entry.lastModified) + "\""

        fun statusFor(reason: VfsException.Reason): HttpStatus = when (reason) {
            VfsException.Reason.NOT_FOUND -> HttpStatus.NOT_FOUND
            VfsException.Reason.NOT_A_DIRECTORY -> HttpStatus.BAD_REQUEST
            VfsException.Reason.IS_A_DIRECTORY -> HttpStatus.BAD_REQUEST
            VfsException.Reason.ALREADY_EXISTS -> HttpStatus.CONFLICT
            VfsException.Reason.READ_ONLY -> HttpStatus.FORBIDDEN
            VfsException.Reason.ACCESS_DENIED -> HttpStatus.FORBIDDEN
            VfsException.Reason.INVALID_PATH -> HttpStatus.BAD_REQUEST
            VfsException.Reason.NO_SPACE -> HttpStatus.INSUFFICIENT_STORAGE
            VfsException.Reason.CONFLICT -> HttpStatus.CONFLICT
            VfsException.Reason.IO_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
        }
    }
}
