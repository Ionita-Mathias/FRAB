package ch.genedis.tvfileserver.core.http

/**
 * A small path router.
 *
 * A pattern is either an exact path such as `/api/list`, or a prefix wildcard whose last
 * segment is a single asterisk, matching everything below that prefix.
 * Routes are evaluated in registration order, so register the specific ones first.
 */
class Router {

    private class Route(
        val pattern: String,
        val methods: Set<String>?,
        val handler: HttpHandler,
        val isPrefix: Boolean,
        val isMount: Boolean,
    ) {
        val prefix: String = if (isPrefix) pattern.dropLast(2) else pattern

        fun matchesPath(path: String): Boolean = when {
            isMount -> path == prefix || path.startsWith("$prefix/")
            isPrefix -> path == prefix || path.startsWith("$prefix/") || path.startsWith(prefix)
            else -> path == pattern
        }

        fun matchesMethod(method: String): Boolean = methods == null || method in methods
    }

    private val routes = ArrayList<Route>(32)
    private var fallback: HttpHandler = HttpHandler { HttpResponse.error(HttpStatus.NOT_FOUND) }

    /**
     * Registers [handler] for [pattern].
     *
     * @param methods the accepted verbs, or null to accept any verb.
     */
    fun route(pattern: String, methods: Set<String>?, handler: HttpHandler): Router {
        val isPrefix = pattern.endsWith("/*")
        routes.add(Route(pattern, methods, handler, isPrefix, isMount = false))
        return this
    }

    fun get(pattern: String, handler: HttpHandler): Router = route(pattern, setOf("GET", "HEAD"), handler)

    fun post(pattern: String, handler: HttpHandler): Router = route(pattern, setOf("POST"), handler)

    fun put(pattern: String, handler: HttpHandler): Router = route(pattern, setOf("PUT"), handler)

    fun delete(pattern: String, handler: HttpHandler): Router = route(pattern, setOf("DELETE"), handler)

    fun any(pattern: String, handler: HttpHandler): Router = route(pattern, null, handler)

    /**
     * Forwards every request under [prefix] to [handler] with the prefix stripped.
     *
     * `mount("/dav", h)` matches both `/dav` and `/dav/movies/a.mkv`; the handler sees
     * `/` and `/movies/a.mkv` respectively, and can rebuild absolute URLs from
     * [HttpRequest.basePath].
     */
    fun mount(prefix: String, handler: HttpHandler): Router {
        val normalised = if (prefix.endsWith("/")) prefix.dropLast(1) else prefix
        routes.add(Route(normalised, null, handler, isPrefix = false, isMount = true))
        return this
    }

    /** Replaces the handler used when nothing matches. */
    fun notFound(handler: HttpHandler): Router {
        fallback = handler
        return this
    }

    fun asHandler(): HttpHandler = HttpHandler { request -> handle(request) }

    private fun handle(request: HttpRequest): HttpResponse {
        val allowed = LinkedHashSet<String>(4)
        for (route in routes) {
            if (!route.matchesPath(request.path)) continue
            if (route.matchesMethod(request.method)) {
                return if (route.isMount) {
                    val remainder = request.path.removePrefix(route.prefix)
                    val childPath = if (remainder.isEmpty()) "/" else remainder
                    route.handler.handle(request.withBasePath(route.prefix, childPath))
                } else {
                    route.handler.handle(request)
                }
            }
            route.methods?.let { allowed.addAll(it) }
        }
        if (allowed.isNotEmpty()) {
            allowed.add("OPTIONS")
            return HttpResponse.error(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaderNames.ALLOW, allowed.joinToString(", "))
        }
        return fallback.handle(request)
    }
}
