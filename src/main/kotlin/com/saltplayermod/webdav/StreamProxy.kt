package com.saltplayermod.webdav

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class StreamProxy(private val client: WebDavClient) : NanoHTTPD("127.0.0.1", 0), Closeable {
    private val streams = ConcurrentHashMap<String, RemoteTrack>()
    private val urls = ConcurrentHashMap<String, String>()

    fun urlFor(track: RemoteTrack): String = urls.computeIfAbsent(track.path) {
        val token = UUID.randomUUID().toString()
        streams[token] = track
        "http://127.0.0.1:$listeningPort/stream/$token/" + URLEncoder.encode(track.name, Charsets.UTF_8).replace("+", "%20")
    }

    fun trackInfo(track: RemoteTrack, reader: HostTagReader): TrackInfo = RemoteTrackInfo(client, reader).read(track)

    fun lyrics(track: RemoteTrack, embedded: String? = null): String? =
        try { readSidecarLyrics(client, track) ?: embedded } catch (_: Exception) { embedded }

    fun checkPlayable(track: RemoteTrack) {
        client.request(track.url, range = "bytes=0-0").use { response ->
            WebDavClient.requireSuccess(response, setOf(206))
            val range = response.header("Content-Range")
            if (range == null || !range.matches(Regex("bytes 0-0/[1-9][0-9]*")) || response.body?.byteStream()?.read() == -1) {
                throw IOException("服务器不支持有效的 HTTP Range 分段读取，无法在线播放")
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) return text(Response.Status.METHOD_NOT_ALLOWED, "Only GET and HEAD are supported")
        val path = session.uri.split('/')
        val track = if (path.size >= 4 && path[1] == "stream") streams[path[2]] else null
        if (track == null) return text(Response.Status.NOT_FOUND, "Stream not found")
        val range = session.headers["range"]
        if (range != null && !range.matches(Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))) {
            return text(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid byte range")
        }
        try {
            val upstream = client.request(track.url, session.method.name, range)
            val status = Response.Status.lookup(upstream.code)
            if (status == null || upstream.code !in setOf(200, 206, 416)) {
                upstream.close()
                return text(badGateway, "Remote server rejected the request")
            }
            if (range != null && upstream.code == 200) {
                upstream.close()
                return text(badGateway, "Remote server does not support byte ranges")
            }
            val body = upstream.body
            val mime = upstream.header("Content-Type") ?: "application/octet-stream"
            val contentLength = upstream.header("Content-Length")?.toLongOrNull() ?: body?.contentLength() ?: -1
            val input = object : FilterInputStream(body?.byteStream() ?: ByteArrayInputStream(byteArrayOf())) {
                override fun close() { try { super.close() } finally { upstream.close() } }
            }
            val response = if (contentLength >= 0) newFixedLengthResponse(status, mime, input, contentLength)
                else newChunkedResponse(status, mime, input)
            for (header in listOf("Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
                upstream.header(header)?.let { response.addHeader(header, it) }
            }
            response.addHeader("Cache-Control", "no-store")
            return response
        } catch (_: Exception) {
            return text(badGateway, "Unable to read the remote stream")
        }
    }

    override fun close() {
        stop()
        streams.clear()
        urls.clear()
    }

    private fun text(status: Response.IStatus, body: String) = newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    companion object {
        private val badGateway = object : Response.IStatus {
            override fun getRequestStatus() = 502
            override fun getDescription() = "502 Bad Gateway"
        }
    }
}
