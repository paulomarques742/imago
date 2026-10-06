package eu.studio742.imago.core.data

/** The record of the assets this app sent to Immich, to keep them out of the library. */
interface DerivedAssetRepository {
    /** Records that [derivedAssetId] was born from an export of [originalAssetId]. */
    suspend fun record(originalAssetId: String, derivedAssetId: String)

    /** The ids known as this app's exports in the active library. */
    suspend fun derivedIds(): Set<String>
}
