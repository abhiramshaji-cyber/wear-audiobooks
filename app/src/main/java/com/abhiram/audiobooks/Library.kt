package com.abhiram.audiobooks

import android.os.Environment
import java.io.File

const val LIBRARY_DIR = "Audiobooks"

/** The grant itself needs adb; the README carries the command so the watch screen stays clean. */
const val ACCESS_HINT = "No storage access"

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav")

sealed interface Entry {
    val name: String
    val file: File
}

data class Folder(override val file: File, override val name: String) : Entry

data class Track(override val file: File, val id: String, override val name: String) : Entry

/**
 * One fixed folder in shared storage. Not the app's own external files dir: files pushed there by
 * `adb` stay owned by `shell` and the app cannot read them, while shared storage normalises
 * ownership so anything pushed is readable.
 */
class Library {

    val root: File = File(Environment.getExternalStorageDirectory(), LIBRARY_DIR)

    fun list(dir: File): List<Entry> {
        val children = dir.listFiles() ?: return emptyList()
        val folders = children.filter { it.isDirectory && it.containsAudio() }.map { Folder(it, it.name) }
        val tracks = children.filter { it.isAudio() }.map { trackOf(it) }
        return folders.sortedWith(BY_NAME) + tracks.sortedWith(BY_NAME)
    }

    fun tracksIn(dir: File): List<Track> =
        (dir.listFiles() ?: emptyArray()).filter { it.isAudio() }.map { trackOf(it) }.sortedWith(BY_NAME)

    fun trackOf(file: File) = Track(file, idOf(file), file.nameWithoutExtension)

    /** Resolves a stored id back to a track, or null if the file is gone. */
    fun resolve(id: String): Track? {
        val file = File(root, id)
        return if (file.isFile && file.isAudio()) trackOf(file) else null
    }

    /**
     * All-files access is the only reliable read on Wear: READ_MEDIA_AUDIO serves files through
     * MediaStore, which misses anything adb-pushed but not yet indexed, and never maps .m4b to
     * audio at all. Wear OS ships no Settings screen or document picker to grant it, so adb it is.
     */
    fun hasFullAccess(): Boolean = Environment.isExternalStorageManager()

    /** Why the library looks empty, so a permission problem never reads as "no books". */
    fun emptyReason(): String = when {
        !hasFullAccess() -> ACCESS_HINT
        !root.exists() -> "No /$LIBRARY_DIR folder"
        root.listFiles() == null -> "Cannot read /$LIBRARY_DIR"
        else -> "No books yet"
    }

    /** Recursive so one call removes a single track or a whole book folder. */
    fun delete(entry: Entry): Boolean = entry.file.deleteRecursively()

    /** Ids are paths relative to the root, so moving the whole library keeps every position. */
    fun idOf(file: File) = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')

    private fun File.isAudio() = isFile && extension.lowercase() in AUDIO_EXTENSIONS

    private fun File.containsAudio(): Boolean =
        (listFiles() ?: emptyArray()).any { it.isAudio() || (it.isDirectory && it.containsAudio()) }
}

private val BY_NAME = Comparator<Entry> { a, b -> naturalCompare(a.name, b.name) }

/** Orders "Chapter 2" before "Chapter 10" by comparing digit runs as numbers. */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var endA = i
            while (endA < a.length && a[endA].isDigit()) endA++
            var endB = j
            while (endB < b.length && b[endB].isDigit()) endB++
            val numA = a.substring(i, endA).trimStart('0')
            val numB = b.substring(j, endB).trimStart('0')
            if (numA.length != numB.length) return numA.length - numB.length
            numA.compareTo(numB).let { if (it != 0) return it }
            i = endA
            j = endB
        } else {
            a[i].lowercaseChar().compareTo(b[j].lowercaseChar()).let { if (it != 0) return it }
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
