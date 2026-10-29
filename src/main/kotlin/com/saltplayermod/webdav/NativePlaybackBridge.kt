package com.saltplayermod.webdav

import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.Closeable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val resultFailure = Result::class.java.getMethod("exceptionOrNull-impl", Any::class.java)

internal fun decodeHostStreamResult(value: Any?): Result<Int> {
    val failure = resultFailure.invoke(null, value) as Throwable?
    return when {
        failure != null -> Result.failure(failure)
        value is Int && value != 0 -> Result.success(value)
        else -> Result.failure(IllegalStateException("网络解码器没有返回有效的音频流"))
    }
}

internal class NativeStreamInterceptor(
    type: Class<*>,
    private val lookup: (Any) -> String?,
    private val open: (String, Int) -> Result<Int>,
) : Closeable {
    @Volatile private var closed = false
    val instance: Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "WebDAVStreamInterceptor"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> create(arguments)
        }
    }

    private fun create(arguments: Array<out Any?>?): Result<Int>? {
        if (closed) return null
        val item = arguments?.getOrNull(1) ?: return null
        return try {
            val url = lookup(item) ?: return null
            require(arguments[2] == 0L && arguments[3] == 0L) { "网络音乐不支持 CUE 分段播放" }
            open(url, arguments[4] as Int)
        } catch (failure: Exception) {
            Result.failure((failure as? InvocationTargetException)?.targetException ?: failure)
        }
    }

    override fun close() { closed = true }
}

private data class InstalledInterceptorSlot(
    val owner: Any, val field: Field, val original: List<*>, val replacement: CopyOnWriteArrayList<Any?>,
)

internal fun attachStreamInterceptor(slots: List<Pair<Any, Field>>, interceptor: Any): Closeable {
    val installed = mutableListOf<InstalledInterceptorSlot>()
    try {
        for ((owner, field) in slots) {
            synchronized(owner) {
                field.isAccessible = true
                val original = field.get(owner) as List<*>
                val next = CopyOnWriteArrayList<Any?>(original)
                next.add(0, interceptor)
                field.set(owner, next)
                installed += InstalledInterceptorSlot(owner, field, original, next)
            }
        }
    } catch (failure: Exception) {
        for ((owner, field, original, replacement) in installed.asReversed()) {
            try { synchronized(owner) { if (field.get(owner) === replacement) field.set(owner, original) } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
        }
        throw failure
    }
    return Closeable {
        var problem: Exception? = null
        for ((owner, field) in installed) {
            try {
                synchronized(owner) {
                    val current = field.get(owner) as List<*>
                    if (current.any { it === interceptor }) {
                        val remaining = CopyOnWriteArrayList(current.filter { it !== interceptor })
                        field.set(owner, remaining)
                    }
                }
            } catch (failure: Exception) { if (problem == null) problem = failure else problem.addSuppressed(failure) }
        }
        problem?.let { throw it }
    }
}

internal class HostBindings(loader: ClassLoader, version: String) {
    val itemClass: Class<*>
    val interceptorClass: Class<*>
    val interceptorMethod: Method
    private val controllerClass: Class<*>
    private val playerClass: Class<*>
    private val service: Field
    private val playerGetter: Method
    private val owners: List<Pair<Field, Field>>
    private val streamClass: Class<*>
    private val ownerClass: Class<*>
    private val mediaConstructor: java.lang.reflect.Constructor<*>
    private val queueMethod: Method
    private val rangeMethod: Method
    private val getters: List<Method>

    init {
        require(version == "1.19.3") { "在线播放目前适配 Salt Player 1.19.3；当前版本为 $version" }
        itemClass = loader.loadClass("com.xuncorp.pisces.PiscesMediaItem")
        interceptorClass = loader.loadClass("androidx.compose.ui.ś")
        val contextClass = loader.loadClass("com.xuncorp.pisces.PlatformContext")
        interceptorMethod = interceptorClass.methods.single { it.parameterTypes.toList() == listOf(
            contextClass, itemClass, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java,
        ) }
        mediaConstructor = itemClass.getConstructor(*Array(6) { String::class.java })
        controllerClass = loader.loadClass("com.xuncorp.voxzen.service.PlaybackController")
        queueMethod = controllerClass.getMethod("setPlaybackQueue", List::class.java, Int::class.javaPrimitiveType)
        service = controllerClass.getDeclaredField("service").apply { isAccessible = true }
        playerGetter = service.type.getMethod("getPlayer")
        playerClass = loader.loadClass("com.xuncorp.pisces.Ԩ")
        val ownerTypes = listOf("com.xuncorp.pisces.PiscesPlayer\$Properties", "androidx.compose.ui.є", "androidx.compose.ui.ź")
        owners = ownerTypes.map { name ->
            val type = loader.loadClass(name)
            val parent = playerClass.declaredFields.single { it.type == type }.apply { isAccessible = true }
            val list = type.declaredFields.single { it.type == List::class.java && it.genericType.typeName.contains(interceptorClass.name) }
                .apply { isAccessible = true }
            parent to list
        }
        streamClass = loader.loadClass("com.xuncorp.voxzen.cast.HttpRangeBassStream")
        ownerClass = loader.loadClass("com.xuncorp.voxzen.cast.HttpRangeStreamOwner")
        rangeMethod = streamClass.methods.single { it.name.startsWith("create-") && it.parameterTypes.toList() ==
            listOf(String::class.java, Int::class.javaPrimitiveType, ownerClass) }
        getters = itemClass.declaredMethods.filter { it.parameterCount == 0 && it.returnType == String::class.java && it.name != "toString" }
        require(interceptorClass.isInterface && getters.size == 6 && interceptorMethod.returnType == Any::class.java && rangeMethod.returnType == Any::class.java) {
            "播放器内部接口签名已变化"
        }
    }

    fun mappedUrl(item: Any, urls: Map<String, String>): String? = getters.firstNotNullOfOrNull {
        urls[it.invoke(item) as String]
    }

    fun createStream(url: String, flags: Int): Result<Int> = try {
        val instance = streamClass.getField("INSTANCE").get(null)
        val owner = ownerClass.enumConstants.single { (it as Enum<*>).name == "Unspecified" }
        decodeHostStreamResult(rangeMethod.invoke(instance, url, flags, owner))
    } catch (failure: Exception) {
        Result.failure((failure as? InvocationTargetException)?.targetException ?: failure)
    }

    fun attach(interceptor: Any): Closeable {
        val player = playerGetter.invoke(service.get(null))
        require(playerClass.isInstance(player)) { "当前音频引擎不支持网络流适配" }
        val slots = owners.map { (parent, list) -> parent.get(player) to list }
        return attachStreamInterceptor(slots, interceptor)
    }

    fun queue(items: List<List<String>>) {
        val media = items.map { mediaConstructor.newInstance(*it.toTypedArray()) }
        val controller = controllerClass.getField("INSTANCE").get(null)
        queueMethod.invoke(controller, media, 0)
    }
}

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativePlaybackBridge(version: String) : Closeable {
    private val bindings = HostBindings(WorkshopApi::class.java.classLoader, version)
    private val urls = ConcurrentHashMap<String, String>()
    private val lyricsSources = ConcurrentHashMap<String, LyricsSource>()
    private val tagReader = HostTagReader(WorkshopApi::class.java.classLoader)
    private data class LyricsSource(val proxy: StreamProxy, val track: RemoteTrack, val embedded: String?)
    private val stateLock = Any()
    private val interceptor = NativeStreamInterceptor(bindings.interceptorClass, { bindings.mappedUrl(it, urls) }) { url, flags ->
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            Result.failure(SecurityException("在线播放权限已被撤销"))
        } else bindings.createStream(url, flags)
    }
    private var registration: Closeable? = null
    @Volatile private var closed = false

    fun play(tracks: List<RemoteTrack>, proxy: StreamProxy) {
        check(!closed) { "模组已停用" }
        require(WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) { "在线播放权限已被撤销，请重新启用模组并授权" }
        require(tracks.isNotEmpty()) { "请选择要播放的音乐" }
        proxy.checkPlayable(tracks.first())
        val prepared = tracks.map { track ->
            checkCancelled()
            val info = try { proxy.trackInfo(track, tagReader) }
                catch (_: Exception) { checkCancelled(); TrackInfo() }
            track to info
        }
        synchronized(stateLock) {
            check(!closed) { "模组已停用" }
            require(WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) { "在线播放权限已被撤销" }
            if (registration == null) registration = bindings.attach(interceptor.instance)
            val items = prepared.map { (track, info) ->
                val url = proxy.urlFor(track)
                val id = "saltplayer-webdav:" + digest(url)
                urls[id] = url
                lyricsSources[id] = LyricsSource(proxy, track, info.lyrics)
                info.queueFields(id, track, url)
            }
            bindings.queue(items)
            WorkshopApi.playback.play()
        }
    }

    fun lyricsFor(id: String, path: String): String? {
        if (closed) return null
        val key = if (id.isNotBlank()) id else "saltplayer-webdav:" + digest(path)
        val source = lyricsSources[key] ?: return null
        return source.proxy.lyrics(source.track, source.embedded)
    }

    override fun close() {
        synchronized(stateLock) {
            closed = true
            interceptor.close()
            try { registration?.close() } finally { registration = null; urls.clear(); lyricsSources.clear() }
        }
    }
}
