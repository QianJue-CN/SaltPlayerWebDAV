package com.saltplayermod.webdav

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.file.Path

class RemoteTrackInfoTest {
    private val title = "歌曲标题"
    private val artist = "Aimer"
    private val album = "专辑名称"
    private val albumArtist = "专辑艺术家"
    private val lyrics = "[00:01.00]第一句歌词\n[00:02.00]第二句歌词"

    private fun withReader(action: (HostTagReader) -> Unit) {
        val archive = System.getProperty("spw.hostArchive")
        assumeTrue("需要宿主自带的标签解析器", !archive.isNullOrBlank())
        URLClassLoader(arrayOf(Path.of(archive).toUri().toURL()), javaClass.classLoader).use { action(HostTagReader(it)) }
    }

    private fun be(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun synchsafe(value: Int) = byteArrayOf((value ushr 21).toByte(), ((value ushr 14) and 127).toByte(), ((value ushr 7) and 127).toByte(), (value and 127).toByte())
    private fun text(value: String) = value.toByteArray(Charsets.ISO_8859_1)
    private fun frames(version: Int): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val body = if (id == "USLT") byteArrayOf(1) + text("eng") + byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0) + value.toByteArray(Charsets.UTF_16)
                else byteArrayOf(1) + value.toByteArray(Charsets.UTF_16)
            return text(id) + (if (version == 4) synchsafe(body.size) else be(body.size)) + byteArrayOf(0, 0) + body
        }
        val body = frame("TIT2", title) + frame("TPE1", artist) + frame("TALB", album) + frame("TPE2", albumArtist) + frame("USLT", lyrics)
        return text("ID3") + byteArrayOf(version.toByte(), 0, 0) + synchsafe(body.size) + body
    }

    private fun comments(): ByteArray {
        val values = listOf("TITLE=$title", "ARTIST=$artist", "ALBUM=$album", "ALBUMARTIST=$albumArtist", "LYRICS=$lyrics")
        var result = le(4) + text("test") + le(values.size)
        for (value in values) { val bytes = value.toByteArray(); result += le(bytes.size) + bytes }
        return result
    }

    private fun assertInfo(info: TrackInfo) {
        assertEquals(title, info.title)
        assertEquals(artist, info.artist)
        assertEquals(album, info.album)
        assertEquals(albumArtist, info.albumArtist)
        assertEquals(lyrics, info.lyrics)
    }

    private fun readFixture(reader: HostTagReader, name: String, data: ByteArray): TrackInfo {
        DavServer().use { server -> server.client().use { client ->
            server.files["/dav/music/$name"] = data
            val track = client.scan().single()
            val info = RemoteTrackInfo(client, reader).read(track)
            assertTrue(server.seen.filter { it.method == "GET" }.all { it.range != null && it.authorization != null })
            assertTrue(server.seen.none { it.method in listOf("PUT", "MOVE", "DELETE") })
            return info
        } }
    }

    @Test fun readsMp3Id3v23TagsIncludingEmbeddedLyrics() = withReader { reader ->
        assertInfo(readFixture(reader, "wrong-name.mp3", frames(3) + ByteArray(100_000)))
    }

    @Test fun readsMp3Id3v24TagsIncludingEmbeddedLyrics() = withReader { reader ->
        assertInfo(readFixture(reader, "wrong-name.mp3", frames(4) + ByteArray(100_000)))
    }

    @Test fun readsFlacVorbisCommentsWithoutReadingAudioOrLargePictures() = withReader { reader ->
        val comment = comments()
        val padding = 500_000
        val data = text("fLaC") + byteArrayOf(1, (padding ushr 16).toByte(), (padding ushr 8).toByte(), padding.toByte()) + ByteArray(padding) +
            byteArrayOf(0x84.toByte(), (comment.size ushr 16).toByte(), (comment.size ushr 8).toByte(), comment.size.toByte()) + comment + ByteArray(100_000)
        assertInfo(readFixture(reader, "wrong-name.flac", data))
    }

    private fun oggPage(packet: ByteArray, sequence: Int): ByteArray {
        val segments = ByteArray(packet.size / 255 + 1) { if (it < packet.size / 255) 255.toByte() else (packet.size % 255).toByte() }
        val header = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
            .put(text("OggS")).put(0).put(if (sequence == 0) 2 else 0).putLong(0).putInt(17).putInt(sequence).putInt(0).put(segments.size.toByte()).array()
        return header + segments + packet
    }

    @Test fun readsOpusAndVorbisCommentPackets() = withReader { reader ->
        val comment = comments()
        assertInfo(readFixture(reader, "song.opus", oggPage(text("OpusHead") + ByteArray(11), 0) + oggPage(text("OpusTags") + comment, 1)))
        assertInfo(readFixture(reader, "song.ogg", oggPage(byteArrayOf(1) + text("vorbis") + ByteArray(23), 0) + oggPage(byteArrayOf(3) + text("vorbis") + comment + byteArrayOf(1), 1)))
    }

    @Test fun readsM4aTagsWhenMoovFollowsAudioData() = withReader { reader ->
        fun box(type: String, payload: ByteArray) = be(payload.size + 8) + text(type) + payload
        fun field(key: String, value: String) = box(key, box("data", be(1) + be(0) + value.toByteArray()))
        val ilst = box("ilst", field("©nam", title) + field("©ART", artist) + field("©alb", album) + field("aART", albumArtist) + field("©lyr", lyrics))
        val moov = box("moov", box("udta", box("meta", be(0) + box("hdlr", be(0) + be(0) + text("mdir") + ByteArray(12)) + ilst)))
        val ftyp = box("ftyp", text("M4A ") + be(0) + text("M4A "))
        assertInfo(readFixture(reader, "song.m4a", ftyp + box("mdat", ByteArray(100_000)) + moov))
    }

    @Test fun supportsAlistCdnTagsWithDifferentEtagsWithoutLeakingCredentials() = withReader { reader ->
        DavServer().use { dav -> DavServer().use { cdn -> dav.client().use { client ->
            val audio = frames(4) + ByteArray(1000)
            dav.etagPerPath = true
            cdn.etagPerPath = true
            dav.files["/dav/music/song.mp3"] = audio
            cdn.files["/download/song.mp3"] = audio
            cdn.publicPaths += "/download/song.mp3"
            dav.redirects["/dav/music/song.mp3"] = cdn.address + "/download/song.mp3"
            assertInfo(RemoteTrackInfo(client, reader).read(client.scan().single()))
            assertTrue(cdn.seen.isNotEmpty())
            assertTrue(cdn.seen.all { it.authorization == null && it.range != null })
        } } }
    }

    @Test fun readsId3v1TitleArtistAndAlbumFromFileTail() = withReader { reader ->
        fun field(value: String) = text(value).copyOf(30)
        val tag = text("TAG") + field("Title") + field("Artist") + field("Album") + text("2026") + ByteArray(30) + byteArrayOf(0)
        val info = readFixture(reader, "song.mp3", ByteArray(1024) + tag)
        assertEquals("Title", info.title)
        assertEquals("Artist", info.artist)
        assertEquals("Album", info.album)
        assertEquals("", info.albumArtist)
    }

    @Test fun rejectsMismatchedRangeOffsetsInsteadOfParsingWrongBytes() = withReader { reader ->
        DavServer().use { server -> server.client().use { client ->
            server.files["/dav/music/song.mp3"] = frames(4) + ByteArray(1000)
            val track = client.scan().single()
            server.before = { exchange ->
                if (exchange.requestMethod == "GET") {
                    exchange.responseHeaders.set("Content-Range", "bytes 4-15/${track.size}")
                    server.respond(exchange, 206, ByteArray(12)); true
                } else false
            }
            assertThrows(Exception::class.java) { RemoteTrackInfo(client, reader).read(track) }
        } }
    }

    @Test fun fillsRealPlaybackFieldsAndRetainsFilenameFallback() {
        DavServer().use { server -> server.client().use { client ->
            server.put("/dav/music/Folder/file.mp3", "data")
            val track = client.scan().single()
            assertEquals(listOf("id", title, artist, album, albumArtist, "proxy-url"), TrackInfo(title, artist, album, albumArtist).queueFields("id", track, "proxy-url"))
            assertEquals(listOf("id", "file", "", "Folder", "", "proxy-url"), TrackInfo().queueFields("id", track, "proxy-url"))
        } }
    }

    @Test fun loadsAuthenticatedSameDirectoryLyricsWithSpecialCharacters() {
        DavServer().use { server -> server.client().use { client ->
            val name = "歌曲 + #%.mp3"
            server.put("/dav/music/专辑/$name", "audio")
            server.put("/dav/music/专辑/歌曲 + #%.lrc", lyrics)
            val track = client.scan().single()
            assertEquals(lyrics, readSidecarLyrics(client, track))
            assertTrue(server.seen.any { it.path == "/dav/music/专辑/歌曲 + #%.lrc" && it.authorization != null })
        } }
    }

    @Test fun supportsUppercaseLyricsAndByteOrderMarks() {
        DavServer().use { server -> server.client().use { client ->
            server.put("/dav/music/song.flac", "audio")
            val track = client.scan().single()
            for (bytes in listOf(("\uFEFF" + lyrics).toByteArray(), ("\uFEFF" + lyrics).toByteArray(Charsets.UTF_16LE), lyrics.toByteArray(Charsets.UTF_16))) {
                server.files["/dav/music/song.LRC"] = bytes
                assertEquals(lyrics, readSidecarLyrics(client, track))
            }
        } }
    }

    @Test fun decodesGb18030Lyrics() {
        assertEquals(lyrics, decodeLyrics(lyrics.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun sidecarOverridesEmbeddedLyricsAndMissingSidecarFallsBack() {
        DavServer().use { server -> server.client().use { client ->
            server.put("/dav/music/song.mp3", "audio")
            val track = client.scan().single()
            StreamProxy(client).use { proxy ->
                assertEquals("embedded", proxy.lyrics(track, "embedded"))
                server.put("/dav/music/song.lrc", lyrics)
                assertEquals(lyrics, proxy.lyrics(track, "embedded"))
                server.files["/dav/music/song.lrc"] = ByteArray(1024 * 1024 + 1) { 65 }
                assertEquals("embedded", proxy.lyrics(track, "embedded"))
                server.files.remove("/dav/music/song.lrc")
                assertNull(proxy.lyrics(track))
            }
        } }
    }

    @Test fun externalLyricsRemainAvailableForUnknownAudioFormats() {
        DavServer().use { server -> server.client().use { client ->
            server.put("/dav/music/song.wav", "audio")
            server.put("/dav/music/song.lrc", lyrics)
            assertEquals(lyrics, StreamProxy(client).use { it.lyrics(client.scan().single()) })
        } }
    }

    @Test fun malformedAndOversizedTagsDoNotDownloadEntireFile() = withReader { reader ->
        DavServer().use { server -> server.client().use { client ->
            server.files["/dav/music/song.mp3"] = text("ID3") + byteArrayOf(4, 0, 0) + synchsafe(32 * 1024 * 1024) + ByteArray(1000)
            assertThrows(Exception::class.java) { RemoteTrackInfo(client, reader).read(client.scan().single()) }
            assertEquals(listOf("bytes=0-11"), server.seen.filter { it.method == "GET" }.map { it.range })
        } }
    }

    @Test fun registeredExtensionDoesNotInterceptLocalMusicWhenPluginIsInactive() {
        assertNull(RemoteLyricsExtension().onBeforeLoadLyrics(PlaybackExtensionPoint.MediaItem("local", "", "", "", "C:/Music/song.mp3")))
        val index = javaClass.classLoader.getResourceAsStream("META-INF/extensions.idx")!!.bufferedReader().use { it.readText() }
        assertTrue(index.lines().contains(RemoteLyricsExtension::class.java.name))
    }
}
