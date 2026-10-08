package eu.studio742.imago.core.data

/** The table of version 13, as Room writes it; the same statement on both apps. */
const val ASSET_LOCATIONS_SQL =
    "CREATE TABLE IF NOT EXISTS `asset_locations` (`libraryKey` TEXT NOT NULL, `assetId` TEXT NOT NULL, " +
        "`checksum` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, PRIMARY KEY(`libraryKey`, `assetId`))"

/** The index of version 12, as Room names it; the same statement on both apps. */
const val UNIFIED_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS index_assets_libraryKey_originalFileName ON assets (libraryKey, originalFileName)"
