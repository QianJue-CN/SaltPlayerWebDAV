@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.saltplayermod.webdav

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class MemoryConfig(private val path: Path, initial: Map<String, Any> = emptyMap()) : ConfigHelper {
    val values = ConcurrentHashMap(initial)
    val saves = CopyOnWriteArrayList<Map<String, Any>>()
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, defaultValue: T): T = values[key] as? T ?: defaultValue
    override fun set(key: String, value: Any) { values[key] = value }
    override fun save(): Boolean { saves += values.toMap(); return true }
    override fun reload() = true
    override fun getConfigPath() = path
}

class LibraryControllerTest {
    private fun withController(play: ((List<RemoteTrack>, StreamProxy) -> Unit)? = null,
        action: (DavServer, LibraryController, MemoryConfig, Path) -> Unit) {
        val root = Files.createTempDirectory("webdav-controller-")
        try {
            DavServer().use { server ->
                val config = MemoryConfig(root.resolve("config.json"), mapOf(
                    "server.url" to server.address + "/dav/", "server.folder" to "music", "server.username" to "user",
                    "server.alist" to false, "sync.directory" to root.resolve("music").toString(),
                ))
                LibraryController(config, play, "测试中未启用播放适配").use { controller ->
                    controller.editPassword("pass")
                    action(server, controller, config, root)
                }
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun awaitIdle(controller: LibraryController) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (controller.state.value.busy && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("后台操作未结束：${controller.state.value}", controller.state.value.busy)
    }

    @Test fun reusesExistingSettingsAndKeepsPasswordOutOfSavedConfig() = withController { server, controller, config, _ ->
        server.put("/dav/music/song.mp3", "music")
        assertEquals(server.address + "/dav/", controller.state.value.settings.address)
        assertEquals("music", controller.state.value.settings.folder)
        controller.connect()
        awaitIdle(controller)
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.connected)
        assertEquals(listOf("song.mp3"), controller.state.value.tracks.map { it.path })
        assertTrue(config.saves.isNotEmpty())
        assertTrue(config.values.keys.none { it.contains("password", true) })
        assertTrue(config.values.values.none { it == "pass" })
        assertEquals("pass", controller.password.value)
        controller.close()
        assertTrue(controller.state.value.closed)
        assertEquals("", controller.password.value)
    }

    @Test fun reportsAuthenticationFailureInPageState() = withController { _, controller, _, _ ->
        controller.editPassword("incorrect")
        controller.connect()
        awaitIdle(controller)
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.error.orEmpty().contains("认证失败"))
        assertFalse(controller.state.value.busy)
    }

    @Test fun synchronizesBothDirectionsAndRefreshesTheInlineTrackList() = withController { server, controller, _, root ->
        server.put("/dav/music/remote.mp3", "remote-music")
        val local = Files.createDirectories(root.resolve("music"))
        Files.writeString(local.resolve("local.flac"), "local-music")
        controller.connect()
        awaitIdle(controller)
        controller.sync()
        awaitIdle(controller)
        val ui = controller.state.value
        assertNull(ui.error)
        assertEquals(1, ui.result?.uploaded)
        assertEquals(1, ui.result?.downloaded)
        assertEquals(listOf("local.flac", "remote.mp3"), ui.tracks.map { it.path })
        assertEquals("remote-music", Files.readString(local.resolve("remote.mp3")))
        assertEquals("local-music", server.content("/dav/music/local.flac"))
    }

    @Test fun expandsConflictsInlineWithoutOverwritingEitherFile() = withController { server, controller, _, root ->
        server.put("/dav/music/song.mp3", "remote")
        val local = Files.createDirectories(root.resolve("music"))
        Files.writeString(local.resolve("song.mp3"), "local!")
        controller.connect()
        awaitIdle(controller)
        controller.sync()
        awaitIdle(controller)
        assertEquals(1, controller.state.value.result?.conflicts?.size)
        assertTrue(controller.state.value.detailsExpanded)
        controller.toggleDetails()
        assertFalse(controller.state.value.detailsExpanded)
        assertEquals("remote", server.content("/dav/music/song.mp3"))
        assertEquals("local!", Files.readString(local.resolve("song.mp3")))
    }

    @Test fun playsSelectionThroughExistingConnectionAndClosesItsProxy() {
        val played = CopyOnWriteArrayList<String>()
        var streamUrl: String? = null
        withController(play = { tracks, proxy ->
            played.addAll(tracks.map { it.path })
            proxy.checkPlayable(tracks.first())
            streamUrl = proxy.urlFor(tracks.first())
        }) { server, controller, _, _ ->
            server.put("/dav/music/b.mp3", "bbbb")
            server.put("/dav/music/a.mp3", "aaaa")
            controller.connect()
            awaitIdle(controller)
            controller.toggleSelection("b.mp3")
            controller.toggleSelection("a.mp3")
            controller.editQuery("a.mp3")
            controller.play()
            awaitIdle(controller)
            assertNull(controller.state.value.error)
            assertEquals(listOf("a.mp3", "b.mp3"), played.toList())
            assertEquals("a.mp3", controller.state.value.query)
            controller.close()
            val connection = URI(streamUrl!!).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            try { assertThrows(Exception::class.java) { connection.inputStream.use { it.read() } } }
            finally { connection.disconnect() }
        }
    }

    @Test fun editingConnectionOrPasswordRequiresReconnect() = withController { server, controller, _, _ ->
        server.put("/dav/music/song.mp3", "music")
        controller.connect()
        awaitIdle(controller)
        assertTrue(controller.state.value.connected)
        controller.editPassword("new-password")
        assertFalse(controller.state.value.connected)
        controller.editSettings { it.copy(directory = it.directory + "-another") }
        assertFalse(controller.state.value.connected)
        controller.sync()
        assertTrue(controller.state.value.error.orEmpty().contains("连接信息已修改"))
        assertTrue(server.seen.none { it.method == "PUT" })
    }

    @Test fun productionCodeHasNoStandaloneWindowOrModalDialogReferences() {
        val root = Path.of("build/classes/kotlin/main")
        assertTrue(Files.isDirectory(root))
        val forbidden = listOf("javax/swing/", "java/awt/Window", "androidx/compose/ui/window/", "JFileChooser", "JOptionPane")
        Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.forEach { file ->
                val constants = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
                for (name in forbidden) assertFalse("$file contains $name", name in constants)
            }
        }
    }
}
