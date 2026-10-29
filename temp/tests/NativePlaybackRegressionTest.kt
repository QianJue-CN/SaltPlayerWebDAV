package com.saltplayermod.webdav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.internal.Ref

class NativePlaybackRegressionTest {
    private fun withHost(action: (ClassLoader, HostBindings) -> Unit) {
        val archive = System.getProperty("spw.hostArchive")
        assumeTrue("需要 -PhostArchive 运行真实宿主播放协程回归测试", !archive.isNullOrBlank())
        URLClassLoader(arrayOf(Path.of(archive).toUri().toURL()), javaClass.classLoader).use { loader ->
            action(loader, HostBindings(loader, "1.19.3"))
        }
    }

    private fun item(bindings: HostBindings, id: String = "webdav-test"): Any =
        bindings.itemClass.getConstructor(*Array(6) { String::class.java })
            .newInstance(id, "title", "", "", "", "http://127.0.0.1/test.mp3")

    private data class Outcome(val result: Result<*>, val handle: Int, val fallbacks: Int, val freed: List<Int>)

    private fun runHostCoroutine(loader: ClassLoader, media: Any, interceptors: List<Any>,
        fallback: Any? = 42, cancelled: Boolean = false): Outcome {
        val backendType = loader.loadClass("androidx.compose.ui.ܕ")
        var fallbacks = 0
        val freed = CopyOnWriteArrayList<Int>()
        val backend = Proxy.newProxyInstance(loader, arrayOf(backendType)) { _, method, args ->
            when {
                method.parameterCount == 5 -> { fallbacks++; fallback }
                method.returnType == Boolean::class.javaPrimitiveType -> { freed += args[0] as Int; true }
                else -> error("Unexpected backend call: $method")
            }
        }
        val context = loader.loadClass("com.xuncorp.pisces.PlatformContext").getConstructor().newInstance()
        val type = loader.loadClass("androidx.compose.ui.ࡂ\$Ϳ\$Ԩ")
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val handle = Ref.IntRef()
        val continuation = object : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) { error("Expected synchronous interceptor result") }
        }
        val coroutine = constructor.newInstance(interceptors, context, media, 0L, 0L, 123, backend, handle, continuation)
        val job = SupervisorJob()
        if (cancelled) job.cancel()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        try {
            val invoke = type.getDeclaredMethod("invoke", Any::class.java, Any::class.java).apply { isAccessible = true }
            val result = invoke.invoke(coroutine, scope, continuation) as Result<*>
            (result.getOrNull() as? AutoCloseable)?.close()
            return Outcome(result, handle.element, fallbacks, freed.toList())
        } finally { job.cancel() }
    }

    @Test fun reproducesReportedNullHandleCrashWithBrokenBackendReturn() = withHost { loader, bindings ->
        val failure = assertThrows(InvocationTargetException::class.java) {
            runHostCoroutine(loader, item(bindings), emptyList(), fallback = null)
        }
        assertTrue(failure.targetException is NullPointerException)
        assertTrue(failure.targetException.stackTrace.any { it.className == "androidx.compose.ui.ࡂ\$Ϳ\$Ԩ" && it.methodName == "invokeSuspend" })
    }

    @Test fun nativeInterceptorDeliversBoxedSuccessToActualHostCoroutine() = withHost { loader, bindings ->
        val media = item(bindings)
        NativeStreamInterceptor(bindings.interceptorClass, { if (it === media) "stream" else null }, { url, flags ->
            assertEquals("stream", url)
            assertEquals(123, flags)
            Result.success(713)
        }).use { interceptor ->
            val outcome = runHostCoroutine(loader, media, listOf(interceptor.instance))
            assertTrue(outcome.result.toString(), outcome.result.isSuccess)
            assertEquals(713, outcome.handle)
            assertEquals(0, outcome.fallbacks)
            assertEquals(listOf(713), outcome.freed)
        }
    }

    @Test fun decoderFailureRemainsFailureInsteadOfNullHandle() = withHost { loader, bindings ->
        val media = item(bindings)
        val error = IOException("模拟远端断流")
        NativeStreamInterceptor(bindings.interceptorClass, { "stream" }, { _, _ -> Result.failure(error) }).use { interceptor ->
            val outcome = runHostCoroutine(loader, media, listOf(interceptor.instance))
            assertSame(error, outcome.result.exceptionOrNull())
            assertEquals(0, outcome.handle)
            assertEquals(0, outcome.fallbacks)
            assertTrue(outcome.freed.isEmpty())
        }
    }

    @Test fun reflectedExceptionsBecomeHostResultFailures() = withHost { loader, bindings ->
        val error = IOException("模拟解码接口异常")
        NativeStreamInterceptor(bindings.interceptorClass, { "stream" }, { _, _ -> throw InvocationTargetException(error) }).use { interceptor ->
            assertSame(error, runHostCoroutine(loader, item(bindings), listOf(interceptor.instance)).result.exceptionOrNull())
        }
    }

    @Test fun localMusicFallsThroughAndClosedInterceptorDoesNotOpenStreams() = withHost { loader, bindings ->
        var opens = 0
        val remote = item(bindings)
        NativeStreamInterceptor(bindings.interceptorClass, { if (it === remote) "stream" else null }, { _, _ -> opens++; Result.success(713) }).use { interceptor ->
            val local = runHostCoroutine(loader, item(bindings, "local-track"), listOf(interceptor.instance))
            assertEquals(42, local.handle)
            assertEquals(1, local.fallbacks)
            interceptor.close()
            val closed = runHostCoroutine(loader, remote, listOf(interceptor.instance))
            assertEquals(1, closed.fallbacks)
            assertEquals(0, opens)
        }
    }

    @Test fun cancelledHostJobFreesCreatedStream() = withHost { loader, bindings ->
        NativeStreamInterceptor(bindings.interceptorClass, { "stream" }, { _, _ -> Result.success(713) }).use { interceptor ->
            val outcome = runHostCoroutine(loader, item(bindings), listOf(interceptor.instance), cancelled = true)
            assertTrue(outcome.result.isFailure)
            assertEquals(0, outcome.handle)
            assertEquals(listOf(713), outcome.freed)
        }
    }

    @Test fun streamsRealProxyBytesThroughHostRangeCallbacksIntoHostCoroutine() = withHost { loader, bindings ->
        DavServer().use { dav -> dav.client().use { client ->
            dav.put("/dav/music/song.mp3", "0123456789abcdef")
            val track = client.scan().single()
            StreamProxy(client).use { proxy ->
                proxy.start(5000, true)
                val runtimeType = loader.loadClass("com.xuncorp.voxzen.cast.BassFileStreamRuntime")
                val pointer = loader.loadClass("com.sun.jna.Pointer")
                val memoryType = loader.loadClass("com.sun.jna.Memory")
                val streamType = loader.loadClass("com.xuncorp.voxzen.cast.HttpRangeBassStream")
                val ownerType = loader.loadClass("com.xuncorp.voxzen.cast.HttpRangeStreamOwner")
                val owner = ownerType.enumConstants.single { (it as Enum<*>).name == "Unspecified" }
                val stream = streamType.getField("INSTANCE").get(null)
                val create = streamType.methods.single { it.name.startsWith("create-") && it.parameterTypes.toList() ==
                    listOf(String::class.java, Int::class.javaPrimitiveType, runtimeType, ownerType) }
                var callbacks: Any? = null
                var readBytes: ByteArray? = null
                val createAudio: (Int, Any) -> Int = { flags, procs ->
                    assertEquals(123, flags)
                    callbacks = procs
                    fun callback(name: String) = procs.javaClass.getField(name).let { it.type to it.get(procs) }
                    val (lengthType, length) = callback("length")
                    assertEquals(16L, lengthType.getMethod("invoke", pointer).invoke(length, null))
                    val (seekType, seek) = callback("seek")
                    assertEquals(true, seekType.getMethod("invoke", Long::class.javaPrimitiveType, pointer).invoke(seek, 4L, null))
                    val memory = memoryType.getConstructor(Long::class.javaPrimitiveType).newInstance(4L)
                    try {
                        val (readType, read) = callback("read")
                        assertEquals(4, readType.getMethod("invoke", pointer, Int::class.javaPrimitiveType, pointer).invoke(read, memory, 4, null))
                        readBytes = pointer.getMethod("getByteArray", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(memory, 0L, 4) as ByteArray
                    } finally { (memory as AutoCloseable).close() }
                    713
                }
                val free: (Int) -> Boolean = { true }
                val errorCode: () -> Int = { 41 }
                val runtime = runtimeType.constructors.single().newInstance(createAudio, free, errorCode)
                try {
                    NativeStreamInterceptor(bindings.interceptorClass, { proxy.urlFor(track) }, { url, flags ->
                        decodeHostStreamResult(create.invoke(stream, url, flags, runtime, owner))
                    }).use { interceptor ->
                        val outcome = runHostCoroutine(loader, item(bindings), listOf(interceptor.instance))
                        assertTrue(outcome.result.toString(), outcome.result.isSuccess)
                        assertEquals(713, outcome.handle)
                        assertArrayEquals("4567".toByteArray(), readBytes)
                        assertEquals(0, outcome.fallbacks)
                    }
                } finally {
                    callbacks?.let { procs ->
                        val close = procs.javaClass.getField("close")
                        close.type.getMethod("invoke", pointer).invoke(close.get(procs), null)
                    }
                }
            }
        } }
    }

    @Test fun actualHostRangeFailureReachesCoroutineWithoutNullUnboxing() = withHost { loader, bindings ->
        DavServer().use { dav -> dav.client().use { client ->
            dav.put("/dav/music/song.mp3", "0123456789abcdef")
            val track = client.scan().single()
            StreamProxy(client).use { proxy ->
                proxy.start(5000, true)
                val runtimeType = loader.loadClass("com.xuncorp.voxzen.cast.BassFileStreamRuntime")
                val streamType = loader.loadClass("com.xuncorp.voxzen.cast.HttpRangeBassStream")
                val ownerType = loader.loadClass("com.xuncorp.voxzen.cast.HttpRangeStreamOwner")
                val owner = ownerType.enumConstants.single { (it as Enum<*>).name == "Unspecified" }
                val create = streamType.methods.single { it.name.startsWith("create-") && it.parameterTypes.toList() ==
                    listOf(String::class.java, Int::class.javaPrimitiveType, runtimeType, ownerType) }
                val createAudio: (Int, Any) -> Int = { _, _ -> 0 }
                val free: (Int) -> Boolean = { true }
                val errorCode: () -> Int = { 41 }
                val runtime = runtimeType.constructors.single().newInstance(createAudio, free, errorCode)
                NativeStreamInterceptor(bindings.interceptorClass, { proxy.urlFor(track) }, { url, flags ->
                    decodeHostStreamResult(create.invoke(streamType.getField("INSTANCE").get(null), url, flags, runtime, owner))
                }).use { interceptor ->
                    val outcome = runHostCoroutine(loader, item(bindings), listOf(interceptor.instance))
                    assertTrue(outcome.result.isFailure)
                    assertTrue(outcome.result.exceptionOrNull()?.message.orEmpty().contains("41"))
                    assertEquals(0, outcome.fallbacks)
                }
            }
        } }
    }

    @Test fun normalizesUnboxedHostResultsAndRejectsInvalidHandles() {
        assertEquals(713, decodeHostStreamResult(713).getOrThrow())
        assertEquals(-1, decodeHostStreamResult(-1).getOrThrow()) // BASS handles are unsigned 32-bit values.
        for (invalid in listOf(null, 0, "713", 713L)) assertTrue(decodeHostStreamResult(invalid).isFailure)
        val error = IOException("decode failure")
        val failure = Class.forName("kotlin.ResultKt").getMethod("createFailure", Throwable::class.java).invoke(null, error)
        assertSame(error, decodeHostStreamResult(failure).exceptionOrNull())
    }

    private class Slot(@JvmField var entries: List<Any> = emptyList())
    private class InvalidSlot(@JvmField var entries: String = "not a list")

    @Test fun registrationPreservesOthersAndDetachesOnlyItsOwnInterceptor() {
        val first = Any()
        val second = Any()
        val own = Any()
        val otherPlugin = Any()
        val a = Slot(listOf(first))
        val b = Slot(listOf(second))
        val field = Slot::class.java.getDeclaredField("entries")
        val registration = attachStreamInterceptor(listOf(a to field, b to field), own)
        assertEquals(listOf(own, first), a.entries)
        assertEquals(listOf(own, second), b.entries)
        a.entries = listOf(otherPlugin) + a.entries
        registration.close()
        registration.close()
        assertEquals(listOf(otherPlugin, first), a.entries)
        assertEquals(listOf(second), b.entries)
    }

    @Test fun partialRegistrationFailureRestoresOriginalLists() {
        val original = listOf(Any())
        val good = Slot(original)
        val bad = InvalidSlot()
        assertThrows(Exception::class.java) {
            attachStreamInterceptor(listOf(good to Slot::class.java.getDeclaredField("entries"),
                bad to InvalidSlot::class.java.getDeclaredField("entries")), Any())
        }
        assertSame(original, good.entries)
        assertEquals("not a list", bad.entries)
    }
}
