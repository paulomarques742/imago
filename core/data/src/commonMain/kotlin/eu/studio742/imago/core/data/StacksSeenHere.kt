package eu.studio742.imago.core.data

import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.DerivedAssetEntity
import eu.studio742.imago.core.data.db.StackMemberEntity
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichStack
import eu.studio742.imago.core.model.isImmichRoomExport

/**
 * An Immich stack as IMAGO shows it.
 *
 * This app's exports never show in it. On the server an export is its original's stack's cover —
 * that is what Immich and other apps see — but here it is the original that is looked at and edited,
 * so the original stands in for it. A stack made only of a photo and its exports is no stack here;
 * only the photos that are not exports count.
 *
 * @param cover what the grid shows for the stack: Immich's cover, or the original standing in for it.
 * @param photos the stack's photos that are not exports, the cover first.
 * @param coverIsStandIn Immich's cover is an export, and [cover] the original standing in for it.
 */
internal data class StackSeenHere(
    val id: String,
    val cover: ImmichAsset,
    val photos: List<ImmichAsset>,
    val coverIsStandIn: Boolean,
) {
    val isStack: Boolean get() = photos.size >= 2

    /** The count for the badge, or null when there is no stack to show. */
    val size: Int? get() = photos.size.takeIf { isStack }

    fun members(libraryKey: String) = photos.map { StackMemberEntity(libraryKey, it.id, id, cover.id) }
}

/**
 * [originalOf] maps each export this app made to the photo it came from. An export without the
 * record — sent from another device before it synced — is still told apart by its file name and
 * moment. An export whose original is not in the stack is a photo like the others: it may be the
 * only copy left.
 */
internal fun ImmichStack.seenHere(originalOf: Map<String, String>): StackSeenHere? {
    val ids = assets.map { it.id }.toSet()
    // An export counts as one only while its original is in the stack to stand for it.
    fun isExport(asset: ImmichAsset) = originalOf[asset.id]?.let { it in ids } == true ||
        (asset.id !in originalOf && isImmichRoomExport(asset.originalFileName) && assets.any { other ->
            other.id != asset.id && isExportOf(asset.originalFileName, asset.fileCreatedAt, other.originalFileName, other.fileCreatedAt)
        })
    val photos = assets.filterNot(::isExport)
    val primary = assets.firstOrNull { it.id == primaryAssetId }?.takeUnless(::isExport)
    val cover = primary
        ?: originalOf[primaryAssetId]?.let { original -> photos.firstOrNull { it.id == original } }
        ?: photos.firstOrNull()
        ?: return null
    return StackSeenHere(id, cover, listOf(cover) + photos.filter { it.id != cover.id }, coverIsStandIn = primary == null)
}

/**
 * A month of the timeline as the catalogue keeps it.
 *
 * The timeline gives one photo per stack, its cover, with the stack's count. When that cover is an
 * export of this app, the original stands in for it, from [known] — what the catalogue already had,
 * or was fetched for it. Every stack's count is the one of [stacks], the photos that are not exports;
 * a stack of fewer than two has no badge. Without [stacks] for a stack — a key without `stack.read` —
 * a cover keeps the timeline's count, and an original standing in has none.
 *
 * An export whose original is not in [presentOriginals] — deleted, and the export is the only copy
 * left — is a photo like any other.
 */
internal fun timelineRows(
    libraryKey: String,
    assets: List<ImmichAsset>,
    known: Map<String, AssetEntity>,
    stacks: Map<String, List<StackMemberEntity>>,
    originalOf: Map<String, String>,
    presentOriginals: Set<String>,
): List<AssetEntity> = assets.mapNotNull { asset ->
    val members = asset.stackId?.let { stacks[it] }
    fun AssetEntity.withStack(): AssetEntity = when {
        members == null -> this
        members.size >= 2 -> copy(stackId = asset.stackId, stackCount = members.size)
        else -> copy(stackId = null, stackCount = null)
    }
    val asItself = AssetEntity.fromDomain(libraryKey, asset).keeping(known[asset.id])
    when {
        asset.id !in originalOf || originalOf.getValue(asset.id) !in presentOriginals -> asItself.withStack()
        // A loose export: its original is in the timeline on its own.
        asset.stackId == null -> null
        else -> {
            val standIn = members?.firstOrNull()?.primaryAssetId ?: originalOf.getValue(asset.id)
            known[standIn]?.let { row -> if (members == null) row.copy(stackId = null, stackCount = null) else row.withStack() }
                ?: asItself.withStack()
        }
    }
}.distinctBy { it.id }

/** The originals standing in for exports in [assets], which [known] has to provide. */
internal fun standInsNeeded(
    assets: List<ImmichAsset>,
    stacks: Map<String, List<StackMemberEntity>>,
    originalOf: Map<String, String>,
): List<String> = assets
    .filter { it.id in originalOf && it.stackId != null }
    .map { stacks[it.stackId]?.firstOrNull()?.primaryAssetId ?: originalOf.getValue(it.id) }
    .distinct()

/**
 * What stands for [photo] on the server: its newest export in [assetIds], or the photo itself.
 *
 * On the server an export goes on top of its original — that is what the web and other apps show —
 * so a photo made a stack's cover there is made so through it.
 */
internal fun faceOf(photo: String, assetIds: Collection<String>, exports: List<DerivedAssetEntity>): String =
    exports.filter { it.originalAssetId == photo && it.derivedAssetId in assetIds }
        .maxByOrNull { it.createdAt }
        ?.derivedAssetId
        ?: photo

/**
 * Taking [photo] out of [stack]: it leaves with its exports, which stay stacked over it.
 *
 * @param leaving what leaves the stack, the photo and its exports.
 * @param newCover the stack's new cover on the server, when the old one is leaving — Immich will not
 *   take a stack's cover out of it.
 * @param undo the stack is left with fewer than two photos, and is undone instead.
 * @param regroup the photo and its exports, to stack again on their own, the newest export first;
 *   empty when it had none.
 */
internal data class StackRemoval(
    val leaving: List<String>,
    val newCover: String?,
    val undo: Boolean,
    val regroup: List<String>,
)

internal fun ImmichStack.removing(photo: String, exports: List<DerivedAssetEntity>): StackRemoval {
    val ids = assets.map { it.id }
    val ownExports = exports.filter { it.originalAssetId == photo && it.derivedAssetId in ids }.map { it.derivedAssetId }
    val leaving = listOf(photo) + ownExports
    val staying = ids - leaving.toSet()
    val exportIds = exports.map { it.derivedAssetId }.toSet()
    val newCover = if (primaryAssetId in leaving) {
        staying.firstOrNull { it !in exportIds }?.let { faceOf(it, staying, exports) } ?: staying.firstOrNull()
    } else {
        null
    }
    val regroup = if (ownExports.isEmpty()) emptyList() else faceOf(photo, ids, exports).let { face -> listOf(face) + (leaving - face) }
    return StackRemoval(leaving, newCover, undo = staying.size < 2, regroup = regroup)
}

/** Undoing [stack]: each original that had exports in it stays stacked with them, the newest on top. */
internal fun ImmichStack.pairsAfterUndoing(exports: List<DerivedAssetEntity>): List<List<String>> {
    val ids = assets.map { it.id }.toSet()
    return exports.filter { it.derivedAssetId in ids && it.originalAssetId in ids }
        .groupBy { it.originalAssetId }
        .map { (original, own) ->
            val face = faceOf(original, ids, exports)
            listOf(face) + (listOf(original) + own.map { it.derivedAssetId } - face)
        }
}

/**
 * This device's stacks as they still stand, without the photos in [gone] — no longer on the device.
 * A stack left with one photo is undone; one whose cover is gone takes another photo as its cover.
 */
internal fun settleLocalStacks(members: List<StackMemberEntity>, gone: Set<String>): List<StackMemberEntity> =
    members.filterNot { it.assetId in gone }
        .groupBy { it.stackId }
        .filterValues { it.size >= 2 }
        .flatMap { (_, photos) ->
            val cover = photos.firstOrNull { it.isCover }?.assetId ?: photos.first().assetId
            photos.map { it.copy(primaryAssetId = cover) }
        }

/**
 * A new stack of [assetIds], the first as its cover. A photo already in a stack brings that whole
 * stack along, as stacking a cover does in Immich.
 */
internal fun stackedTogether(
    libraryKey: String,
    assetIds: List<String>,
    members: List<StackMemberEntity>,
    newStackId: String,
): List<StackMemberEntity> {
    val joined = assetIds.mapNotNull { id -> members.firstOrNull { it.assetId == id }?.stackId }.toSet()
    val photos = (assetIds + members.filter { it.stackId in joined }.map { it.assetId }).distinct()
    return members.filterNot { it.stackId in joined } + photos.map { StackMemberEntity(libraryKey, it, newStackId, assetIds.first()) }
}
