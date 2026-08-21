package ch.genedis.tvfileserver.core.webdav

import ch.genedis.tvfileserver.core.auth.AuthResult
import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.config.CoreConfig
import ch.genedis.tvfileserver.core.http.ByteRange
import ch.genedis.tvfileserver.core.http.CountingOutputStream
import ch.genedis.tvfileserver.core.http.HttpBody
import ch.genedis.tvfileserver.core.http.HttpDates
import ch.genedis.tvfileserver.core.http.HttpHeaderNames
import ch.genedis.tvfileserver.core.http.HttpHandler
import ch.genedis.tvfileserver.core.http.HttpRequest
import ch.genedis.tvfileserver.core.http.HttpResponse
import ch.genedis.tvfileserver.core.http.HttpStatus
import ch.genedis.tvfileserver.core.http.MimeTypes
import ch.genedis.tvfileserver.core.http.RangeParser
import ch.genedis.tvfileserver.core.http.UrlCodec
import ch.genedis.tvfileserver.core.transfer.TransferDirection
import ch.genedis.tvfileserver.core.transfer.TransferProtocol
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.copyStream
import ch.genedis.tvfileserver.core.vfs.VPath
import ch.genedis.tvfileserver.core.vfs.VfsEntry
import ch.genedis.tvfileserver.core.vfs.VfsException
import ch.genedis.tvfileserver.core.vfs.VirtualFileSystem
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * A WebDAV class 1 + 2 endpoint.
 *
 * This is the protocol that makes the server a first-class citizen on macOS: Finder mounts
 * `http://<ip>:8080/dav` read-write from Go > Connect to Server, which plain FTP can no
 * longer do on current macOS releases. Windows Explorer and most Linux file managers speak
 * it too.
 */
class WebDavHandler(
    private val vfs: VirtualFileSystem,
    private val auth: HttpAuthenticator,
    private val transfers: TransferRegistry,
    private val locks: DavLockManager = DavLockManager(),
    private val configProvider: () -> CoreConfig,
) : HttpHandler {

    override fun handle(request: HttpRequest): HttpResponse = try {
        dispatch(request)
    } catch (error: VfsException) {
        CoreLog.d(TAG, "${request.method} ${request.path}: ${error.reason} ${error.message}")
        HttpResponse.error(statusFor(error.reason), error.message ?: error.reason.name)
    } catch (error: IOException) {
        CoreLog.w(TAG, "${request.method} ${request.path} failed", error)
        HttpResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, error.message ?: "I/O error")
    }

    private fun dispatch(request: HttpRequest): HttpResponse {
        if (request.method == "OPTIONS") return options()

        val identity = auth.authenticate(request)
        if (!identity.authenticated) return auth.challenge()

        return when (request.method) {
            "GET", "HEAD" -> get(request, headOnly = request.method == "HEAD")
            "PROPFIND" -> propfind(request)
            "PROPPATCH" -> proppatch(request, identity)
            "PUT" -> put(request, identity)
            "DELETE" -> delete(request, identity)
            "MKCOL" -> mkcol(request, identity)
            "COPY" -> copyOrMove(request, identity, move = false)
            "MOVE" -> copyOrMove(request, identity, move = true)
            "LOCK" -> lock(request, identity)
            "UNLOCK" -> unlock(request, identity)
            else -> HttpResponse.error(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaderNames.ALLOW, ALLOWED_METHODS)
        }
    }

    // ------------------------------------------------------------------ methods

    private fun options(): HttpResponse = HttpResponse.status(HttpStatus.OK)
        .header(HttpHeaderNames.DAV, "1, 2")
        .header(HttpHeaderNames.MS_AUTHOR_VIA, "DAV")
        .header(HttpHeaderNames.ALLOW, ALLOWED_METHODS)
        .header(HttpHeaderNames.ACCEPT_RANGES, "bytes")
        .header(HttpHeaderNames.CONTENT_LENGTH, "0")

    private fun get(request: HttpRequest, headOnly: Boolean): HttpResponse {
        val path = pathOf(request)
        val entry = vfs.stat(path) ?: throw VfsException.notFound(path)
        if (entry.isDirectory) {
            // Finder never GETs a collection, but a browser pointed at the mount will.
            return HttpResponse.html(directoryListingHtml(request, path))
        }

        val etag = etagOf(entry)
        val lastModified = HttpDates.format(entry.lastModified)
        notModifiedResponse(request, etag, entry.lastModified)?.let { return it }

        val length = entry.size
        val ranges = if (rangeApplies(request, etag, entry.lastModified)) {
            RangeParser.parse(request.header(HttpHeaderNames.RANGE), length)
        } else {
            null
        }
        if (ranges != null && ranges.isEmpty()) {
            return HttpResponse.status(HttpStatus.RANGE_NOT_SATISFIABLE)
                .header(HttpHeaderNames.CONTENT_RANGE, RangeParser.unsatisfiedContentRange(length))
        }
        val range = ranges?.firstOrNull()

        val response = if (range == null) {
            fileResponse(request, path, entry, ByteRange(0, maxOf(length - 1, 0)), length, partial = false, empty = length == 0L)
        } else {
            fileResponse(request, path, entry, range, length, partial = true, empty = false)
        }
        response.header(HttpHeaderNames.ETAG, etag)
        response.header(HttpHeaderNames.LAST_MODIFIED, lastModified)
        response.header(HttpHeaderNames.ACCEPT_RANGES, "bytes")
        response.header(HttpHeaderNames.CONTENT_TYPE, entry.mimeType)
        return if (headOnly) HttpResponse(response.status, response.headers, HttpBody.Empty) else response
    }

    private fun fileResponse(
        request: HttpRequest,
        path: VPath,
        entry: VfsEntry,
        range: ByteRange,
        totalLength: Long,
        partial: Boolean,
        empty: Boolean,
    ): HttpResponse {
        val length = if (empty) 0L else range.length
        val status = if (partial) HttpStatus.PARTIAL_CONTENT else HttpStatus.OK
        val response = HttpResponse(
            status,
            body = HttpBody.Streaming(length) { out ->
                streamFile(request, path, entry, range.start, length, out)
            },
        )
        if (partial) {
            response.header(HttpHeaderNames.CONTENT_RANGE, RangeParser.contentRange(range, totalLength))
        }
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
            protocol = TransferProtocol.WEBDAV,
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

    private fun propfind(request: HttpRequest): HttpResponse {
        val path = pathOf(request)
        val entry = vfs.stat(path) ?: throw VfsException.notFound(path)

        val depthHeader = request.header(HttpHeaderNames.DEPTH)?.trim()?.lowercase(Locale.ROOT)
        if (depthHeader == "infinity") {
            // Walking an entire TV drive would take minutes and pin memory; RFC 4918 lets a
            // server refuse, and every mainstream client falls back to depth 1.
            return HttpResponse.xml(
                errorXml("propfind-finite-depth"),
                HttpStatus.FORBIDDEN,
            )
        }
        val depth = if (depthHeader == "0") 0 else 1

        val requested = DavXml.parsePropfind(request.bodyBytes(MAX_XML_BODY))
        val hideDotFiles = configProvider().hideDotFiles

        val builder = StringBuilder(1024)
        builder.append(XML_DECLARATION)
        builder.append("<D:multistatus xmlns:D=\"DAV:\">")
        appendResponse(builder, request, path, entry, requested)
        if (depth == 1 && entry.isDirectory) {
            for (child in vfs.list(path)) {
                if (hideDotFiles && child.isHidden) continue
                appendResponse(builder, request, child.path, child, requested)
            }
        }
        builder.append("</D:multistatus>")
        return HttpResponse.xml(builder.toString())
    }

    private fun proppatch(request: HttpRequest, identity: AuthResult): HttpResponse {
        val path = pathOf(request)
        denyIfReadOnly(identity, path)?.let { return it }
        denyIfLocked(request, path)?.let { return it }
        vfs.stat(path) ?: throw VfsException.notFound(path)

        val body = request.bodyBytes(MAX_XML_BODY)
        val properties = DavXml.parseProppatch(body)

        // Finder and Explorer set timestamps right after an upload. Apply what we can and
        // acknowledge the rest: answering 403 here makes Finder show a copy error even
        // though the file arrived intact.
        applyLastModified(body, path)

        val builder = StringBuilder(512)
        builder.append(XML_DECLARATION)
        builder.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:Z=\"urn:schemas-microsoft-com:\">")
        builder.append("<D:response><D:href>").append(hrefOf(request, path, vfs.stat(path)?.isDirectory == true))
            .append("</D:href>")
        builder.append("<D:propstat><D:prop>")
        for ((namespace, name) in properties) {
            val prefix = if (namespace == DavXml.DAV_NS || namespace.isEmpty()) "D" else "Z"
            builder.append('<').append(prefix).append(':').append(DavXml.escape(name)).append("/>")
        }
        builder.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        builder.append("</D:response></D:multistatus>")
        return HttpResponse.xml(builder.toString())
    }

    private fun applyLastModified(body: ByteArray, path: VPath) {
        val raw = DavXml.proppatchValue(body, "getlastmodified")
            ?: DavXml.proppatchValue(body, "Win32LastModifiedTime")
            ?: return
        val millis = HttpDates.parse(raw) ?: return
        vfs.setLastModified(path, millis)
    }

    private fun put(request: HttpRequest, identity: AuthResult): HttpResponse {
        val path = pathOf(request)
        if (path.isRoot) return HttpResponse.error(HttpStatus.METHOD_NOT_ALLOWED, "Cannot PUT the root")
        denyIfReadOnly(identity, path)?.let { return it }
        denyIfLocked(request, path)?.let { return it }

        val existing = vfs.stat(path)
        if (existing != null && existing.isDirectory) {
            return HttpResponse.error(HttpStatus.METHOD_NOT_ALLOWED, "Target is a collection")
        }
        if (request.header(HttpHeaderNames.IF_NONE_MATCH) == "*" && existing != null) {
            return HttpResponse.status(HttpStatus.PRECONDITION_FAILED)
        }
        val ifMatch = request.header(HttpHeaderNames.IF_MATCH)
        if (ifMatch != null && ifMatch != "*") {
            if (existing == null || etagOf(existing) != ifMatch) {
                return HttpResponse.status(HttpStatus.PRECONDITION_FAILED)
            }
        }
        val parent = path.parent
        if (parent != null && !parent.isRoot && vfs.stat(parent) == null) {
            return HttpResponse.error(HttpStatus.CONFLICT, "Parent collection does not exist")
        }

        val declared = request.contentLength
        val handle = transfers.begin(
            name = path.name,
            path = path.value,
            direction = TransferDirection.UPLOAD,
            protocol = TransferProtocol.WEBDAV,
            client = request.remoteAddress,
            total = declared,
        )
        try {
            val buffer = ByteArray(configProvider().bufferSize)
            vfs.openWrite(path, append = false).use { output ->
                copyStream(request.body, output, buffer, onProgress = { handle.advance(it) })
            }
            handle.complete()
        } catch (error: IOException) {
            handle.fail(error)
            // A partially written file is worse than none: the client will retry.
            runCatching { vfs.delete(path, recursive = false) }
            throw error
        }

        val entry = vfs.stat(path)
        val response = HttpResponse.status(if (existing == null) HttpStatus.CREATED else HttpStatus.NO_CONTENT)
        if (entry != null) response.header(HttpHeaderNames.ETAG, etagOf(entry))
        return response
    }

    private fun delete(request: HttpRequest, identity: AuthResult): HttpResponse {
        val path = pathOf(request)
        if (path.isRoot) return HttpResponse.error(HttpStatus.FORBIDDEN, "Cannot delete the root")
        denyIfReadOnly(identity, path)?.let { return it }
        denyIfLocked(request, path)?.let { return it }
        vfs.stat(path) ?: throw VfsException.notFound(path)
        vfs.delete(path, recursive = true)
        return HttpResponse.noContent()
    }

    private fun mkcol(request: HttpRequest, identity: AuthResult): HttpResponse {
        val path = pathOf(request)
        denyIfReadOnly(identity, path)?.let { return it }
        denyIfLocked(request, path)?.let { return it }
        if (request.contentLength > 0) {
            return HttpResponse.error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MKCOL does not accept a body")
        }
        if (vfs.stat(path) != null) {
            return HttpResponse.error(HttpStatus.METHOD_NOT_ALLOWED, "Already exists")
        }
        val parent = path.parent
        if (parent != null && vfs.stat(parent) == null) {
            return HttpResponse.error(HttpStatus.CONFLICT, "Parent collection does not exist")
        }
        vfs.mkdir(path)
        return HttpResponse.status(HttpStatus.CREATED)
    }

    private fun copyOrMove(request: HttpRequest, identity: AuthResult, move: Boolean): HttpResponse {
        val source = pathOf(request)
        val destinationHeader = request.header(HttpHeaderNames.DESTINATION)
            ?: return HttpResponse.error(HttpStatus.BAD_REQUEST, "Missing Destination header")
        val target = destinationPath(request, destinationHeader)
            ?: return HttpResponse.error(HttpStatus.BAD_REQUEST, "Destination is outside this share")

        denyIfReadOnly(identity, target)?.let { return it }
        denyIfLocked(request, target)?.let { return it }
        if (move) denyIfLocked(request, source)?.let { return it }

        if (vfs.stat(source) == null) throw VfsException.notFound(source)
        val overwrite = !request.header(HttpHeaderNames.OVERWRITE).equals("F", ignoreCase = true)
        val existed = vfs.stat(target) != null
        if (existed && !overwrite) return HttpResponse.status(HttpStatus.PRECONDITION_FAILED)

        val targetParent = target.parent
        if (targetParent != null && !targetParent.isRoot && vfs.stat(targetParent) == null) {
            return HttpResponse.error(HttpStatus.CONFLICT, "Destination parent does not exist")
        }

        if (move) {
            vfs.move(source, target, overwrite)
        } else {
            vfs.copy(source, target, overwrite, recursive = true)
        }
        return HttpResponse.status(if (existed) HttpStatus.NO_CONTENT else HttpStatus.CREATED)
    }

    private fun lock(request: HttpRequest, identity: AuthResult): HttpResponse {
        val path = pathOf(request)
        denyIfReadOnly(identity, path)?.let { return it }

        val timeout = DavLockManager.parseTimeout(request.header(HttpHeaderNames.TIMEOUT))
        val body = request.bodyBytes(MAX_XML_BODY)

        // An empty body means "refresh the lock named in the If header".
        if (body.isEmpty()) {
            val token = DavLockManager.parseIfTokens(request.header(HttpHeaderNames.IF)).firstOrNull()
                ?: return HttpResponse.error(HttpStatus.BAD_REQUEST, "Lock refresh without a token")
            val refreshed = locks.refresh(DavLockManager.normalizeToken(token), timeout)
                ?: return HttpResponse.error(HttpStatus.PRECONDITION_FAILED, "Unknown lock token")
            return lockResponse(request, refreshed, HttpStatus.OK)
        }

        val info = DavXml.parseLock(body)
            ?: return HttpResponse.error(HttpStatus.BAD_REQUEST, "Malformed lockinfo")
        val presented = DavLockManager.parseIfTokens(request.header(HttpHeaderNames.IF))
        if (locks.isLockedForOthers(path, presented)) {
            return HttpResponse.error(HttpStatus.LOCKED, "Resource is locked")
        }

        val depth = if (request.header(HttpHeaderNames.DEPTH)?.trim() == "0") 0 else Int.MAX_VALUE
        val created = vfs.stat(path) == null
        if (created) {
            // RFC 4918 allows locking an unmapped resource; Finder relies on it to reserve a
            // name before uploading.
            vfs.openWrite(path, append = false).close()
        }
        val lock = locks.lock(path, depth, info.owner, info.exclusive, timeout)
        return lockResponse(request, lock, if (created) HttpStatus.CREATED else HttpStatus.OK)
    }

    private fun lockResponse(request: HttpRequest, lock: DavLock, status: HttpStatus): HttpResponse {
        val builder = StringBuilder(512)
        builder.append(XML_DECLARATION)
        builder.append("<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery>")
        appendActiveLock(builder, request, lock)
        builder.append("</D:lockdiscovery></D:prop>")
        return HttpResponse.xml(builder.toString(), status)
            .header(HttpHeaderNames.LOCK_TOKEN, "<${lock.token}>")
    }

    private fun unlock(request: HttpRequest, identity: AuthResult): HttpResponse {
        val path = pathOf(request)
        denyIfReadOnly(identity, path)?.let { return it }
        val header = request.header(HttpHeaderNames.LOCK_TOKEN)
            ?: return HttpResponse.error(HttpStatus.BAD_REQUEST, "Missing Lock-Token header")
        val token = DavLockManager.normalizeToken(header)
        return if (locks.unlock(token)) {
            HttpResponse.noContent()
        } else {
            HttpResponse.error(HttpStatus.CONFLICT, "Unknown lock token")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun pathOf(request: HttpRequest): VPath = VPath.of(request.path)

    /** Builds the href for [path] as clients must see it: mount-prefixed and encoded. */
    private fun hrefOf(request: HttpRequest, path: VPath, isCollection: Boolean): String {
        val encoded = if (path.isRoot) "/" else UrlCodec.encodePath(path.value)
        val withBase = request.basePath + encoded
        return DavXml.escape(if (isCollection && !withBase.endsWith("/")) "$withBase/" else withBase)
    }

    /** Maps a `Destination` header, absolute or not, onto a virtual path. */
    private fun destinationPath(request: HttpRequest, header: String): VPath? {
        var value = header.trim()
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            val schemeEnd = value.indexOf("://") + 3
            val slash = value.indexOf('/', schemeEnd)
            value = if (slash < 0) "/" else value.substring(slash)
        }
        val decoded = UrlCodec.decode(value)
        val base = request.basePath
        if (base.isNotEmpty()) {
            if (!decoded.startsWith(base)) return null
            return VPath.of(decoded.substring(base.length))
        }
        return VPath.of(decoded)
    }

    private fun denyIfReadOnly(identity: AuthResult, path: VPath): HttpResponse? {
        if (identity.readOnly || vfs.readOnly) {
            return HttpResponse.error(HttpStatus.FORBIDDEN, "The share is read-only")
        }
        if (!path.isRoot && !vfs.isWritable(path)) {
            return HttpResponse.error(HttpStatus.FORBIDDEN, "This location is read-only")
        }
        return null
    }

    private fun denyIfLocked(request: HttpRequest, path: VPath): HttpResponse? {
        val presented = DavLockManager.parseIfTokens(request.header(HttpHeaderNames.IF))
        return if (locks.isLockedForOthers(path, presented)) {
            HttpResponse.error(HttpStatus.LOCKED, "Resource is locked")
        } else {
            null
        }
    }

    private fun notModifiedResponse(request: HttpRequest, etag: String, lastModified: Long): HttpResponse? {
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

    /** `If-Range` makes a range request conditional on the entity being unchanged. */
    private fun rangeApplies(request: HttpRequest, etag: String, lastModified: Long): Boolean {
        val ifRange = request.header(HttpHeaderNames.IF_RANGE) ?: return true
        if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) return ifRange.trim() == etag
        val date = HttpDates.parse(ifRange) ?: return false
        return lastModified / 1000 <= date / 1000
    }

    private fun appendResponse(
        builder: StringBuilder,
        request: HttpRequest,
        path: VPath,
        entry: VfsEntry,
        requested: PropfindRequest,
    ) {
        builder.append("<D:response><D:href>")
            .append(hrefOf(request, path, entry.isDirectory))
            .append("</D:href>")

        val found = StringBuilder(256)
        val missing = StringBuilder(64)

        if (requested.propName) {
            found.append(PROP_NAMES)
        } else {
            val wanted: List<String> = if (requested.allProp) {
                ALL_PROPS
            } else {
                requested.properties.map { it.second }
            }
            for (name in wanted) {
                val value = renderProperty(name, request, path, entry)
                if (value != null) {
                    found.append(value)
                } else if (!requested.allProp) {
                    missing.append("<D:").append(DavXml.escape(name)).append("/>")
                }
            }
        }

        if (found.isNotEmpty()) {
            builder.append("<D:propstat><D:prop>").append(found)
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        }
        if (missing.isNotEmpty()) {
            builder.append("<D:propstat><D:prop>").append(missing)
                .append("</D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>")
        }
        builder.append("</D:response>")
    }

    private fun renderProperty(
        name: String,
        request: HttpRequest,
        path: VPath,
        entry: VfsEntry,
    ): String? = when (name.lowercase(Locale.ROOT)) {
        "resourcetype" ->
            if (entry.isDirectory) "<D:resourcetype><D:collection/></D:resourcetype>"
            else "<D:resourcetype/>"

        "displayname" ->
            "<D:displayname>${DavXml.escape(if (path.isRoot) "/" else entry.name)}</D:displayname>"

        "getcontentlength" ->
            if (entry.isDirectory) null else "<D:getcontentlength>${entry.size}</D:getcontentlength>"

        "getcontenttype" ->
            "<D:getcontenttype>${DavXml.escape(if (entry.isDirectory) MimeTypes.DIRECTORY else entry.mimeType)}</D:getcontenttype>"

        "getlastmodified" ->
            "<D:getlastmodified>${HttpDates.format(entry.lastModified)}</D:getlastmodified>"

        "creationdate" ->
            "<D:creationdate>${HttpDates.formatIso(entry.lastModified)}</D:creationdate>"

        "getetag" ->
            if (entry.isDirectory) null else "<D:getetag>${DavXml.escape(etagOf(entry))}</D:getetag>"

        "supportedlock" -> SUPPORTED_LOCK

        "lockdiscovery" -> {
            val lock = locks.find(path)
            if (lock == null) {
                "<D:lockdiscovery/>"
            } else {
                val builder = StringBuilder("<D:lockdiscovery>")
                appendActiveLock(builder, request, lock)
                builder.append("</D:lockdiscovery>").toString()
            }
        }

        "quota-available-bytes" -> "<D:quota-available-bytes>${vfs.freeSpace(path)}</D:quota-available-bytes>"

        "quota-used-bytes" -> {
            val total = vfs.totalSpace(path)
            val used = (total - vfs.freeSpace(path)).coerceAtLeast(0)
            "<D:quota-used-bytes>$used</D:quota-used-bytes>"
        }

        else -> null
    }

    private fun appendActiveLock(builder: StringBuilder, request: HttpRequest, lock: DavLock) {
        builder.append("<D:activelock>")
        builder.append("<D:locktype><D:write/></D:locktype>")
        builder.append("<D:lockscope>")
            .append(if (lock.exclusive) "<D:exclusive/>" else "<D:shared/>")
            .append("</D:lockscope>")
        builder.append("<D:depth>").append(if (lock.depth == 0) "0" else "infinity").append("</D:depth>")
        builder.append("<D:owner>").append(DavXml.escape(lock.owner)).append("</D:owner>")
        builder.append("<D:timeout>").append(locks.timeoutHeader(lock)).append("</D:timeout>")
        builder.append("<D:locktoken><D:href>").append(DavXml.escape(lock.token)).append("</D:href></D:locktoken>")
        builder.append("<D:lockroot><D:href>")
            .append(hrefOf(request, lock.path, vfs.stat(lock.path)?.isDirectory == true))
            .append("</D:href></D:lockroot>")
        builder.append("</D:activelock>")
    }

    /** A minimal browsable listing, so pointing a browser at the mount is not a dead end. */
    private fun directoryListingHtml(request: HttpRequest, path: VPath): String {
        val builder = StringBuilder(1024)
        builder.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        builder.append("<title>").append(DavXml.escape(path.value)).append("</title>")
        builder.append("<style>body{font-family:system-ui,sans-serif;background:#111;color:#eee;padding:24px}")
        builder.append("a{color:#7cc4ff;text-decoration:none}li{margin:4px 0}</style></head><body>")
        builder.append("<h1>").append(DavXml.escape(path.value)).append("</h1><ul>")
        path.parent?.let {
            builder.append("<li><a href=\"").append(hrefOf(request, it, true)).append("\">../</a></li>")
        }
        for (child in vfs.list(path)) {
            if (configProvider().hideDotFiles && child.isHidden) continue
            builder.append("<li><a href=\"").append(hrefOf(request, child.path, child.isDirectory)).append("\">")
                .append(DavXml.escape(child.name))
                .append(if (child.isDirectory) "/" else "")
                .append("</a></li>")
        }
        builder.append("</ul></body></html>")
        return builder.toString()
    }

    private fun errorXml(condition: String): String =
        "$XML_DECLARATION<D:error xmlns:D=\"DAV:\"><D:$condition/></D:error>"

    private companion object {
        const val TAG = "WebDavHandler"
        const val MAX_XML_BODY = 256L * 1024
        const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        const val ALLOWED_METHODS =
            "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK"

        val ALL_PROPS = listOf(
            "resourcetype", "displayname", "getcontentlength", "getcontenttype",
            "getlastmodified", "creationdate", "getetag", "supportedlock", "lockdiscovery",
        )

        const val PROP_NAMES = "<D:resourcetype/><D:displayname/><D:getcontentlength/>" +
            "<D:getcontenttype/><D:getlastmodified/><D:creationdate/><D:getetag/>" +
            "<D:supportedlock/><D:lockdiscovery/><D:quota-available-bytes/><D:quota-used-bytes/>"

        const val SUPPORTED_LOCK = "<D:supportedlock>" +
            "<D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>" +
            "<D:lockentry><D:lockscope><D:shared/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>" +
            "</D:supportedlock>"

        fun etagOf(entry: VfsEntry): String =
            "\"" + java.lang.Long.toHexString(entry.size) + "-" + java.lang.Long.toHexString(entry.lastModified) + "\""

        fun statusFor(reason: VfsException.Reason): HttpStatus = when (reason) {
            VfsException.Reason.NOT_FOUND -> HttpStatus.NOT_FOUND
            VfsException.Reason.NOT_A_DIRECTORY -> HttpStatus.CONFLICT
            VfsException.Reason.IS_A_DIRECTORY -> HttpStatus.METHOD_NOT_ALLOWED
            VfsException.Reason.ALREADY_EXISTS -> HttpStatus.METHOD_NOT_ALLOWED
            VfsException.Reason.READ_ONLY -> HttpStatus.FORBIDDEN
            VfsException.Reason.ACCESS_DENIED -> HttpStatus.FORBIDDEN
            VfsException.Reason.INVALID_PATH -> HttpStatus.BAD_REQUEST
            VfsException.Reason.NO_SPACE -> HttpStatus.INSUFFICIENT_STORAGE
            VfsException.Reason.CONFLICT -> HttpStatus.CONFLICT
            VfsException.Reason.IO_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
        }
    }
}
