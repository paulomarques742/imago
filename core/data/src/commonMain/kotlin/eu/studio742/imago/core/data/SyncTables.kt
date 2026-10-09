package eu.studio742.imago.core.data

object SyncTables {
    /** The tables that sync, with the column that already said when the record was changed. */
    val SYNCED = listOf(
        "recipes" to "updatedAt",
        "derived_assets" to "createdAt",
        "saved_recipes" to "updatedAt",
        "composition_templates" to "updatedAt",
        "brand_kits" to "updatedAt",
        "composition_projects" to "updatedAt",
        "built_in_recipe_marks" to "updatedAt",
    )
}
