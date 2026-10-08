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
}
