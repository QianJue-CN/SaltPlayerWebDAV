@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.saltplayermod.webdav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class LibraryPanelRenderTest {
    @Test fun rendersDesktopLightDarkAndNarrowLayoutsWithoutOpeningWindows() {
        val output = Path.of("build/reports/ui-preview")
        Files.createDirectories(output)
        val temporary = Files.createTempDirectory("webdav-ui-preview-")
        try {
            val config = MemoryConfig(temporary.resolve("config.json"), mapOf(
                "server.url" to "https://music.example.com", "server.folder" to "/音乐",
                "server.username" to "music", "server.alist" to true, "sync.directory" to "C:/Music/WebDAV",
            ))
            LibraryController(config, { _, _ -> }, "").use { controller ->
                for ((name, width, height) in listOf(Triple("desktop-dark", 820, 1180), Triple("desktop-light", 820, 1180), Triple("narrow-dark", 460, 1300))) {
                    val dark = name.endsWith("dark")
                    val palette = palette(dark)
                    val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
                        Box(Modifier.fillMaxSize().background(palette.background).verticalScroll(rememberScrollState())) {
                            LibraryPanel(controller, PaddingValues(0.dp), palette)
                        }
                    }
                    try {
                        scene.render(0).close()
                        val image = scene.render(16_000_000)
                        try {
                            val data = image.encodeToData() ?: error("PNG 编码失败")
                            try { Files.write(output.resolve("$name.png"), data.bytes) } finally { data.close() }
                        } finally { image.close() }
                    } finally { scene.close() }
                    val rendered = ImageIO.read(output.resolve("$name.png").toFile())
                    assertEquals(width, rendered.width)
                    assertEquals(height, rendered.height)
                    assertTrue(Files.size(output.resolve("$name.png")) > 10_000)
                }
            }
        } finally { Files.walk(temporary).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun palette(dark: Boolean) = SettingsPalette(
        highlight = Color(0xff448eff), text = if (dark) Color(0xffebeef1) else Color(0xff1e1715),
        subText = if (dark) Color(0xffb9bdc1) else Color(0xff737373),
        background = if (dark) Color(0xff242424) else Color(0xfff3f3f3),
        card = if (dark) Color(0x08ffffff) else Color(0x80ffffff),
        stroke = if (dark) Color(0xff3d3d3d) else Color(0xffdddddd), onHighlight = Color.White,
        success = Color(0xff50b974), warning = Color(0xffb9862c), error = Color(0xffe36b68),
        main = TextStyle(fontSize = 14.sp), sub = TextStyle(fontSize = 12.sp),
        small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp), padding = 16.dp,
    )
}
