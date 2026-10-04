package com.choidev.choifile.storage

import java.io.File

/**
 * "갤러리에서 숨기기": an empty `.nomedia` file in a folder tells the media scanner (and so most gallery and music
 * apps) to skip that folder and everything below it. Files stay where they are and remain visible here.
 */
object NoMedia {
    const val NAME = ".nomedia"

    fun has(dir: File): Boolean = File(dir, NAME).exists()

    /** Folders among [dirs] that contain a `.nomedia` file. */
    fun marked(dirs: List<File>): Set<String> = dirs.filter { it.isDirectory && has(it) }.map { it.path }.toSet()

    /**
     * Creates (on) or deletes (off) `.nomedia` in [dir]. Returns true when something changed. An existing non-empty
     * `.nomedia` is left alone when turning on; turning off deletes it whatever it contains, but never a folder
     * that happens to be named `.nomedia`.
     */
    fun set(dir: File, on: Boolean): Boolean {
        require(dir.isDirectory) { "not a folder: ${dir.name}" }
        val f = File(dir, NAME)
        return if (on) {
            if (f.exists()) false else f.createNewFile()
        } else {
            if (!f.isFile) false else f.delete().also { check(it) { "could not delete ${f.path}" } }
        }
    }

    /**
     * Applies [on] to every folder in [dirs] (non-folders are skipped). Returns the folders that changed, which are
     * the ones the media scanner has to look at again.
     */
    fun setAll(dirs: List<File>, on: Boolean): List<File> = dirs.filter { it.isDirectory && set(it, on) }

    /** For a multi-folder toggle: turn on unless every folder is already marked. */
    fun targetState(dirs: List<File>): Boolean = !dirs.filter { it.isDirectory }.let { d -> d.isNotEmpty() && d.all(::has) }
}
