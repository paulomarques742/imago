package eu.studio742.imago

import java.io.File

/**
 * Deletes the working files nobody is going to come back for.
 *
 * Downloads for a share or a save, exports, reels and composition proxies are written to the cache
 * folder and deleted by whoever made them — but a process killed halfway, or a file handed to another
 * app, leaves them behind, and nothing else ever looked at them again. Android only empties the cache
 * when the phone runs short of space, so until then they counted as the app's own data.
 *
 * Only what this app names is touched, and only once it is older than [maxAgeMillis]: a file still
 * being written, or still being read by the app it was shared with, is younger than that. The image
 * caches have their own limits and are left alone.
 */
class CacheSweeper(
    private val cacheDir: File,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** How many files went. */
    fun sweep(): Int {
        val cutoff = now() - maxAgeMillis
        val loose = cacheDir.listFiles().orEmpty()
            .filter { it.isFile && TEMPORARY_PREFIXES.any(it.name::startsWith) }
        val inFolders = WORKING_FOLDERS
            .mapNotNull { File(cacheDir, it).takeIf(File::isDirectory) }
            .flatMap { folder -> folder.walkBottomUp().filter(File::isFile).toList() }
        return (loose + inFolders).count { it.lastModified() < cutoff && it.delete() }
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

        /** The prefixes the app's own `createTempFile` calls give in the cache folder. */
        val TEMPORARY_PREFIXES = listOf("imago-", "download-", "composition-")

        /** Folders that only ever hold working copies; the folders themselves stay. */
        val WORKING_FOLDERS = listOf("share", "picked", "composition-export")
    }
}
