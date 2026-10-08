package eu.studio742.imago.core.data

/** The table of version 13, as Room writes it; the same statement on both apps. */
const val ASSET_LOCATIONS_SQL =
    "CREATE TABLE IF NOT EXISTS `asset_locations` (`libraryKey` TEXT NOT NULL, `assetId` TEXT NOT NULL, " +
        "`checksum` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, PRIMARY KEY(`libraryKey`, `assetId`))"

/** What version 14 adds, as Room writes it: the archive. */
val ARCHIVE_SQL = listOf(
    "ALTER TABLE `assets` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0",
    "CREATE TABLE IF NOT EXISTS `archived_assets` (`libraryKey` TEXT NOT NULL, `assetId` TEXT NOT NULL, PRIMARY KEY(`libraryKey`, `assetId`))",
)

/**
 * Version 15: the stack a cover holds. The months are forgotten so that each one is asked for again
 * once, now with its stacks: the sync only goes back to a month whose count changed, and adding the
 * columns changes no count.
 */
val STACK_SQL = listOf(
    "ALTER TABLE `assets` ADD COLUMN `stackId` TEXT",
    "ALTER TABLE `assets` ADD COLUMN `stackCount` INTEGER",
    "DELETE FROM `catalog_months`",
)

/** The index of version 12, as Room names it; the same statement on both apps. */
const val UNIFIED_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS index_assets_libraryKey_originalFileName ON assets (libraryKey, originalFileName)"
