package eu.studio742.imago.core.data

import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.immichRoomExportFileName
import eu.studio742.imago.core.model.isImmichRoomExport
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * This app's exports, and when they are hidden.
 *
 * Inside the app it is the original that is looked at and edited, so an export is hidden — but only
 * while its original is there to stand for it. An export whose original is gone is the only copy
 * left, and shows like any other photo; when the original comes back, the export hides again.
 */

/** An export keeps its original's moment; a time zone apart at most. */
private val SAME_MOMENT: Duration = Duration.ofDays(1)

/**
 * Whether [exportName], taken at [exportCreatedAt], is this app's export of the photo [originalName]
 * taken at [originalCreatedAt]: the name an export of it is saved with, from the same moment.
 *
 * A name alone could be anyone's: the moment is what makes it this photo's.
 */
internal fun isExportOf(exportName: String, exportCreatedAt: String, originalName: String, originalCreatedAt: String): Boolean {
    if (originalName.isBlank() || isImmichRoomExport(originalName)) return false
    if (!immichRoomExportFileName(originalName, untitled = "").equals(exportName, ignoreCase = true)) return false
    val exported = momentOf(exportCreatedAt) ?: return false
    val taken = momentOf(originalCreatedAt) ?: return false
    return Duration.between(exported, taken).abs() < SAME_MOMENT
}

/** Whether two capture moments are the same photo's: the unified library's rule, less than a day apart. */
internal fun sameMoment(first: String, second: String): Boolean {
    val a = momentOf(first) ?: return false
    val b = momentOf(second) ?: return false
    return Duration.between(a, b).abs() < SAME_MOMENT
}

private fun momentOf(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

/**
 * Of [exports], the ones whose original is in the library — in the catalogue, under a stack's cover,
 * among [alongside], the photos arriving with them, or on this phone.
 *
 * [originalOf] holds the exports with a record, by the photo they came from; the others are told
 * apart by their name and moment. A server's timeline rows have no names until a photo is opened:
 * an export without a record whose original was never opened shows — the safe side of the mistake.
 */
internal suspend fun ImmichRoomDatabase.exportsWithOriginal(
    libraryKey: String,
    exports: List<AssetEntity>,
    originalOf: Map<String, String>,
    alongside: List<AssetEntity> = emptyList(),
): Set<String> {
    if (exports.isEmpty()) return emptySet()
    val recorded = exports.filter { it.id in originalOf }
    val originals = recorded.map { originalOf.getValue(it.id) }.distinct().chunked(SQL_VARIABLES)
    val present = alongside.map { it.id }.toSet() +
        originals.flatMap { assetDao().byIds(libraryKey, it) }.map { it.id } +
        originals.flatMap { stackMemberDao().ofAssets(libraryKey, it) }.map { it.assetId }
    val byRecord = recorded.filter { originalOf.getValue(it.id) in present }.map { it.id }
    val byName = exports.filter { it.id !in originalOf && isImmichRoomExport(it.originalFileName) }.filter { export ->
        val moment = momentOf(export.fileCreatedAt) ?: return@filter false
        val from = moment.minus(SAME_MOMENT.multipliedBy(2)).toString()
        val to = moment.plus(SAME_MOMENT.multipliedBy(2)).toString()
        // The phone's copy of the original counts too: a server's timeline rows have no names, and an
        // edit saved to the phone and backed up to the server would show there beside its original.
        val near = alongside + assetDao().between(libraryKey, from, to) +
            (if (libraryKey != DEVICE_LIBRARY_ID) assetDao().between(DEVICE_LIBRARY_ID, from, to) else emptyList())
        near.any { it.id != export.id && isExportOf(export.originalFileName, export.fileCreatedAt, it.originalFileName, it.fileCreatedAt) }
    }.map { it.id }
    return (byRecord + byName).toSet()
}

/** Takes out of the catalogue this app's exports whose original is in it; the others stay. */
internal suspend fun ImmichRoomDatabase.hideExportsWithOriginals(libraryKey: String) {
    val originalOf = derivedAssetDao().live(libraryKey).associate { it.derivedAssetId to it.originalAssetId }
    exportsWithOriginal(libraryKey, assetDao().exportCandidates(libraryKey), originalOf)
        .forEach { assetDao().delete(libraryKey, it) }
}

/** Under SQLite's limit on the variables of one statement, which older Androids keep at 999. */
private const val SQL_VARIABLES = 500
