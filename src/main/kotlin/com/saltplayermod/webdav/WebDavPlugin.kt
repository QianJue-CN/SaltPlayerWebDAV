package com.saltplayermod.webdav

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi

@OptIn(UnstableSpwWorkshopApi::class)
class WebDavPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {
    private var controller: LibraryController? = null
    private var bridge: NativePlaybackBridge? = null
    private var settings: NativeSettingsBridge? = null
    private var settingsUnavailable = "请启用模组，并授予“方法与 UI Hook”权限，然后重新进入当前设置页。"

    override fun start() {
        active = this
        var playbackUnavailable = "在线播放需要“方法与 UI Hook”权限。"
        val permitted = WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)
        if (permitted) {
            try { bridge = NativePlaybackBridge(pluginContext.spwVersion) }
            catch (failure: Exception) { playbackUnavailable = failure.message ?: "当前播放器不支持在线播放适配。" }
        }
        val playback = bridge
        val library = LibraryController(
            WorkshopApi.manager.createConfigManager().getConfig(),
            if (playback == null) null else { tracks, proxy -> playback.play(tracks, proxy) },
            playbackUnavailable,
        )
        controller = library
        if (permitted) {
            try { settings = NativeSettingsBridge(pluginContext.spwVersion, pluginContext.pluginId, library) }
            catch (failure: Exception) { settingsUnavailable = failure.cause?.message ?: failure.message ?: "当前播放器不支持内嵌设置。" }
        }
        WorkshopApi.ui.toast("WebDAV 音乐库已启用，可在模组配置中直接设置。", WorkshopApi.Ui.ToastType.Success)
    }

    override fun stop() {
        if (active === this) active = null
        val currentController = controller
        val currentSettings = settings
        val currentBridge = bridge
        controller = null
        settings = null
        bridge = null
        try { currentController?.close() }
        finally { try { currentSettings?.close() } finally { currentBridge?.close() } }
    }

    companion object {
        @Volatile private var active: WebDavPlugin? = null
        internal fun lyricsFor(id: String, path: String): String? = active?.bridge?.lyricsFor(id, path)
        @JvmStatic fun checkSettings() {
            val current = active
            val message = if (current?.settings != null) "请返回后重新进入当前模组设置页。" else current?.settingsUnavailable ?: "请先启用 WebDAV 音乐库模组。"
            WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning)
        }
    }
}
