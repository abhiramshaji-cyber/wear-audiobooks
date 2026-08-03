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

    /** Drops every position under a deleted track or folder, so freeing space frees its keys too. */
    fun forget(id: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it != "last" }.forEach {
            if (it.substringAfter(':').isUnder(id)) editor.remove(it)
        }
        if (lastPlayed?.isUnder(id) == true) editor.remove("last")
        editor.commit()
    }

    /** Prefix match on a path boundary: deleting "Dune" must not touch "Dune 2.mp3". */
    private fun String.isUnder(id: String) = this == id || startsWith("$id/")

    /** The file to reopen on launch, so the app never lands on a cold library. */
    var lastPlayed: String?
        get() = prefs.getString("last", null)
        set(value) {
            prefs.edit().putString("last", value).commit()
        }
}
