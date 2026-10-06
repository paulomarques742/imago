package eu.studio742.imago.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SyncMigration {
    internal val SYNCED_TABLES get() = SyncTables.SYNCED

    /**
     * The sync state, without anything that depends on the network or an account.
     *
     * It is additive: no row changes its key or content. `editedAt` starts equal to the moment each
     * record already stored, so that the first sign-in compares edits by the date they were made and
     * not by the date of the migration. Nothing is left `dirty` — signing in is what marks
     * everything to be sent.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for ((table, timestamp) in SYNCED_TABLES) {
                db.execSQL("ALTER TABLE $table ADD COLUMN remoteRevision INTEGER")
                db.execSQL("ALTER TABLE $table ADD COLUMN editedAt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN editedByDevice TEXT")
                db.execSQL("ALTER TABLE $table ADD COLUMN deletedAt TEXT")
                db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE $table SET editedAt = $timestamp")
            }
            db.execSQL("ALTER TABLE recipes ADD COLUMN contentSha1 TEXT")
            db.execSQL("ALTER TABLE recipes ADD COLUMN hintsJson TEXT")

            db.execSQL("ALTER TABLE assets ADD COLUMN sizeBytes INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_assets_libraryKey_sizeBytes_fileCreatedAt " +
                    "ON assets (libraryKey, sizeBytes, fileCreatedAt)",
            )

            db.execSQL("CREATE TABLE IF NOT EXISTS sync_cursors (entity TEXT NOT NULL, cursor INTEGER NOT NULL, PRIMARY KEY(entity))")
            db.execSQL("CREATE TABLE IF NOT EXISTS library_links (localKey TEXT NOT NULL, remoteLibraryId TEXT NOT NULL, PRIMARY KEY(localKey))")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS content_hashes (libraryKey TEXT NOT NULL, assetId TEXT NOT NULL, " +
                    "sha1 TEXT NOT NULL, size INTEGER, dateModified INTEGER, PRIMARY KEY(libraryKey, assetId))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_content_hashes_sha1 ON content_hashes (sha1)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS remote_recipes (contentSha1 TEXT NOT NULL, recipeJson TEXT NOT NULL, " +
                    "revision INTEGER NOT NULL, editedAt TEXT NOT NULL, editedByDevice TEXT, hintsJson TEXT, PRIMARY KEY(contentSha1))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS recipe_conflicts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "libraryKey TEXT NOT NULL, assetId TEXT NOT NULL, recipeJson TEXT NOT NULL, deviceName TEXT NOT NULL, editedAt TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_conflicts_libraryKey_assetId ON recipe_conflicts (libraryKey, assetId)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS candidate_rejections (contentSha1 TEXT NOT NULL, libraryKey TEXT NOT NULL, " +
                    "assetId TEXT NOT NULL, rejectedAt TEXT NOT NULL, PRIMARY KEY(contentSha1, libraryKey, assetId))",
            )
        }
    }
}
