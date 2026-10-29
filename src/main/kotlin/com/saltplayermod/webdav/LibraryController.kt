package com.saltplayermod.webdav

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class ConnectionSettings(
    val address: String = "",
    val folder: String = "/",
    val username: String = "",
    val alist: Boolean = false,
    val directory: String = Path.of(System.getProperty("user.home"), "Music", "WebDAV").toString(),
) {
    fun identity() = digest("${davRoot(address, folder, alist)}|$username")
}

internal data class LibraryState(
    val settings: ConnectionSettings,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val cancelling: Boolean = false,
    val closed: Boolean = false,
    val tracks: List<RemoteTrack> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val status: String = "填写连接信息后，即可浏览远端音乐或双向同步。",
    val error: String? = null,
    val result: SyncResult? = null,
    val detailsExpanded: Boolean = false,
)

@OptIn(UnstableSpwWorkshopApi::class)
internal class LibraryController(
    private val config: ConfigHelper,
    private val playTracks: ((List<RemoteTrack>, StreamProxy) -> Unit)?,
    val playbackUnavailable: String,
) : Closeable {
    private val mutableState = MutableStateFlow(LibraryState(ConnectionSettings(
        address = config.get("server.url", ""),
        folder = config.get("server.folder", "/"),
        username = config.get("server.username", ""),
        alist = config.get("server.alist", false),
        directory = config.get("sync.directory", ConnectionSettings().directory),
    )))
    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    private val mutablePassword = MutableStateFlow("")
    val password: StateFlow<String> = mutablePassword.asStateFlow()
    val canPlay: Boolean get() = playTracks != null
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "webdav-library-worker").apply { isDaemon = true } }
    private val sessionLock = Any()
    private val taskBusy = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var worker: Thread? = null
    @Volatile private var client: WebDavClient? = null
    @Volatile private var proxy: StreamProxy? = null

    fun editSettings(change: (ConnectionSettings) -> ConnectionSettings) {
        if (state.value.busy) return
        update { current ->
            val next = change(current.settings)
            val connected = current.connected && (client?.let { remote -> runCatching { next.identity() == remote.sourceIdentity }.getOrDefault(false) } ?: false)
            current.copy(settings = next, connected = connected, error = null)
        }
    }

    fun editPassword(value: String) {
        if (!closed && !state.value.busy) {
            mutablePassword.value = value
            update { it.copy(connected = false, error = null) }
        }
    }
    fun editQuery(value: String) = update { it.copy(query = value) }
    fun toggleDetails() = update { it.copy(detailsExpanded = !it.detailsExpanded) }
    fun clearSelection() = update { it.copy(selected = emptySet()) }
    fun toggleSelection(path: String) {
        if (!state.value.busy) update { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) }
    }

    fun connect() = handle {
        val settings = state.value.settings
        val root = davRoot(settings.address, settings.folder, settings.alist)
        val secret = mutablePassword.value
        runTask("正在连接服务器……") {
            val remote = WebDavClient(root, settings.username, secret)
            var nextProxy: StreamProxy? = null
            try {
                val found = remote.scan(::updateStatus)
                checkCancelled()
                saveSettings(settings)
                val readyProxy = StreamProxy(remote)
                nextProxy = readyProxy
                readyProxy.start(15_000, true)
                val previous = synchronized(sessionLock) {
                    if (closed || cancelled.get()) throw InterruptedException()
                    val previous = proxy to client
                    client = remote
                    proxy = readyProxy
                    previous
                }
                previous.first?.close()
                previous.second?.close()
                update { it.copy(connected = true, tracks = found, selected = emptySet(), status = "连接成功，找到 ${found.size} 首音乐。") }
            } catch (failure: Exception) {
                if (client !== remote) { nextProxy?.close(); remote.close() }
                throw failure
            }
        }
    }

    fun sync() = handle {
        val remote = connectedClient()
        val settings = state.value.settings
        val target = Path.of(settings.directory).toAbsolutePath().normalize()
        runTask("正在双向同步……") {
            saveSettings(settings.copy(directory = target.toString()))
            val result = SyncEngine(remote, config.getConfigPath().parent).sync(target, ::updateStatus)
            val refreshed = try { remote.scan(::updateStatus) } catch (_: Exception) { checkCancelled(); null }
            update { current -> current.copy(
                tracks = refreshed ?: current.tracks,
                selected = if (refreshed != null) current.selected.intersect(refreshed.map { it.path }.toSet()) else current.selected,
                status = result.summary() + if (refreshed == null) "；请重新扫描远端列表" else "",
                result = result,
                detailsExpanded = result.conflicts.isNotEmpty() || result.failures.isNotEmpty(),
            ) }
        }
    }

    fun play(paths: Set<String> = state.value.selected) = handle {
        connectedClient()
        val playback = playTracks ?: throw IllegalStateException(playbackUnavailable)
        val stream = proxy ?: throw IllegalStateException("请先连接并扫描")
        val selected = state.value.tracks.filter { it.path in paths }
        require(selected.isNotEmpty()) { "请先选择要播放的音乐" }
        runTask("正在打开网络音乐……") {
            playback(selected, stream)
            updateStatus("已提交 ${selected.size} 首音乐到播放队列。")
        }
    }

    fun cancel() {
        if (!state.value.busy) return
        cancelled.set(true)
        worker?.interrupt()
        update { it.copy(cancelling = true, status = "正在取消，等待当前请求结束……") }
    }

    private fun connectedClient(): WebDavClient {
        val remote = client ?: throw IllegalStateException("请先连接并扫描")
        require(state.value.connected && remote.sourceIdentity == state.value.settings.identity()) { "连接信息已修改，请先重新连接并扫描" }
        return remote
    }

    private fun saveSettings(settings: ConnectionSettings) {
        config.set("server.url", settings.address)
        config.set("server.folder", settings.folder)
        config.set("server.alist", settings.alist)
        config.set("server.username", settings.username)
        config.set("sync.directory", settings.directory)
        if (!config.save()) throw IllegalStateException("设置保存失败，请检查配置目录权限")
    }

    private fun runTask(message: String, action: () -> Unit) {
        if (closed || !taskBusy.compareAndSet(false, true)) return
        cancelled.set(false)
        update { it.copy(busy = true, cancelling = false, status = message, error = null, result = null, detailsExpanded = false) }
        executor.execute {
            worker = Thread.currentThread()
            try {
                if (cancelled.get()) throw InterruptedException()
                action()
            } catch (failure: Exception) {
                val wasCancelled = cancelled.get() || Thread.currentThread().isInterrupted || failure is InterruptedException
                update { it.copy(
                    status = if (wasCancelled) "操作已取消，已完成的同步会保留。" else "操作失败，请检查下方提示。",
                    error = if (wasCancelled) null else failure.cause?.message ?: failure.message ?: "未知错误",
                ) }
            } finally {
                worker = null
                Thread.interrupted()
                taskBusy.set(false)
                update { it.copy(busy = false, cancelling = false) }
            }
        }
    }

    private fun handle(action: () -> Unit) {
        if (closed || state.value.busy) return
        try { action() } catch (failure: Exception) { update { it.copy(error = failure.message ?: "操作失败") } }
    }

    private fun updateStatus(message: String) {
        checkCancelled()
        update { it.copy(status = message) }
    }

    private fun update(change: (LibraryState) -> LibraryState) {
        synchronized(sessionLock) { if (!closed) mutableState.update(change) }
    }

    override fun close() {
        val previous = synchronized(sessionLock) {
            closed = true
            val previous = proxy to client
            proxy = null
            client = null
            mutableState.update { it.copy(closed = true, connected = false, busy = false, selected = emptySet(), tracks = emptyList()) }
            previous
        }
        mutablePassword.value = ""
        cancelled.set(true)
        worker?.interrupt()
        executor.shutdownNow()
        try { previous.first?.close() } finally { previous.second?.close() }
    }
}
