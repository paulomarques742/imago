package eu.studio742.imago.core.data

/** The index of version 12, as Room names it; the same statement on both apps. */
const val UNIFIED_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS index_assets_libraryKey_originalFileName ON assets (libraryKey, originalFileName)"
