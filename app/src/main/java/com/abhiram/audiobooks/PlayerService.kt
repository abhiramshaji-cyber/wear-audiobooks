package com.abhiram.audiobooks

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

private const val TICK_MS = 5_000L
private const val SAVE_EPSILON_MS = 1_000L

/**
 * Owns the player so playback survives the UI being swiped away, and is the single place that
 * records progress. Positions are saved on every discrete event plus a 5s tick, which is what
 * covers the cases that get no callback at all: LMK kills, force-stop, crash, flat battery.
 */
@UnstableApi
class PlayerService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var store: ProgressStore
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = Tracker()
    private val library = Library()

    override fun onCreate() {
        super.onCreate()
        store = ProgressStore(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Without a wake lock the watch dozes mid-chapter and playback stalls.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(tracker)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        tracker.record()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tracker)
        tracker.record()
        session.release()
        player.release()
        super.onDestroy()
    }

    private inner class Tracker : Player.Listener, Runnable {

        private var savedId: String? = null
        private var savedPosition = Long.MIN_VALUE

        override fun run() {
            record()
            handler.postDelayed(this, TICK_MS)
        }

        fun record() {
            val id = player.currentMediaItem?.mediaId ?: return
            // A tick can land after the file was deleted, which would resurrect the position it just lost.
            if (library.resolve(id) == null) return
            val position = player.currentPosition
            if (id == savedId && Math.abs(position - savedPosition) < SAVE_EPSILON_MS) return
            val duration = player.duration.let { if (it == C.TIME_UNSET) store.duration(id) else it }
            store.save(id, position, duration)
            if (id != savedId) store.lastPlayed = id
            savedId = id
            savedPosition = position
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            record()
            handler.removeCallbacks(this)
            if (isPlaying) handler.postDelayed(this, TICK_MS)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) record()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                oldPosition.mediaItem?.mediaId?.let { store.markFinished(it) }
            }
            // Read the new file's saved position before record() can overwrite it with a fresh 0.
            val resumeAt = newPosition.mediaItem?.mediaId?.let { store.startPosition(it) } ?: 0L
            val movedFile = oldPosition.mediaItemIndex != newPosition.mediaItemIndex
            if (movedFile && newPosition.positionMs == 0L && resumeAt > 0L) {
                player.seekTo(resumeAt)
                return
            }
            record()
        }

        override fun onPlayerError(error: PlaybackException) = record()
    }
}
