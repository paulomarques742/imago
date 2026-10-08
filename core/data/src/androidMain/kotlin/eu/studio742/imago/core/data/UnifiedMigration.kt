package eu.studio742.imago.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object UnifiedMigration {
    /** The unified library looks photos up by name in each library: one index, nothing else changes. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(UNIFIED_INDEX_SQL)
        }
    }

    /** Where the device's photos were taken, kept apart from the catalogue, which syncs rewrite. */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ASSET_LOCATIONS_SQL)
        }
    }
}
