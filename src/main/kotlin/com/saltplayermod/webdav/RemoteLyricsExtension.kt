package com.saltplayermod.webdav

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

@Extension
class RemoteLyricsExtension : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? =
        WebDavPlugin.lyricsFor(mediaItem.id, mediaItem.path)
}
