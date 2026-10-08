package eu.studio742.imago.core.data

import eu.studio742.imago.core.data.db.AssetEntity
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
 * record — sent from another device before it synced — is still told apart by its file name.
 */
internal fun ImmichStack.seenHere(originalOf: Map<String, String>): StackSeenHere? {
    fun isExport(asset: ImmichAsset) = asset.id in originalOf || isImmichRoomExport(asset.originalFileName)
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
 */
internal fun timelineRows(
    libraryKey: String,
    assets: List<ImmichAsset>,
    known: Map<String, AssetEntity>,
    stacks: Map<String, List<StackMemberEntity>>,
    originalOf: Map<String, String>,
): List<AssetEntity> = assets.mapNotNull { asset ->
    val members = asset.stackId?.let { stacks[it] }
    fun AssetEntity.withStack(): AssetEntity = when {
        members == null -> this
        members.size >= 2 -> copy(stackId = asset.stackId, stackCount = members.size)
        else -> copy(stackId = null, stackCount = null)
    }
    when {
        asset.id !in originalOf -> AssetEntity.fromDomain(libraryKey, asset).keeping(known[asset.id]).withStack()
        // A loose export: its original is in the timeline on its own.
        asset.stackId == null -> null
        else -> {
            val standIn = members?.firstOrNull()?.primaryAssetId ?: originalOf.getValue(asset.id)
            known[standIn]?.let { row -> if (members == null) row.copy(stackId = null, stackCount = null) else row.withStack() }
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
