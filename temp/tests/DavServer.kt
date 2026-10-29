package com.saltplayermod.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal class DavServer : Closeable {
    data class Seen(val method: String, val path: String, val authorization: String?, val range: String?)
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val directories = ConcurrentHashMap.newKeySet<String>().apply { add("/"); add("/dav/"); add("/dav/music/") }
    val seen = CopyOnWriteArrayList<Seen>()
    val redirects = ConcurrentHashMap<String, String>()
    val publicPaths = ConcurrentHashMap.newKeySet<String>()
    var listingOverride: String? = null
    var omitValidators = false
    var listedExtraSize = 0L
    var ignoreIfMatch = false
    var etagPerPath = false
    var authUser = "user"
    var before: ((HttpExchange) -> Boolean)? = null
    private val executor = Executors.newCachedThreadPool { Thread(it, "mock-webdav").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@DavServer.executor
        createContext("/") { exchange ->
            try { handle(exchange) }
            catch (failure: Exception) { try { respond(exchange, 500, (failure.message ?: "fixture failure").toByteArray()) } catch (_: Exception) { exchange.close() } }
        }
        start()
    }
    val address = "http://127.0.0.1:${server.address.port}"
    val root get() = (address + "/dav/music/").toHttpUrl()
    fun client() = WebDavClient(root, "user", "pass")
    fun put(path: String, text: String) {
        var current = "/"
        for (part in path.trim('/').split('/').dropLast(1)) { current += "$part/"; directories.add(current) }
        files[path] = text.toByteArray(Charsets.UTF_8)
    }
    fun content(path: String) = files[path]?.toString(Charsets.UTF_8)
    private fun etag(path: String, bytes: ByteArray) = "\"" + digest(Base64.getEncoder().encodeToString(bytes) + if (etagPerPath) path else "") + "\""

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        val auth = exchange.requestHeaders.getFirst("Authorization")
        seen += Seen(method, path, auth, exchange.requestHeaders.getFirst("Range"))
        if (before?.invoke(exchange) == true) return
        val expected = "Basic " + Base64.getEncoder().encodeToString("$authUser:pass".toByteArray())
        if (path !in publicPaths && auth != expected) { respond(exchange, 401); return }
        redirects[path]?.let { location -> exchange.responseHeaders.set("Location", location); respond(exchange, 302); return }
        when (method) {
            "PROPFIND" -> {
                listingOverride?.let { respond(exchange, 207, it.toByteArray()); return }
                val directory = if (path.endsWith('/')) path else "$path/"
                if (path !in files && directory !in directories) { respond(exchange, 404); return }
                val all = mutableListOf(if (path in files) path else directory)
                if (exchange.requestHeaders.getFirst("Depth") != "0" && path !in files) {
                    all += directories.filter { it != directory && it.startsWith(directory) && '/' !in it.removePrefix(directory).trimEnd('/') }
                    all += files.keys.filter { it.startsWith(directory) && '/' !in it.removePrefix(directory) }
                }
                val xml = buildString {
                    append("<d:multistatus xmlns:d=\"DAV:\">")
                    for (entry in all.sorted()) {
                        val content = files[entry]
                        val href = URI(null, null, entry, null).toASCIIString().replace("&", "&amp;")
                        append("<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>")
                        if (content == null) append("<d:collection/>")
                        append("</d:resourcetype>")
                        if (content != null) append("<d:getcontentlength>${content.size + listedExtraSize}</d:getcontentlength>")
                        if (!omitValidators) {
                            append("<d:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</d:getlastmodified>")
                            if (content != null) append("<d:getetag>${etag(entry, content)}</d:getetag>")
                        }
                        append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                    }
                    append("</d:multistatus>")
                }
                respond(exchange, 207, xml.toByteArray(Charsets.UTF_8))
            }
            "GET", "HEAD" -> {
                val data = files[path] ?: run { respond(exchange, 404); return }
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("Content-Type", "audio/mpeg")
                if (!omitValidators) exchange.responseHeaders.set("ETag", etag(path, data))
                val range = exchange.requestHeaders.getFirst("Range")
                if (range != null) {
                    val match = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range)
                    val start = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val end = (match?.groupValues?.get(2)?.toIntOrNull() ?: (data.size - 1)).coerceAtMost(data.size - 1)
                    if (start < 0 || start > end) { exchange.responseHeaders.set("Content-Range", "bytes */${data.size}"); respond(exchange, 416); return }
                    exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${data.size}")
                    respond(exchange, 206, data.copyOfRange(start, end + 1))
                } else if (method == "HEAD") {
                    exchange.responseHeaders.set("Content-Length", data.size.toString())
                    exchange.sendResponseHeaders(200, -1); exchange.close()
                } else respond(exchange, 200, data)
            }
            "MKCOL" -> {
                val directory = path.trimEnd('/') + "/"
                if (directory in directories || path in files) { respond(exchange, 405); return }
                val parent = directory.trimEnd('/').substringBeforeLast('/', "") + "/"
                if (parent !in directories) { respond(exchange, 409); return }
                directories.add(directory); respond(exchange, 201)
            }
            "PUT" -> {
                if (exchange.requestHeaders.getFirst("If-None-Match") == "*" && path in files) { respond(exchange, 412); return }
                if (path.substringBeforeLast('/') + "/" !in directories) { respond(exchange, 409); return }
                files[path] = exchange.requestBody.readAllBytes(); respond(exchange, 201)
            }
            "MOVE" -> synchronized(files) {
                val source = files[path] ?: run { respond(exchange, 404); return }
                val destination = URI(exchange.requestHeaders.getFirst("Destination")).path
                if (exchange.requestHeaders.getFirst("Overwrite") == "F" && destination in files) { respond(exchange, 412); return }
                val match = exchange.requestHeaders.getFirst("If-Match")
                if (!ignoreIfMatch && match != null && match != etag(path, source)) { respond(exchange, 412); return }
                if (destination.substringBeforeLast('/') + "/" !in directories) { respond(exchange, 409); return }
                files[destination] = source
                files.remove(path)
                respond(exchange, 201)
            }
            "DELETE" -> respond(exchange, if (files.remove(path) != null) 204 else 404)
            else -> respond(exchange, 405)
        }
    }

    fun respond(exchange: HttpExchange, code: Int, body: ByteArray = byteArrayOf()) {
        if (body.isEmpty()) { exchange.sendResponseHeaders(code, -1); exchange.close() }
        else { exchange.sendResponseHeaders(code, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
    }

    override fun close() { server.stop(0); executor.shutdownNow() }
}
