package com.saltplayermod.webdav

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class HostContractTest {
    @Test fun refusesUnknownHostVersions() {
        assertThrows(IllegalArgumentException::class.java) { HostBindings(javaClass.classLoader, "1.19.4") }
    }

    @Test fun checksNativeSettingsAndThemeContracts() {
        val archive = System.getProperty("spw.hostArchive")
        assumeTrue("指定 -PhostArchive 可检查播放器内嵌设置接口", !archive.isNullOrBlank())
        URLClassLoader(arrayOf(Path.of(archive).toUri().toURL()), javaClass.classLoader).use { loader ->
            val bindings = NativeUiBindings(loader, "1.19.3")
            assertEquals(4, bindings.contentMethod.parameterCount)
            assertEquals("androidx.compose.ui.Ь", bindings.contentMethod.parameterTypes[0].name)
            val resolver = loader.loadClass("com.xuncorp.voxzen.workshop.hooks.Ԫ")
            val instance = resolver.getField("Ϳ").get(null)
            val resolve = resolver.methods.single { it.parameterTypes.toList() == listOf(
                String::class.java, String::class.java, String::class.java, List::class.java,
            ) }
            val resolved = resolve.invoke(instance, bindings.contentMethod.declaringClass.name, bindings.contentMethod.name,
                bindings.callOwner, bindings.contentMethod.parameterTypes.take(2).map { it.name })
            assertNotNull(resolved)
        }
    }

    @Test fun checksInstalledHostSignaturesWithoutStartingAudioOrChangingLibrary() {
        val archive = System.getProperty("spw.hostArchive")
        assumeTrue("指定 -PhostArchive 可检查安装的播放器接口", !archive.isNullOrBlank())
        val path = Path.of(archive)
        assertTrue(Files.isRegularFile(path))
        URLClassLoader(arrayOf(path.toUri().toURL()), javaClass.classLoader).use { loader ->
            val bindings = HostBindings(loader, "1.19.3")
            assertEquals(6, bindings.interceptorMethod.parameterCount)
            assertTrue(bindings.interceptorClass.isInterface)
            val item = bindings.itemClass.getConstructor(*Array(6) { String::class.java })
                .newInstance("own-id", "title", "artist", "album", "albumArtist", "http://127.0.0.1/test")
            assertEquals("our-stream", bindings.mappedUrl(item, mapOf("own-id" to "our-stream")))
            assertNull(bindings.mappedUrl(item, emptyMap()))
        }
    }
}
