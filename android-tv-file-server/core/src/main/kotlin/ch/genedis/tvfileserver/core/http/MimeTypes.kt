package ch.genedis.tvfileserver.core.http

import java.util.Locale

/**
 * Extension-to-MIME mapping.
 *
 * Android's `MimeTypeMap` is not available in this module and its coverage of media
 * container formats is poor anyway, so the table is maintained here. It is biased towards
 * the file types a TV box actually stores.
 */
object MimeTypes {

    const val OCTET_STREAM = "application/octet-stream"
    const val DIRECTORY = "inode/directory"

    private val TYPES: Map<String, String> = buildMap {
        // Documents and text
        put("html", "text/html"); put("htm", "text/html")
        put("css", "text/css")
        put("js", "text/javascript"); put("mjs", "text/javascript")
        put("json", "application/json")
        put("xml", "application/xml")
        put("txt", "text/plain"); put("log", "text/plain")
        put("md", "text/markdown")
        put("csv", "text/csv")
        put("conf", "text/plain"); put("ini", "text/plain")
        put("yml", "application/yaml"); put("yaml", "application/yaml")
        put("nfo", "text/plain")
        put("pdf", "application/pdf")
        put("doc", "application/msword")
        put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        put("xls", "application/vnd.ms-excel")
        put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        put("ppt", "application/vnd.ms-powerpoint")
        put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        put("odt", "application/vnd.oasis.opendocument.text")
        put("ods", "application/vnd.oasis.opendocument.spreadsheet")
        put("epub", "application/epub+zip")

        // Images
        put("png", "image/png")
        put("jpg", "image/jpeg"); put("jpeg", "image/jpeg")
        put("gif", "image/gif")
        put("webp", "image/webp")
        put("bmp", "image/bmp")
        put("svg", "image/svg+xml")
        put("ico", "image/x-icon")
        put("heic", "image/heic"); put("heif", "image/heif")
        put("avif", "image/avif")

        // Video
        put("mp4", "video/mp4"); put("m4v", "video/x-m4v")
        put("mkv", "video/x-matroska")
        put("avi", "video/x-msvideo")
        put("mov", "video/quicktime")
        put("webm", "video/webm")
        put("mpg", "video/mpeg"); put("mpeg", "video/mpeg")
        put("ts", "video/mp2t"); put("m2ts", "video/mp2t")
        put("wmv", "video/x-ms-wmv")
        put("flv", "video/x-flv")
        put("3gp", "video/3gpp")

        // Audio
        put("mp3", "audio/mpeg")
        put("flac", "audio/flac")
        put("aac", "audio/aac")
        put("ogg", "audio/ogg"); put("oga", "audio/ogg"); put("opus", "audio/opus")
        put("wav", "audio/wav")
        put("m4a", "audio/mp4")
        put("wma", "audio/x-ms-wma")

        // Archives and packages
        put("zip", "application/zip")
        put("7z", "application/x-7z-compressed")
        put("rar", "application/vnd.rar")
        put("gz", "application/gzip"); put("tgz", "application/gzip")
        put("bz2", "application/x-bzip2")
        put("xz", "application/x-xz")
        put("tar", "application/x-tar")
        put("iso", "application/x-iso9660-image")
        put("apk", "application/vnd.android.package-archive")
        put("torrent", "application/x-bittorrent")

        // Subtitles and activity tracks
        put("srt", "application/x-subrip")
        put("vtt", "text/vtt")
        put("ass", "text/x-ssa"); put("ssa", "text/x-ssa")
        put("sub", "text/plain")
        put("fit", "application/vnd.ant.fit")
        put("gpx", "application/gpx+xml")
        put("tcx", "application/vnd.garmin.tcx+xml")
    }

    /** Types whose payload is already compressed, so re-deflating them is wasted CPU. */
    private val PRECOMPRESSED_EXTENSIONS: Set<String> = setOf(
        "zip", "7z", "rar", "gz", "tgz", "bz2", "xz", "apk", "iso", "torrent", "epub",
        "mp4", "m4v", "mkv", "avi", "mov", "webm", "mpg", "mpeg", "ts", "m2ts", "wmv",
        "flv", "3gp", "mp3", "flac", "aac", "ogg", "oga", "opus", "m4a", "wma",
        "png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "avif",
        "docx", "xlsx", "pptx", "odt", "ods",
    )

    /** Returns the MIME type for [name], or [OCTET_STREAM] when the extension is unknown. */
    fun forFileName(name: String): String = TYPES[extensionOf(name)] ?: OCTET_STREAM

    /** True when [mime] identifies human-readable content that a browser can display inline. */
    fun isText(mime: String): Boolean {
        val base = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return base.startsWith("text/") ||
            base == "application/json" ||
            base == "application/xml" ||
            base == "application/yaml" ||
            base == "application/javascript" ||
            base == "application/x-subrip" ||
            base.endsWith("+xml") ||
            base.endsWith("+json")
    }

    /** True when re-compressing this file in a ZIP archive would not pay for itself. */
    fun isPrecompressed(name: String): Boolean = extensionOf(name) in PRECOMPRESSED_EXTENSIONS

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }
}
