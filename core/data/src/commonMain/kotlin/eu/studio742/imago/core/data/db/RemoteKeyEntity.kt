package eu.studio742.imago.core.data.db

import androidx.room.Entity

@Entity(tableName = "remote_keys", primaryKeys = ["libraryKey", "filter"])
data class RemoteKeyEntity(
    val libraryKey: String,
    val filter: String,
    val nextPage: Int?,
)

