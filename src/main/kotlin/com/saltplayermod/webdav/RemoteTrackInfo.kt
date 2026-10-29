package com.saltplayermod.webdav

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

internal data class TrackInfo(
    val title: String = "", val artist: String = "", val album: String = "", val albumArtist: String = "",
    val lyrics: String? = null,
) {
    fun queueFields(id: String, track: RemoteTrack, playbackPath: String): List<String> = listOf(
        id, title.ifBlank { track.name.substringBeforeLast('.') }, artist,
        album.ifBlank { track.path.substringBeforeLast('/', "") }, albumArtist, playbackPath,
    )
}

internal class HostTagReader(private val loader: ClassLoader) {
    private val tagType = loader.loadClass("org.jaudiotagger.tag.Tag")
    private val keyType = loader.loadClass("org.jaudiotagger.tag.FieldKey")
    private val getFirst = tagType.getMethod("getFirst", keyType)

    private fun fields(tag: Any): TrackInfo {
        fun value(key: String): String {
            val field = keyType.enumConstants.single { (it as Enum<*>).name == key }
            return (getFirst.invoke(tag, field) as? String).orEmpty().trim('\u0000', ' ', '\r', '\n').take(512 * 1024)
        }
        return TrackInfo(value("TITLE"), value("ARTIST"), value("ALBUM"), value("ALBUM_ARTIST"), value("LYRICS").ifBlank { null })
    }

    fun id3(bytes: ByteArray, version: Int): TrackInfo {
        require(version in 2..4)
        val type = loader.loadClass("org.jaudiotagger.tag.id3.ID3v2${version}Tag")
        return fields(type.getConstructor(ByteBuffer::class.java).newInstance(ByteBuffer.wrap(bytes)))
    }

    fun id3v1(bytes: ByteArray): TrackInfo {
        val type = loader.loadClass("org.jaudiotagger.tag.id3.ID3v1Tag")
        val tag = type.getConstructor().newInstance()
        type.getMethod("read", ByteBuffer::class.java).invoke(tag, ByteBuffer.wrap(bytes))
        return fields(tag)
    }

    fun vorbis(bytes: ByteArray): TrackInfo {
        val type = loader.loadClass("org.jaudiotagger.tag.vorbiscomment.VorbisCommentReader")
        return fields(type.getMethod("read", ByteArray::class.java, Boolean::class.javaPrimitiveType)
            .invoke(type.getConstructor().newInstance(), bytes, false))
    }

    fun mp4(ftyp: ByteArray, moov: ByteArray): TrackInfo {
        val temporary = Files.createTempFile("webdav-tags-", ".m4a")
        try {
            Files.newOutputStream(temporary).use { it.write(ftyp); it.write(moov) }
            val type = loader.loadClass("org.jaudiotagger.audio.mp4.Mp4TagReader")
            return fields(type.getMethod("read", java.nio.file.Path::class.java).invoke(type.getConstructor().newInstance(), temporary))
        } finally { Files.deleteIfExists(temporary) }
    }
}

internal class RemoteTrackInfo(private val client: WebDavClient, private val reader: HostTagReader) {
    fun read(track: RemoteTrack): TrackInfo {
        val source = Ranges(client, track)
        val header = source.read(0, 12)
        return when {
            header.text(0, 3) == "ID3" -> {
                require(header.size >= 10)
                var length = 0
                for (i in 6..9) { require(header[i].toInt() and 128 == 0); length = (length shl 7) or (header[i].toInt() and 127) }
                reader.id3(source.read(0, Math.addExact(length, 10)), header[3].toInt())
            }
            header.text(0, 4) == "fLaC" -> flac(source)
            header.text(4, 4) == "ftyp" -> mp4(source)
            header.text(0, 4) == "OggS" -> ogg(source)
            track.size >= 128 -> {
                val tail = source.read(track.size - 128, 128)
                if (tail.text(0, 3) == "TAG") reader.id3v1(tail) else TrackInfo()
            }
            else -> TrackInfo()
        }
    }

    private fun flac(source: Ranges): TrackInfo {
        var offset = 4L
        repeat(128) {
            val block = source.read(offset, 4)
            require(block.size == 4)
            val length = ((block[1].toInt() and 255) shl 16) or ((block[2].toInt() and 255) shl 8) or (block[3].toInt() and 255)
            if (block[0].toInt() and 127 == 4) return reader.vorbis(source.read(offset + 4, length))
            if (block[0].toInt() and 128 != 0) return TrackInfo()
            offset += 4 + length
        }
        throw IOException("FLAC 标签块数量过多")
    }

    private fun mp4(source: Ranges): TrackInfo {
        var offset = 0L
        var ftyp = byteArrayOf()
        repeat(128) {
            val header = source.read(offset, 16)
            if (header.size < 8) return TrackInfo()
            var length = header.uint32(0)
            if (length == 1L) { require(header.size == 16); length = ByteBuffer.wrap(header, 8, 8).long }
            if (length == 0L) length = source.size - offset
            require(length >= 8 && offset <= Long.MAX_VALUE - length) { "MP4 标签块长度无效" }
            when (header.text(4, 4)) {
                "ftyp" -> ftyp = source.read(offset, Math.toIntExact(length))
                "moov" -> return reader.mp4(ftyp, source.read(offset, Math.toIntExact(length)))
            }
            offset += length
            if (source.size >= 0 && offset >= source.size) return TrackInfo()
        }
        throw IOException("MP4 标签块数量过多")
    }

    private fun ogg(source: Ranges): TrackInfo {
        var offset = 0L
        var serial: Int? = null
        var packet = ByteArrayOutputStream()
        repeat(128) {
            val header = source.read(offset, 27)
            require(header.size == 27 && header.text(0, 4) == "OggS")
            val pageSerial = ByteBuffer.wrap(header, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (serial == null) serial = pageSerial
            require(serial == pageSerial) { "不支持交错的 Ogg 标签流" }
            val segments = source.read(offset + 27, header[26].toInt() and 255)
            val total = segments.sumOf { it.toInt() and 255 }
            val payload = source.read(offset + 27 + segments.size, total)
            require(payload.size == total)
            var start = 0
            for (segment in segments) {
                val count = segment.toInt() and 255
                require(packet.size() + count <= MAX_TAG_BYTES)
                packet.write(payload, start, count)
                start += count
                if (count < 255) {
                    val bytes = packet.toByteArray()
                    if (bytes.text(0, 8) == "OpusTags") return reader.vorbis(bytes.copyOfRange(8, bytes.size))
                    if (bytes.firstOrNull() == 3.toByte() && bytes.text(1, 6) == "vorbis") return reader.vorbis(bytes.copyOfRange(7, bytes.size))
                    packet = ByteArrayOutputStream()
                }
            }
            offset += 27 + segments.size + total
        }
        throw IOException("Ogg 标签页数量过多")
    }

    private class Ranges(private val client: WebDavClient, private val track: RemoteTrack) {
        var size = track.size
            private set
        private var consumed = 0
        fun read(offset: Long, count: Int): ByteArray {
            require(offset >= 0 && count >= 0 && count <= MAX_TAG_BYTES - consumed) { "标签数据超过读取上限" }
            if (count == 0 || (size >= 0 && offset >= size)) return byteArrayOf()
            val end = if (size >= 0) minOf(size - 1, Math.addExact(offset, count.toLong() - 1)) else Math.addExact(offset, count.toLong() - 1)
            client.request(track.url, range = "bytes=$offset-$end").use { response ->
                WebDavClient.requireSuccess(response, setOf(200, 206))
                var expected = Math.toIntExact(end - offset + 1)
                if (response.code == 206) {
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw IOException("标签读取的分段响应无效")
                    val total = range.groupValues[3].toLong()
                    val actualEnd = range.groupValues[2].toLong()
                    require(total > offset && range.groupValues[1].toLong() == offset && actualEnd == minOf(end, total - 1))
                    require(size < 0 || total == size) { "读取期间音乐文件已变更" }
                    size = total
                    expected = Math.toIntExact(actualEnd - offset + 1)
                } else require(offset == 0L) { "服务器不支持标签分段读取" }
                val bytes = response.body?.byteStream()?.readNBytes(expected) ?: throw IOException("标签响应为空")
                require(response.code != 206 || bytes.size == expected) { "标签数据不完整" }
                consumed += bytes.size
                return bytes
            }
        }
    }

    companion object { private const val MAX_TAG_BYTES = 8 * 1024 * 1024 }
}

internal fun readSidecarLyrics(client: WebDavClient, track: RemoteTrack): String? {
    val stem = track.name.substringBeforeLast('.')
    for (extension in listOf("lrc", "LRC")) {
        checkCancelled()
        val url = track.url.newBuilder().setPathSegment(track.url.pathSegments.lastIndex, "$stem.$extension").build()
        client.request(url).use { response ->
            if (response.code == 404) return@use
            WebDavClient.requireSuccess(response)
            val bytes = response.body?.byteStream()?.readNBytes(MAX_LYRICS_BYTES + 1) ?: throw IOException("歌词响应为空")
            require(bytes.size <= MAX_LYRICS_BYTES) { "歌词文件超过 1 MB" }
            val text = decodeLyrics(bytes).trim('\uFEFF', '\u0000', ' ', '\r', '\n')
            if (text.isNotEmpty()) return text
        }
    }
    return null
}

internal fun decodeLyrics(bytes: ByteArray): String {
    val charset = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    return try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
    catch (failure: CharacterCodingException) {
        if (charset != Charsets.UTF_8 || !Charset.isSupported("GB18030")) throw failure
        Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}

private const val MAX_LYRICS_BYTES = 1024 * 1024
private fun ByteArray.text(start: Int, count: Int): String = if (size >= start + count) String(this, start, count, Charsets.ISO_8859_1) else ""
private fun ByteArray.uint32(offset: Int): Long = ByteBuffer.wrap(this, offset, 4).int.toLong() and 0xffffffffL
