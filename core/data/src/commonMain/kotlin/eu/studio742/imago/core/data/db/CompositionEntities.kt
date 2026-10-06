package eu.studio742.imago.core.data.db

import androidx.room.Embedded
import androidx.room.Entity

@Entity(tableName = "composition_projects", primaryKeys = ["libraryKey", "id"])
data class CompositionProjectEntity(
    val libraryKey: String,
    val id: String,
    val name: String,
    val projectJson: String,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
    @Embedded val sync: SyncState = SyncState(),
)

@Entity(tableName = "composition_templates", primaryKeys = ["libraryKey", "id"])
data class CompositionTemplateEntity(
    val libraryKey: String,
    val id: String,
    val name: String,
    val templateJson: String,
    val createdAt: String,
    val updatedAt: String,
    @Embedded val sync: SyncState = SyncState(),
)

@Entity(tableName = "brand_kits", primaryKeys = ["libraryKey"])
data class BrandKitEntity(
    val libraryKey: String,
    val kitJson: String,
    val updatedAt: String,
    @Embedded val sync: SyncState = SyncState(),
)
