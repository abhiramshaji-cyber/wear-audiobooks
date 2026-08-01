package com.abhiram.audiobooks

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

@UnstableApi
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val library = Library()
    val progress = ProgressStore(app)

    private var controller: MediaController? = null

    var nowPlaying by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set

    /** Known before the controller connects, so launch can go straight to the player screen. */
    val hasResumable = progress.lastPlayed?.let { library.resolve(it) } != null

    init {
        val token = SessionToken(app, ComponentName(app, PlayerService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            if (connected.mediaItemCount == 0) restoreLastPlayed()
            refresh()
        }, ContextCompat.getMainExecutor(app))
    }

    override fun onCleared() {
        controller?.release()
        controller = null
    }

    fun open(track: Track, playlist: List<Track>, play: Boolean) {
        val connected = controller ?: return
        val queue = playlist.ifEmpty { listOf(track) }
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        connected.setMediaItems(queue.map { it.toMediaItem() }, index, progress.startPosition(track.id))
        connected.prepare()
        progress.lastPlayed = track.id
        if (play) connected.play()
        refresh()
    }

    fun togglePlay() {
        val connected = controller ?: return
        if (connected.isPlaying) connected.pause() else connected.play()
    }

    fun skip(deltaMs: Long) {
        val connected = controller ?: return
        val target = connected.currentPosition + deltaMs
        val end = connected.duration
        connected.seekTo(if (end > 0) target.coerceIn(0, end) else target.coerceAtLeast(0))
    }

    fun refresh() {
        val connected = controller ?: return
        val item = connected.currentMediaItem
        nowPlaying = item?.mediaId
        title = item?.mediaMetadata?.title?.toString() ?: item?.mediaId?.substringAfterLast('/') ?: ""
        isPlaying = connected.isPlaying
        position = connected.currentPosition.coerceAtLeast(0)
        duration = connected.duration.takeIf { it > 0 } ?: nowPlaying?.let { progress.duration(it) } ?: 0L
    }

    /** Reopens the last file paused at its saved position rather than starting the library cold. */
    private fun restoreLastPlayed() {
        val track = progress.lastPlayed?.let { library.resolve(it) } ?: return
        open(track, library.tracksIn(track.file.parentFile ?: library.root), play = false)
    }

    private fun Track.toMediaItem() = MediaItem.Builder()
        .setUri(Uri.fromFile(file))
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setIsPlayable(true).build())
        .build()
}
