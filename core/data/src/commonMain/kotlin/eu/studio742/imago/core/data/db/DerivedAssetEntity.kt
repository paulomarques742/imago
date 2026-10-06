package eu.studio742.imago.core.data.db

import androidx.room.Embedded
import androidx.room.Entity

/**
 * An asset this app sent to Immich, from [originalAssetId].
 *
 * It is there so it is not shown in the library again: without this record, the export reappears as
 * an independent photo on the server's next read.
 */
@Entity(tableName = "derived_assets", primaryKeys = ["libraryKey", "derivedAssetId"])
data class DerivedAssetEntity(
    val libraryKey: String,
    val derivedAssetId: String,
    val originalAssetId: String,
    val createdAt: String,
    @Embedded val sync: SyncState = SyncState(),
)
