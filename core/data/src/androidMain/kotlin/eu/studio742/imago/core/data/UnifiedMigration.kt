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

    /** The archive: a mark on every row, and the device's own list of what was archived. */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ARCHIVE_SQL.forEach(db::execSQL)
        }
    }

    /** The stack each cover holds, and the timeline read again to bring it. */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STACK_SQL.forEach(db::execSQL)
        }
    }

    /** The stacks as the unified library shows them, and the names that find each photo's phone copy. */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            UNIFIED_STACKS_SQL.forEach(db::execSQL)
        }
    }

    /** The marks on the app's presets. */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(BUILT_IN_RECIPE_MARKS_SQL)
        }
    }
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(SAVED_RECIPE_PARTS_SQL)
        }
    }
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(HIDDEN_FOLDERS_SQL)
        }
    }
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ALBUM_MEMBERS_SQL)
        }
    }
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LIVE_PHOTO_SQL.forEach(db::execSQL)
        }
    }
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(LOCAL_MOTIONS_SQL)
        }
    }
}
