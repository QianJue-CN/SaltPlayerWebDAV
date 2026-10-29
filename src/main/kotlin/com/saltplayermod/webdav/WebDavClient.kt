package com.saltplayermod.webdav

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal class SyncConflict(message: String) : IOException(message)

internal data class RemoteTrack(
    val path: String,
    val url: HttpUrl,
    val size: Long,
    val etag: String,
    val modified: Instant?,
) {
    val name: String get() = path.substringAfterLast('/')
    val fingerprint: String? get() = when {
        etag.isNotBlank() -> digest("$etag|$size")
        modified != null && size >= 0 -> digest("$modified|$size")
        else -> null
    }
}

internal fun digest(value: String): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

internal fun checkCancelled() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("操作已取消")
}

internal fun davRoot(address: String, folder: String, alist: Boolean): HttpUrl {
    require(address.isNotBlank()) { "请填写服务器地址" }
    val parsed = address.trim().toHttpUrl()
    require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "请在账号栏填写认证信息" }
    require(parsed.query == null && parsed.fragment == null) { "服务器地址不能包含查询参数或片段" }
    val builder = parsed.newBuilder()
    if (alist && "dav" !in parsed.pathSegments) builder.addPathSegment("dav")
    for (part in folder.trim('/').split('/').filter { it.isNotEmpty() }) {
        require(part != "." && part != ".." && part.none { it == '\\' || it.code < 32 }) { "远端目录包含无效路径" }
        builder.addPathSegment(part)
    }
    if (builder.build().pathSegments.last() != "") builder.addPathSegment("")
    return builder.build()
}

internal class WebDavClient(
    val root: HttpUrl,
    username: String,
    password: String,
) : Closeable {
    val sourceIdentity = digest("$root|$username")
    private val authorization = if (username.isEmpty() && password.isEmpty()) null
        else Credentials.basic(username, password, Charsets.UTF_8)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val rootSegments = root.pathSegments.dropLastWhile { it.isEmpty() }

    init {
        require(':' !in username) { "用户名不能包含冒号" }
    }

    fun request(
        url: HttpUrl, method: String = "GET", range: String? = null,
        body: RequestBody? = null, headers: Map<String, String> = emptyMap(), depth: String = "1",
    ): Response {
        var current = url
        repeat(9) { redirect ->
            checkCancelled()
            val builder = Request.Builder().url(current)
                .header("User-Agent", "SaltPlayerWebDAV/0.1.0")
                .header("Accept-Encoding", "identity")
            if (sameOrigin(current, root) && authorization != null) builder.header("Authorization", authorization)
            if (range != null) builder.header("Range", range)
            for ((name, value) in headers) builder.header(name, value)
            if (method == "PROPFIND") {
                builder.header("Depth", depth).method(method, PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            } else builder.method(method, body)
            val response = http.newCall(builder.build()).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            val next = location?.let { current.resolve(it) }
            response.close()
            if (next == null || redirect == 8) throw IOException("服务器重定向无效或次数过多")
            if (current.isHttps && !next.isHttps) throw IOException("服务器尝试将 HTTPS 请求降级为 HTTP")
            if (next.username.isNotEmpty() || next.password.isNotEmpty()) throw IOException("重定向地址包含账号信息")
            if (method !in setOf("GET", "HEAD") && (!sameOrigin(next, root) || !insideRoot(next))) {
                throw IOException("WebDAV 请求被重定向到配置目录以外，请检查服务器地址")
            }
            current = next
        }
        error("Unreachable")
    }

    fun scan(progress: (String) -> Unit = {}): List<RemoteTrack> {
        val pending = ArrayDeque<HttpUrl>()
        val visited = HashSet<String>()
        val tracks = linkedMapOf<String, RemoteTrack>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            checkCancelled()
            val directory = pending.removeFirst()
            if (!visited.add(directory.toString())) continue
            if (visited.size > 50_000) throw IOException("目录数量过多，请选择更具体的音乐目录")
            progress("扫描目录 ${visited.size}，已找到 ${tracks.size} 首音乐")
            request(directory, "PROPFIND").use { response ->
                requireSuccess(response, setOf(200, 207))
                val bytes = response.body?.byteStream()?.readNBytes(MAX_XML_BYTES + 1)
                    ?: throw IOException("服务器返回空目录响应")
                if (bytes.size > MAX_XML_BYTES) throw IOException("目录响应过大，请缩小远端目录范围")
                val entries = parseDirectory(bytes, directory)
                for ((track, collection) in entries) {
                    if (collection) {
                        if (track.path.split('/').any { it == BACKUP_DIRECTORY }) continue
                        val child = track.url.newBuilder().apply {
                            if (track.url.pathSegments.last() != "") addPathSegment("")
                        }.build()
                        if (child != directory) pending.add(child)
                    } else if (isAudioPath(track.path)) {
                        val old = tracks.putIfAbsent(track.path, track)
                        if (old != null && old.url != track.url) throw IOException("远端返回重复文件路径")
                        if (tracks.size > 200_000) throw IOException("文件数量过多，请选择更具体的音乐目录")
                    }
                }
            }
        }
        return tracks.values.sortedBy { it.path.lowercase() }
    }

    private fun parseDirectory(bytes: ByteArray, directory: HttpUrl, includeSelf: Boolean = false): List<Pair<RemoteTrack, Boolean>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(exception: SAXParseException) { throw exception }
                override fun fatalError(exception: SAXParseException) { throw exception }
            })
        }
        val document = bytes.inputStream().use(builder::parse)
        require(document.documentElement.localName == "multistatus") { "服务器未返回 WebDAV 目录，请检查地址是否包含 /dav/" }
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        val result = mutableListOf<Pair<RemoteTrack, Boolean>>()
        for (index in 0 until responses.length) {
            val element = responses.item(index) as Element
            val href = element.text("href") ?: throw IOException("WebDAV 响应缺少文件地址")
            val url = resolveHref(directory, href)
            val relative = url.pathSegments.drop(rootSegments.size).dropLastWhile { it.isEmpty() }
            val propstats = element.getElementsByTagNameNS("DAV:", "propstat")
            var prop: Element? = null
            for (p in 0 until propstats.length) {
                val stat = propstats.item(p) as Element
                if (stat.text("status")?.split(' ')?.getOrNull(1)?.toIntOrNull() in 200..299) {
                    prop = stat.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element
                    if (prop != null) break
                }
            }
            val properties = prop ?: throw IOException("无法读取远端文件属性，请检查目录权限")
            if (relative.isEmpty()) continue
            if (!includeSelf && url.encodedPath.trimEnd('/') == directory.encodedPath.trimEnd('/')) continue
            val collection = properties.getElementsByTagNameNS("DAV:", "collection").length > 0
            val modified = properties.text("getlastmodified")?.let {
                runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            }
            result += RemoteTrack(relative.joinToString("/"), url,
                properties.text("getcontentlength")?.toLongOrNull() ?: -1,
                properties.text("getetag") ?: "", modified) to collection
        }
        return result
    }

    private fun resolveHref(directory: HttpUrl, href: String): HttpUrl {
        val encoded = try { URI(href).toASCIIString() } catch (_: Exception) {
            href.replace(" ", "%20")
        }
        val url = directory.resolve(encoded) ?: throw IOException("远端文件地址无效")
        if (!sameOrigin(url, root) || !insideRoot(url) || url.query != null || url.fragment != null ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("远端返回了音乐目录以外的文件地址")
        if (url.pathSegments.any { part -> part == "." || part == ".." || part.any { it == '/' || it == '\\' || it.code < 32 } }) {
            throw IOException("远端文件名包含无效路径字符")
        }
        return url
    }

    private fun insideRoot(url: HttpUrl): Boolean = url.pathSegments.take(rootSegments.size) == rootSegments

    fun remoteHash(track: RemoteTrack): String {
        request(track.url).use { response ->
            requireSuccess(response)
            val body = response.body ?: throw IOException("服务器返回空文件")
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val length = input.read(buffer)
                    if (length < 0) break
                    hash.update(buffer, 0, length)
                    count += length
                }
            }
            if (track.size >= 0 && count != track.size) throw IOException("远端文件大小已变化，请重新同步")
            return HexFormat.of().formatHex(hash.digest())
        }
    }

    fun stat(url: HttpUrl): RemoteTrack? = statEntry(url)?.first

    private fun statEntry(url: HttpUrl): Pair<RemoteTrack, Boolean>? {
        request(url, "PROPFIND", depth = "0").use { response ->
            if (response.code == 404) return null
            requireSuccess(response, setOf(200, 207))
            val bytes = response.body?.byteStream()?.readNBytes(MAX_XML_BYTES + 1) ?: throw IOException("服务器返回空文件属性")
            if (bytes.size > MAX_XML_BYTES) throw IOException("文件属性响应过大")
            return parseDirectory(bytes, url, includeSelf = true).singleOrNull()
                ?: throw IOException("服务器返回了无效的文件属性")
        }
    }

    private fun urlFor(path: String): HttpUrl = root.newBuilder().apply {
        for (part in path.split('/').filter { it.isNotEmpty() }) addPathSegment(part)
    }.build()

    private fun ensureDirectory(path: String) {
        var current = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current += "$part/"
            val url = urlFor(current).newBuilder().addPathSegment("").build()
            request(url, "MKCOL").use { response ->
                if (response.code == 405) {
                    if (statEntry(url)?.second != true) throw IOException("远端父路径不是目录")
                } else requireSuccess(response, setOf(201))
            }
        }
    }

    private fun move(source: HttpUrl, destination: HttpUrl, etag: String? = null) {
        val headers = mutableMapOf("Destination" to destination.toString(), "Overwrite" to "F")
        if (!etag.isNullOrBlank() && !etag.startsWith("W/")) headers["If-Match"] = etag
        request(source, "MOVE", headers = headers).use { requireSuccess(it, setOf(201, 204)) }
    }

    fun upload(snapshot: Path, path: String, expected: RemoteTrack?, expectedHash: String?): RemoteTrack {
        ensureDirectory(path.substringBeforeLast('/', ""))
        val destination = urlFor(path)
        val temporary = urlFor(path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + ".saltplayer-webdav-${UUID.randomUUID()}.part")
        var backup: HttpUrl? = null
        var published = false
        try {
            request(temporary, "PUT", body = snapshot.toFile().asRequestBody(), headers = mapOf("If-None-Match" to "*")).use {
                requireSuccess(it, setOf(200, 201, 204))
            }
            val staged = stat(temporary) ?: throw IOException("上传后未找到远端暂存文件")
            if (staged.size >= 0 && staged.size != Files.size(snapshot)) throw IOException("上传文件大小校验失败")
            val current = stat(destination)
            if (expected == null) {
                if (current != null) throw SyncConflict("远端已出现同名文件")
            } else {
                if (current == null || !matchesExpected(current, expected, expectedHash)) throw SyncConflict("上传期间远端文件已修改")
                val backupPath = "$BACKUP_DIRECTORY/${UUID.randomUUID()}/$path"
                ensureDirectory(backupPath.substringBeforeLast('/'))
                val backupUrl = urlFor(backupPath)
                backup = backupUrl
                move(destination, backupUrl, current.etag)
                val saved = stat(backupUrl) ?: throw IOException("无法验证远端原文件备份")
                if (!matchesExpected(saved, expected, expectedHash)) throw SyncConflict("远端出现并发修改，原文件已保留在备份目录")
            }
            checkCancelled()
            move(temporary, destination)
            published = true
            return stat(destination) ?: throw IOException("上传完成但未能读取远端文件属性")
        } catch (failure: Exception) {
            val saved = backup
            if (!published && saved != null) {
                val interrupted = Thread.interrupted()
                try {
                    if (stat(destination) == null) move(saved, destination)
                } catch (_: Exception) {
                    throw IOException("上传未完成；原文件保留在远端备份目录 ${saved.encodedPath}", failure)
                } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
            throw failure
        } finally {
            val interrupted = Thread.interrupted()
            try { request(temporary, "DELETE").use { } } catch (_: Exception) { }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private fun matchesExpected(current: RemoteTrack, expected: RemoteTrack, expectedHash: String?): Boolean =
        (current.fingerprint != null && current.fingerprint == expected.fingerprint) ||
            (expectedHash != null && remoteHash(current) == expectedHash)

    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
    }

    companion object {
        const val BACKUP_DIRECTORY = ".saltplayer-webdav-backups"
        private const val MAX_XML_BYTES = 8 * 1024 * 1024
        fun isAudioPath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "ape", "wma", "alac", "dsf", "dff")
        private const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""

        fun requireSuccess(response: Response, accepted: Set<Int> = setOf(200)) {
            if (response.code in accepted) return
            val message = when (response.code) {
                401 -> "认证失败，请检查 WebDAV 用户名和密码"
                403 -> "服务器拒绝访问，请检查账号与目录权限"
                404 -> "远端目录或文件不存在"
                405, 501 -> "服务器未启用 WebDAV 或不支持该请求"
                409 -> "远端目录不存在或存在文件名冲突"
                412 -> "远端文件已变化或已有同名文件，已停止覆盖"
                else -> "服务器返回 HTTP ${response.code}"
            }
            if (response.code == 412) throw SyncConflict(message)
            throw IOException(message)
        }

        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
        private fun Element.text(name: String): String? =
            getElementsByTagNameNS("DAV:", name).item(0)?.textContent?.trim()
    }
}
