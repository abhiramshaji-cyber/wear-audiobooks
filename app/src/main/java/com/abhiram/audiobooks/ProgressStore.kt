package com.abhiram.audiobooks

import android.content.Context

private const val END_SLACK_MS = 2_000L

/**
 * Per-file playback positions. Backed by SharedPreferences and always written with commit(), so a
 * saved position is on disk (fsynced, atomic) before the call returns — process kills, force-stops
 * and a dead battery cannot lose it. Reads are synchronous, which lets playback start at the right
 * position with no async window.
 */
class ProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun position(id: String) = prefs.getLong("pos:$id", 0L)

    fun duration(id: String) = prefs.getLong("dur:$id", 0L)

    fun isFinished(id: String) = prefs.getBoolean("done:$id", false)

    /** Resuming a finished file at its end is useless, so it starts over. */
    fun startPosition(id: String) = if (isFinished(id)) 0L else position(id)

    fun save(id: String, position: Long, duration: Long) {
        prefs.edit()
            .putLong("pos:$id", position)
            .putLong("dur:$id", duration)
            .putBoolean("done:$id", duration > 0 && position >= duration - END_SLACK_MS)
            .commit()
    }

    fun markFinished(id: String) {
        prefs.edit().putBoolean("done:$id", true).commit()
    }

    /** The file to reopen on launch, so the app never lands on a cold library. */
    var lastPlayed: String?
        get() = prefs.getString("last", null)
        set(value) {
            prefs.edit().putString("last", value).commit()
        }
}
