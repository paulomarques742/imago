package eu.studio742.imago.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.*
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.GLOBAL_LIBRARY_ID

/** Pure JSON conversion also upgrades media in backgrounds, templates and brand logos. */
internal fun qualifyLegacyMedia(value: JsonElement, libraryId: String): JsonElement = when (value) {
    is JsonArray -> JsonArray(value.map { qualifyLegacyMedia(it, libraryId) })
    is JsonObject -> JsonObject(value.mapValues { (key, child) ->
        if (key == "assetId" && child is JsonPrimitive && child.isString && child.content.isNotBlank())
            JsonPrimitive(AssetReference.qualify(libraryId, child.content))
        else if (key == "schemaVersion" && "elements" in value) JsonPrimitive(2)
        else qualifyLegacyMedia(child, libraryId)
    })
    else -> value
}

object LibraryMigration {
    // Also accepts the first development build of v9, before the identity registry was added.
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS library_identities (id TEXT NOT NULL PRIMARY KEY)")
            for (table in listOf("assets", "recipes", "derived_assets", "catalog_months")) {
                db.execSQL("INSERT OR IGNORE INTO library_identities(id) SELECT DISTINCT libraryKey FROM $table")
            }
            fun collect(value: JsonElement): Set<String> = when (value) {
                is JsonArray -> value.flatMap { collect(it) }.toSet()
                is JsonObject -> value.flatMap { (key, child) ->
                    if (key == "assetId" && child is JsonPrimitive && child.content.startsWith("imago:"))
                        listOf(AssetReference.parse(child.content).libraryId) else collect(child).toList()
                }.toSet()
                else -> emptySet()
            }
            for ((table, column) in listOf("composition_projects" to "projectJson", "composition_templates" to "templateJson", "brand_kits" to "kitJson")) {
                val ids = mutableSetOf<String>()
                db.query("SELECT $column FROM $table").use { cursor ->
                    while (cursor.moveToNext()) ids += collect(Json.parseToJsonElement(cursor.getString(0)))
                }
                ids.forEach { db.execSQL("INSERT OR IGNORE INTO library_identities(id) VALUES (?)", arrayOf(it)) }
            }
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS library_identities (id TEXT NOT NULL PRIMARY KEY)")
            for (table in listOf("assets", "recipes", "derived_assets", "composition_projects", "composition_templates", "saved_recipes", "brand_kits")) {
                db.execSQL("INSERT OR IGNORE INTO library_identities(id) SELECT DISTINCT libraryKey FROM $table")
            }
            db.execSQL("ALTER TABLE assets ADD COLUMN folderId TEXT")
            db.execSQL("ALTER TABLE assets ADD COLUMN folderName TEXT")
            for ((table, column) in listOf("composition_projects" to "projectJson", "composition_templates" to "templateJson", "saved_recipes" to "recipeJson")) {
                val rows = mutableListOf<Triple<String, String, String>>()
                db.query("SELECT libraryKey, id, $column FROM $table").use { cursor ->
                    while (cursor.moveToNext()) rows += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                }
                for ((library, id, raw) in rows) {
                    val newId = java.util.UUID.nameUUIDFromBytes("$library:$id".toByteArray()).toString()
                    val qualified = qualifyLegacyMedia(Json.parseToJsonElement(raw), library).jsonObject
                    val updated = JsonObject(qualified + ("id" to JsonPrimitive(newId))).toString()
                    db.execSQL("UPDATE $table SET libraryKey = ?, id = ?, $column = ? WHERE libraryKey = ? AND id = ?",
                        arrayOf(GLOBAL_LIBRARY_ID, newId, updated, library, id))
                }
            }
            // Keep old kits as recoverable records; the most recently used kit becomes the app kit.
            db.query("SELECT libraryKey, kitJson, updatedAt FROM brand_kits ORDER BY updatedAt DESC LIMIT 1").use { cursor ->
                if (cursor.moveToFirst()) {
                    val kit = qualifyLegacyMedia(Json.parseToJsonElement(cursor.getString(1)), cursor.getString(0)).toString()
                    db.execSQL("INSERT OR REPLACE INTO brand_kits(libraryKey, kitJson, updatedAt) VALUES (?, ?, ?)",
                        arrayOf(GLOBAL_LIBRARY_ID, kit, cursor.getString(2)))
                }
            }
        }
    }
}
