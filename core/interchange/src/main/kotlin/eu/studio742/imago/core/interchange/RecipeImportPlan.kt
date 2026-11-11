package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.SavedRecipe

/** One file already read, and the name it came with. */
data class ReadImagoFile(val fileName: String, val content: ImagoFileContent)

/** What one recipe left behind on its way in. */
data class RecipeImportGaps(val name: String, val gaps: List<ImportGap>)

/**
 * What adding these files would do. Nothing is saved to make it, so that it can be shown first, and
 * everything that will not enter is here to be named: a file never loses a recipe in silence.
 */
data class RecipeImportPlan(
    val recipes: List<SavedRecipe> = emptyList(),
    val alreadyInLibrary: List<String> = emptyList(),
    val needsNewerApp: List<String> = emptyList(),
    /** The ones that entered without some part only a newer app knows. */
    val partlyRead: List<String> = emptyList(),
    val invalidRecipes: Int = 0,
    val unreadableFiles: Int = 0,
    val filesNeedingNewerApp: Int = 0,
    val gaps: List<RecipeImportGaps> = emptyList(),
    /** Files in a pack in a format only a later version reads. */
    val skippedFiles: Int = 0,
    val linksGone: Int = 0,
    val linksFailed: Int = 0,
)

private const val FILE_NAME_LIMIT = 60

/** Never replaces anything: a recipe already there is skipped, and a different one with its name is numbered. */
fun planRecipeImport(
    files: List<ReadImagoFile>,
    library: List<SavedRecipe>,
    now: String,
    newId: () -> String,
    /** Where a preset goes when neither it nor a pack names a collection: a file name would make one collection each. */
    importedCollection: String = "",
): RecipeImportPlan {
    val added = mutableListOf<SavedRecipe>()
    val alreadyThere = mutableListOf<String>()
    val newer = mutableListOf<String>()
    val partly = mutableListOf<String>()
    var invalid = 0
    var unreadable = 0
    var newerFiles = 0
    var skipped = 0
    var linksGone = 0
    var linksFailed = 0
    val gaps = mutableListOf<RecipeImportGaps>()

    for (file in files) {
        val stem = cleanText(recipeFileStem(file.fileName), FILE_NAME_LIMIT)
        val entries = when (val content = file.content) {
            ImagoFileContent.Unreadable -> { unreadable++; continue }
            ImagoFileContent.NeedsNewerApp -> { newerFiles++; continue }
            ImagoFileContent.LinkGone -> { linksGone++; continue }
            ImagoFileContent.LinkFailed -> { linksFailed++; continue }
            is ImagoFileContent.Recipes -> { skipped += content.skippedFiles; content.entries }
        }
        for (entry in entries) {
            when (entry) {
                ImagoFileEntry.Invalid -> invalid++
                is ImagoFileEntry.NeedsNewerApp -> newer += entry.name.ifEmpty { stem }
                is ImagoFileEntry.NothingApplies -> gaps += RecipeImportGaps(entry.name.ifEmpty { stem }, entry.gaps)
                is ImagoFileEntry.Ready -> {
                    val name = entry.name.ifEmpty { stem }.ifEmpty { "IMAGO" }
                    if ((library + added).any { it.isSameRecipe(name, entry) }) {
                        alreadyThere += name
                        continue
                    }
                    val saved = SavedRecipe(
                        id = newId(),
                        name = uniqueName(name, (library + added).map { it.name }),
                        collection = entry.collection.ifEmpty { if (entry.origin != null) importedCollection.ifEmpty { stem } else stem },
                        recipe = entry.recipe.copy(createdAt = now, updatedAt = now),
                        createdAt = now,
                        updatedAt = now,
                        parts = entry.parts,
                        origin = entry.origin,
                    )
                    added += saved
                    if (entry.unknownParts > 0) partly += saved.name
                    if (entry.gaps.isNotEmpty()) gaps += RecipeImportGaps(saved.name, entry.gaps)
                }
            }
        }
    }
    return RecipeImportPlan(added, alreadyThere, newer, partly, invalid, unreadable, newerFiles, gaps, skipped, linksGone, linksFailed)
}

/**
 * The same name, or that name as numbered by an earlier import, with the same values under the parts
 * the file brings. It has to see the numbered ones, or importing a pack twice would number it again.
 */
private fun SavedRecipe.isSameRecipe(fileName: String, entry: ImagoFileEntry.Ready): Boolean {
    val isThatName = name.equals(fileName, ignoreCase = true) ||
        (name.startsWith("$fileName (", ignoreCase = true) && name.removeRange(0, fileName.length).matches(NUMBERED_SUFFIX))
    return isThatName && entry.parts.all { it in appliedParts } && portableRecipe(entry.parts) == entry.recipe
}

private val NUMBERED_SUFFIX = Regex(""" \(\d+\)""")

private fun uniqueName(name: String, taken: List<String>): String {
    val used = taken.mapTo(mutableSetOf()) { it.lowercase() }
    if (name.lowercase() !in used) return name
    return generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { it.lowercase() !in used }
}
