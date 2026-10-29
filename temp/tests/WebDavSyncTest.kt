package com.saltplayermod.webdav

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class WebDavSyncTest {
    private fun withLibrary(action: (DavServer, WebDavClient, Path, Path) -> Unit) {
        val temporary = Files.createTempDirectory("webdav-test-")
        try {
            DavServer().use { server -> server.client().use { client ->
                val local = Files.createDirectory(temporary.resolve("music"))
                val state = Files.createDirectory(temporary.resolve("data"))
                action(server, client, local, state)
            } }
        } finally { Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun scansNestedUnicodeAndAlistAddresses() {
        DavServer().use { server -> server.client().use { client ->
            server.put("/dav/music/歌手/专辑/曲目 + #%.flac", "audio")
            server.put("/dav/music/notes.txt", "ignore")
            server.put("/dav/music/.saltplayer-webdav-backups/old.mp3", "backup")
            val tracks = client.scan()
            assertEquals(listOf("歌手/专辑/曲目 + #%.flac"), tracks.map { it.path })
            client.request(tracks.single().url).use { assertEquals("audio", it.body!!.string()) }
            assertEquals(server.address + "/dav/music/", davRoot(server.address, "music", true).toString())
            assertEquals(server.address + "/dav/music/", davRoot(server.address + "/dav/", "music", true).toString())
            assertEquals(server.address + "/prefix/dav/music/", davRoot(server.address + "/prefix/", "music", true).toString())
        } }
    }

    @Test fun transfersNewFilesInBothDirectionsAndSkipsUnchangedFiles() = withLibrary { server, client, local, state ->
        server.put("/dav/music/remote.mp3", "remote-content")
        Files.createDirectories(local.resolve("新专辑"))
        Files.writeString(local.resolve("新专辑/local.flac"), "local-content")
        val engine = SyncEngine(client, state)
        val first = engine.sync(local)
        assertEquals(1, first.uploaded)
        assertEquals(1, first.downloaded)
        assertTrue(first.failures.toString(), first.failures.isEmpty())
        assertEquals("remote-content", Files.readString(local.resolve("remote.mp3")))
        assertEquals("local-content", server.content("/dav/music/新专辑/local.flac"))
        val second = engine.sync(local)
        assertEquals(0, second.uploaded)
        assertEquals(0, second.downloaded)
        assertEquals(2, second.unchanged)
        assertTrue(second.conflicts.isEmpty())
        assertTrue(server.seen.filter { it.method == "DELETE" }.all { ".saltplayer-webdav-" in it.path && it.path.endsWith(".part") })
    }

    @Test fun sendsLocalChangesWithRemoteBackupAndPullsRemoteChanges() = withLibrary { server, client, local, state ->
        server.ignoreIfMatch = true // AList 部分后端不执行 If-Match，仍需保证旧版本可恢复。
        server.put("/dav/music/song.mp3", "original")
        val engine = SyncEngine(client, state)
        assertEquals(1, engine.sync(local).downloaded)
        Files.writeString(local.resolve("song.mp3"), "local-updated-content")
        val pushed = engine.sync(local)
        assertTrue(pushed.failures.toString(), pushed.failures.isEmpty())
        assertEquals(1, pushed.uploaded)
        assertEquals("local-updated-content", server.content("/dav/music/song.mp3"))
        assertTrue(server.files.any { (path, value) -> WebDavClient.BACKUP_DIRECTORY in path && value.toString(Charsets.UTF_8) == "original" })
        assertEquals(1, client.scan().size)
        server.put("/dav/music/song.mp3", "remote-updated-content-after-upload")
        val pulled = engine.sync(local)
        assertEquals(1, pulled.downloaded)
        assertEquals("remote-updated-content-after-upload", Files.readString(local.resolve("song.mp3")))
    }

    @Test fun preservesBothVersionsWhenBothSidesChange() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "base")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        Files.writeString(local.resolve("song.mp3"), "my-local-version")
        server.put("/dav/music/song.mp3", "their-remote-version")
        val result = engine.sync(local)
        assertEquals(1, result.conflicts.size)
        assertEquals(0, result.uploaded)
        assertEquals(0, result.downloaded)
        assertEquals("my-local-version", Files.readString(local.resolve("song.mp3")))
        assertEquals("their-remote-version", server.content("/dav/music/song.mp3"))
        assertEquals(1, engine.sync(local).conflicts.size)
    }

    @Test fun comparesContentsBeforeAdoptingExistingSameNamedFiles() = withLibrary { server, client, local, state ->
        server.put("/dav/music/same.mp3", "identical")
        server.put("/dav/music/conflict.mp3", "remote")
        Files.writeString(local.resolve("same.mp3"), "identical")
        Files.writeString(local.resolve("conflict.mp3"), "local!")
        val result = SyncEngine(client, state).sync(local)
        assertEquals(1, result.unchanged)
        assertEquals(1, result.conflicts.size)
        assertEquals("local!", Files.readString(local.resolve("conflict.mp3")))
        assertEquals("remote", server.content("/dav/music/conflict.mp3"))
        assertTrue(server.seen.none { it.method == "PUT" })
    }

    @Test fun restoresMissingCopiesInsteadOfPropagatingDeletions() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "base")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        server.files.remove("/dav/music/song.mp3")
        assertEquals(1, engine.sync(local).uploaded)
        Files.delete(local.resolve("song.mp3"))
        assertEquals(1, engine.sync(local).downloaded)
        assertEquals("base", Files.readString(local.resolve("song.mp3")))
    }

    @Test fun usesContentHashWhenServerHasNoValidators() = withLibrary { server, client, local, state ->
        server.omitValidators = true
        server.put("/dav/music/song.mp3", "first")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        server.put("/dav/music/song.mp3", "other")
        assertEquals(1, engine.sync(local).downloaded)
        Files.writeString(local.resolve("song.mp3"), "third")
        Files.setLastModifiedTime(local.resolve("song.mp3"), FileTime.from(Instant.now().plusSeconds(20)))
        val result = engine.sync(local)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(1, result.uploaded)
        assertEquals("third", server.content("/dav/music/song.mp3"))
    }

    @Test fun detectsLocalTagEditsThatPreserveSizeAndTimestamp() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "aaaa")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        val file = local.resolve("song.mp3")
        val timestamp = Files.getLastModifiedTime(file)
        Files.writeString(file, "bbbb")
        Files.setLastModifiedTime(file, timestamp)
        val result = engine.sync(local)
        assertEquals(1, result.uploaded)
        assertEquals("bbbb", server.content("/dav/music/song.mp3"))
    }

    @Test fun rejectsAuthenticationAndPerResourcePermissionFailures() {
        DavServer().use { server ->
            WebDavClient(server.root, "user", "wrong").use { client ->
                assertThrows(IOException::class.java) { client.scan() }
            }
            server.listingOverride = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/music/</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>"""
            server.client().use { client -> assertThrows(IOException::class.java) { client.scan() } }
        }
    }

    @Test fun cancellationBeforeSyncMakesNoNetworkChanges() = withLibrary { server, client, local, state ->
        Files.writeString(local.resolve("song.mp3"), "local-only")
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { SyncEngine(client, state).sync(local) }
        } finally { Thread.interrupted() }
        assertTrue(server.files.isEmpty())
        assertTrue(server.seen.isEmpty())
    }

    @Test fun failedDownloadKeepsPreviousFileAndRemovesTemporaryFiles() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "base")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        server.put("/dav/music/song.mp3", "new-server-version")
        server.listedExtraSize = 12
        val result = engine.sync(local)
        assertEquals(1, result.failures.size)
        assertEquals("base", Files.readString(local.resolve("song.mp3")))
        Files.list(local).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".part") }) }
    }

    @Test fun failedPublishRestoresOriginalRemoteFile() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "old-remote")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        Files.writeString(local.resolve("song.mp3"), "local-new-content")
        server.before = { exchange ->
            if (exchange.requestMethod == "MOVE" && exchange.requestURI.path.endsWith(".part")) {
                server.respond(exchange, 500); true
            } else false
        }
        val result = engine.sync(local)
        assertEquals(1, result.failures.size)
        assertEquals("old-remote", server.content("/dav/music/song.mp3"))
        assertEquals("local-new-content", Files.readString(local.resolve("song.mp3")))
        assertTrue(server.files.keys.none { it.endsWith(".part") })
    }

    @Test fun concurrentRemoteModificationIsReportedWithoutOverwrite() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "base")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        Files.writeString(local.resolve("song.mp3"), "local-modification")
        server.before = { exchange ->
            if (exchange.requestMethod == "PUT") server.put("/dav/music/song.mp3", "concurrent-remote-change")
            false
        }
        val result = engine.sync(local)
        assertEquals(1, result.conflicts.size)
        assertEquals("concurrent-remote-change", server.content("/dav/music/song.mp3"))
        assertEquals("local-modification", Files.readString(local.resolve("song.mp3")))
    }

    @Test fun supportsServersWhoseEtagsChangeAfterMove() = withLibrary { server, client, local, state ->
        server.etagPerPath = true
        server.put("/dav/music/song.mp3", "base")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        Files.writeString(local.resolve("song.mp3"), "updated-local-version")
        val result = engine.sync(local)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertTrue(result.conflicts.toString(), result.conflicts.isEmpty())
        assertEquals(1, result.uploaded)
        assertEquals("updated-local-version", server.content("/dav/music/song.mp3"))
    }

    @Test fun changingAccountsDoesNotReusePreviousAccountsBaseline() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "first-account")
        SyncEngine(client, state).sync(local)
        server.authUser = "other"
        server.put("/dav/music/song.mp3", "other-account")
        WebDavClient(server.root, "other", "pass").use { other ->
            val result = SyncEngine(other, state).sync(local)
            assertEquals(1, result.conflicts.size)
            assertEquals("first-account", Files.readString(local.resolve("song.mp3")))
            assertEquals("other-account", server.content("/dav/music/song.mp3"))
        }
    }

    @Test fun keepsConcurrentChangesEvenWhenServerIgnoresIfMatch() = withLibrary { server, client, local, state ->
        server.ignoreIfMatch = true
        server.put("/dav/music/song.mp3", "base")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        Files.writeString(local.resolve("song.mp3"), "my-local-update")
        server.before = { exchange ->
            if (exchange.requestMethod == "MOVE" && exchange.requestURI.path == "/dav/music/song.mp3") {
                server.put("/dav/music/song.mp3", "changed-during-move")
            }
            false
        }
        val result = engine.sync(local)
        assertEquals(1, result.conflicts.size)
        assertEquals("changed-during-move", server.content("/dav/music/song.mp3"))
        assertEquals("my-local-update", Files.readString(local.resolve("song.mp3")))
    }

    @Test fun preservesConcurrentDestinationAndBackupWhenPublishCollides() = withLibrary { server, client, local, state ->
        server.put("/dav/music/song.mp3", "original")
        val engine = SyncEngine(client, state)
        engine.sync(local)
        Files.writeString(local.resolve("song.mp3"), "local-update")
        server.before = { exchange ->
            if (exchange.requestMethod == "MOVE" && exchange.requestURI.path.endsWith(".part")) {
                server.put("/dav/music/song.mp3", "concurrently-created")
            }
            false
        }
        val result = engine.sync(local)
        assertEquals(1, result.conflicts.size)
        assertEquals("concurrently-created", server.content("/dav/music/song.mp3"))
        assertTrue(server.files.any { (path, bytes) -> WebDavClient.BACKUP_DIRECTORY in path && bytes.toString(Charsets.UTF_8) == "original" })
        assertEquals("local-update", Files.readString(local.resolve("song.mp3")))
    }

    @Test fun rejectsEscapingRemotePathsAndExternalXmlEntities() {
        DavServer().use { server -> server.client().use { client ->
            server.listingOverride = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/outside/secret.mp3</d:href></d:response></d:multistatus>"""
            assertThrows(IOException::class.java) { client.scan() }
            server.listingOverride = """<!DOCTYPE x [<!ENTITY a SYSTEM "file:///does-not-exist">]><d:multistatus xmlns:d="DAV:">&a;</d:multistatus>"""
            assertThrows(Exception::class.java) { client.scan() }
        } }
    }

    @Test fun rejectsUnsafeLocalNames() {
        val root = Files.createTempDirectory("webdav-path-test-").toRealPath()
        try {
            for (name in listOf("../outside.mp3", "/root.mp3", "CON.mp3", "file.mp3:stream", "dir/../escape.flac", "tail./song.mp3")) {
                assertThrows(IOException::class.java) { SyncEngine.safeDestination(root, name) }
            }
            assertEquals(root.resolve("音乐 + % #.flac"), SyncEngine.safeDestination(root, "音乐 + % #.flac"))
        } finally { Files.delete(root) }
    }

    @Test fun handlesAlistRedirectsWithoutLeakingCredentialsAndProxiesRanges() {
        DavServer().use { dav -> DavServer().use { cdn -> dav.client().use { client ->
            dav.put("/dav/music/song.mp3", "0123456789")
            cdn.put("/cloud/song.mp3", "0123456789")
            cdn.publicPaths += "/cloud/song.mp3"
            dav.redirects["/dav/music/song.mp3"] = cdn.address + "/cloud/song.mp3"
            val track = client.scan().single()
            StreamProxy(client).use { proxy ->
                proxy.start(5000, true)
                proxy.checkPlayable(track)
                val url = proxy.urlFor(track)
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.setRequestProperty("Range", "bytes=2-5")
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 2-5/10", connection.getHeaderField("Content-Range"))
                assertEquals("2345", connection.inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) })
                connection.disconnect()
                val head = URI(url).toURL().openConnection() as HttpURLConnection
                head.requestMethod = "HEAD"
                assertEquals(200, head.responseCode)
                assertEquals(10, head.contentLength)
                head.disconnect()
                val invalid = URI(url.replace("/stream/", "/stream/unknown-")).toURL().openConnection() as HttpURLConnection
                assertEquals(404, invalid.responseCode)
                invalid.disconnect()
            }
            assertTrue(cdn.seen.isNotEmpty())
            assertTrue(cdn.seen.all { it.authorization == null })
        } } }
    }
}
