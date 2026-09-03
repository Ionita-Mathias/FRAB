package ch.genedis.tvfileserver.core.http

/** An HTTP status line value: a numeric [code] plus its canonical [reason] phrase. */
data class HttpStatus(val code: Int, val reason: String) {

    override fun toString(): String = "$code $reason"

    val isSuccess: Boolean get() = code in 200..299
    val isRedirect: Boolean get() = code in 300..399
    val isError: Boolean get() = code >= 400

    companion object {
        val CONTINUE = HttpStatus(100, "Continue")
        val OK = HttpStatus(200, "OK")
        val CREATED = HttpStatus(201, "Created")
        val ACCEPTED = HttpStatus(202, "Accepted")
        val NO_CONTENT = HttpStatus(204, "No Content")
        val PARTIAL_CONTENT = HttpStatus(206, "Partial Content")
        val MULTI_STATUS = HttpStatus(207, "Multi-Status")
        val MOVED_PERMANENTLY = HttpStatus(301, "Moved Permanently")
        val FOUND = HttpStatus(302, "Found")
        val SEE_OTHER = HttpStatus(303, "See Other")
        val NOT_MODIFIED = HttpStatus(304, "Not Modified")
        val TEMPORARY_REDIRECT = HttpStatus(307, "Temporary Redirect")
        val BAD_REQUEST = HttpStatus(400, "Bad Request")
        val UNAUTHORIZED = HttpStatus(401, "Unauthorized")
        val FORBIDDEN = HttpStatus(403, "Forbidden")
        val NOT_FOUND = HttpStatus(404, "Not Found")
        val METHOD_NOT_ALLOWED = HttpStatus(405, "Method Not Allowed")
        val NOT_ACCEPTABLE = HttpStatus(406, "Not Acceptable")
        val REQUEST_TIMEOUT = HttpStatus(408, "Request Timeout")
        val CONFLICT = HttpStatus(409, "Conflict")
        val GONE = HttpStatus(410, "Gone")
        val LENGTH_REQUIRED = HttpStatus(411, "Length Required")
        val PRECONDITION_FAILED = HttpStatus(412, "Precondition Failed")
        val PAYLOAD_TOO_LARGE = HttpStatus(413, "Payload Too Large")
        val URI_TOO_LONG = HttpStatus(414, "URI Too Long")
        val UNSUPPORTED_MEDIA_TYPE = HttpStatus(415, "Unsupported Media Type")
        val RANGE_NOT_SATISFIABLE = HttpStatus(416, "Range Not Satisfiable")
        val EXPECTATION_FAILED = HttpStatus(417, "Expectation Failed")
        val UNPROCESSABLE_ENTITY = HttpStatus(422, "Unprocessable Entity")
        val LOCKED = HttpStatus(423, "Locked")
        val FAILED_DEPENDENCY = HttpStatus(424, "Failed Dependency")
        val UPGRADE_REQUIRED = HttpStatus(426, "Upgrade Required")
        val TOO_MANY_REQUESTS = HttpStatus(429, "Too Many Requests")
        val REQUEST_HEADER_FIELDS_TOO_LARGE = HttpStatus(431, "Request Header Fields Too Large")
        val INTERNAL_SERVER_ERROR = HttpStatus(500, "Internal Server Error")
        val NOT_IMPLEMENTED = HttpStatus(501, "Not Implemented")
        val BAD_GATEWAY = HttpStatus(502, "Bad Gateway")
        val SERVICE_UNAVAILABLE = HttpStatus(503, "Service Unavailable")
        val INSUFFICIENT_STORAGE = HttpStatus(507, "Insufficient Storage")

        private val BY_CODE: Map<Int, HttpStatus> = listOf(
            CONTINUE, OK, CREATED, ACCEPTED, NO_CONTENT, PARTIAL_CONTENT, MULTI_STATUS,
            MOVED_PERMANENTLY, FOUND, SEE_OTHER, NOT_MODIFIED, TEMPORARY_REDIRECT,
            BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, METHOD_NOT_ALLOWED,
            NOT_ACCEPTABLE, REQUEST_TIMEOUT, CONFLICT, GONE, LENGTH_REQUIRED,
            PRECONDITION_FAILED, PAYLOAD_TOO_LARGE, URI_TOO_LONG, UNSUPPORTED_MEDIA_TYPE,
            RANGE_NOT_SATISFIABLE, EXPECTATION_FAILED, UNPROCESSABLE_ENTITY, LOCKED,
            FAILED_DEPENDENCY, UPGRADE_REQUIRED, TOO_MANY_REQUESTS,
            REQUEST_HEADER_FIELDS_TOO_LARGE, INTERNAL_SERVER_ERROR, NOT_IMPLEMENTED,
            BAD_GATEWAY, SERVICE_UNAVAILABLE, INSUFFICIENT_STORAGE,
        ).associateBy { it.code }

        /** Returns the canonical status for [code], or a generic one for unknown codes. */
        fun of(code: Int): HttpStatus = BY_CODE[code] ?: HttpStatus(code, genericReason(code))

        private fun genericReason(code: Int): String = when (code / 100) {
            1 -> "Informational"
            2 -> "Success"
            3 -> "Redirection"
            4 -> "Client Error"
            5 -> "Server Error"
            else -> "Unknown"
        }
    }
}
