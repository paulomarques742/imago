package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.needsNewerRawRendering
import eu.studio742.imago.core.model.withPartsFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull

const val IMAGO_FILE_EXTENSION = "imago"

/** The shape of the file, which moves on its own: the process version says what the values mean. */
const val IMAGO_FILE_VERSION = 1

/** Far above any real pack, and low enough that a file sent by a stranger cannot exhaust the heap. */
const val IMAGO_FILE_MAX_BYTES = 16 * 1024 * 1024

private const val FORMAT_NAME = "imago"
private const val KIND_RECIPES = "recipes"
private const val NAME_LIMIT = 120
private const val COLLECTION_LIMIT = 60

/**
 * What says where a recipe came from, and never what it is. Stripped on the way out so that a file
 * cannot tell which photo it was made on, and again on the way in so that a hand-written one cannot
 * attach itself to a photo.
 */
private val PHOTO_FIELDS = setOf(
    "assetId",
    "originalChecksum",
    "derivedAssetId",
    "exportedLook",
    "rawDevelopment",
    "createdAt",
    "updatedAt",
    "processVersion",
)

// Defaults are written out: a default that changes in a later version must not change a shared recipe.
private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * What is tied to where things are in one photo. A file carries these only if the person asks for
 * them, however the recipe itself was saved.
 */
val PHOTO_BOUND_PARTS: Set<RecipePart> = RecipePart.GEOMETRY + RecipePart.MASKS + RecipePart.REMOVE

private fun emptyRecipe(processVersion: Int) =
    EditRecipe(assetId = "", originalChecksum = "", processVersion = processVersion, createdAt = "", updatedAt = "")

/** The [PHOTO_BOUND_PARTS] this recipe applies and has something in: the ones worth asking about. */
fun SavedRecipe.photoBoundParts(): Set<RecipePart> {
    val neutral = emptyRecipe(recipe.processVersion)
    return appliedParts.filterTo(mutableSetOf()) {
        it in PHOTO_BOUND_PARTS && neutral.withPartsFrom(recipe, setOf(it)) != neutral
    }
}

/** This recipe as a file holds it: [parts] with their values and every other part neutral. */
internal fun SavedRecipe.portableRecipe(parts: Set<RecipePart>): EditRecipe =
    emptyRecipe(recipe.processVersion).withPartsFrom(recipe, parts).copy(schemaVersion = recipe.schemaVersion)

/** A recipe on its way into a file, and the parts it takes. */
data class RecipeExport(val recipe: SavedRecipe, val parts: Set<RecipePart>)

/** One file for one recipe or for many: a pack is only a longer list. */
fun encodeImagoFile(exports: List<RecipeExport>): ByteArray {
    val recipes = exports.map { (saved, parts) ->
        val portable = json.encodeToJsonElement(saved.portableRecipe(parts)) as JsonObject
        JsonObject(
            linkedMapOf(
                "name" to JsonPrimitive(saved.name),
                "collection" to JsonPrimitive(saved.collection),
                "parts" to JsonArray(parts.sorted().map { JsonPrimitive(it.name) }),
                "processVersion" to JsonPrimitive(saved.recipe.processVersion),
                "recipe" to JsonObject(portable - PHOTO_FIELDS),
                "origin" to (saved.origin?.let { JsonPrimitive(it.name) } ?: JsonNull),
            ),
        )
    }
    val file = JsonObject(
        linkedMapOf(
            "format" to JsonPrimitive(FORMAT_NAME),
            "version" to JsonPrimitive(IMAGO_FILE_VERSION),
            "kind" to JsonPrimitive(KIND_RECIPES),
            "recipes" to JsonArray(recipes),
        ),
    )
    return json.encodeToString(JsonElement.serializer(), file).encodeToByteArray()
}

sealed interface ImagoFileContent {
    /** [skippedFiles] are the ones in a pack in a format only a later version will read. */
    data class Recipes(val entries: List<ImagoFileEntry>, val skippedFiles: Int = 0) : ImagoFileContent

    /** A later file version, or something other than recipes: this app cannot know what is inside. */
    data object NeedsNewerApp : ImagoFileContent

    /** Not an IMAGO file, or damaged beyond reading. */
    data object Unreadable : ImagoFileContent
}

sealed interface ImagoFileEntry {
    /** [collection] is empty when the file had none. [unknownParts] are the parts only a newer app knows. */
    data class Ready(
        val name: String,
        val collection: String,
        val parts: Set<RecipePart>,
        val recipe: EditRecipe,
        val unknownParts: Int = 0,
        val origin: RecipeOrigin? = null,
        val gaps: List<ImportGap> = emptyList(),
    ) : ImagoFileEntry

    /** Written by a newer app: drawing it here would draw it wrong, so it is named and left out. */
    data class NeedsNewerApp(val name: String) : ImagoFileEntry

    /** Read to the end, and all it holds is what the app has no place for: named, so it is not taken for a damaged file. */
    data class NothingApplies(val name: String, val gaps: List<ImportGap>) : ImagoFileEntry

    /** A recipe the rest of the file does not have to go down with. */
    data object Invalid : ImagoFileEntry
}

fun decodeImagoFile(bytes: ByteArray): ImagoFileContent = runCatching { readFile(bytes) }.getOrDefault(ImagoFileContent.Unreadable)

private fun readFile(bytes: ByteArray): ImagoFileContent {
    if (bytes.size > IMAGO_FILE_MAX_BYTES) return ImagoFileContent.Unreadable
    val text = bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("﻿")
    val file = Json.parseToJsonElement(text) as? JsonObject ?: return ImagoFileContent.Unreadable
    if (file.text("format") != FORMAT_NAME) return ImagoFileContent.Unreadable
    val version = file.int("version") ?: return ImagoFileContent.Unreadable
    if (version > IMAGO_FILE_VERSION) return ImagoFileContent.NeedsNewerApp
    if (version < 1) return ImagoFileContent.Unreadable
    val kind = file.text("kind") ?: return ImagoFileContent.Unreadable
    if (kind != KIND_RECIPES) return ImagoFileContent.NeedsNewerApp
    val recipes = file["recipes"] as? JsonArray ?: return ImagoFileContent.Unreadable
    if (recipes.isEmpty()) return ImagoFileContent.Unreadable
    return ImagoFileContent.Recipes(recipes.map(::readEntry))
}

private fun readEntry(element: JsonElement): ImagoFileEntry {
    val entry = element as? JsonObject ?: return ImagoFileEntry.Invalid
    val name = cleanText(entry.text("name"), NAME_LIMIT)
    val processVersion = entry.int("processVersion") ?: return ImagoFileEntry.Invalid
    if (processVersion > CURRENT_PROCESS_VERSION) return ImagoFileEntry.NeedsNewerApp(name)
    if (processVersion < 1) return ImagoFileEntry.Invalid
    val fields = entry["recipe"] as? JsonObject ?: return ImagoFileEntry.Invalid

    val recipe = runCatching {
        val placeholders = mapOf(
            "assetId" to JsonPrimitive(""),
            "originalChecksum" to JsonPrimitive(""),
            "processVersion" to JsonPrimitive(processVersion),
            "createdAt" to JsonPrimitive(""),
            "updatedAt" to JsonPrimitive(""),
        )
        json.decodeFromJsonElement<EditRecipe>(JsonObject((fields - PHOTO_FIELDS) + placeholders))
    }.getOrNull() ?: return ImagoFileEntry.Invalid
    if (recipe.needsNewerRawRendering()) return ImagoFileEntry.NeedsNewerApp(name)
    if (!recipe.isSound()) return ImagoFileEntry.Invalid

    val listed = (entry["parts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val parts = if (listed == null) RecipePart.LOOK else RecipePart.parse(listed)
    if (parts.isEmpty()) return if (listed.isNullOrEmpty()) ImagoFileEntry.Invalid else ImagoFileEntry.NeedsNewerApp(name)
    return ImagoFileEntry.Ready(
        name = name,
        collection = cleanText(entry.text("collection"), COLLECTION_LIMIT),
        parts = parts,
        recipe = recipe,
        unknownParts = listed.orEmpty().distinct().size - parts.size,
        origin = originOf(entry["origin"]),
    )
}

/** Anything other than a plain absence counts as coming from outside: losing the mark is the worse mistake. */
private fun originOf(value: JsonElement?): RecipeOrigin? = when {
    value == null || value is JsonNull -> null
    value is JsonPrimitive && value.isString -> RecipeOrigin.parse(value.content)
    else -> RecipeOrigin.OTHER
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

/** Names are text from a stranger: one line, a sensible length, and never half of a surrogate pair. */
internal fun cleanText(value: String?, limit: Int): String =
    value.orEmpty().filterNot(Char::isISOControl).trim().take(limit).trimEnd { it.isHighSurrogate() }.trim()

/**
 * What the renderer indexes by or allocates from. Sliders are left alone, since a slider out of range
 * only draws a strong picture; these would throw or run out of memory.
 */
internal fun EditRecipe.isSound(): Boolean {
    val curves = listOfNotNull(toneCurve.rgb, toneCurve.red, toneCurve.green, toneCurve.blue)
    val strokes = masks.flatMap { mask -> mask.components.flatMap { it.brush?.strokes.orEmpty() } } + retouches.flatMap { it.strokes }
    val crop = geometry.cropRect
    return curves.all { curve ->
        curve.isNotEmpty() && curve.all { it.x in 0..255 && it.y in 0..255 } && curve.zipWithNext().all { (a, b) -> a.x < b.x }
    } &&
        strokes.all { it.points.size % 2 == 0 } &&
        retouches.all { retouch ->
            retouch.fill?.let { it.columns in 1..MAX_FILL_SIDE && it.rows in 1..MAX_FILL_SIDE && it.offsets() != null } ?: true
        } &&
        geometry.rotation % 90 == 0 &&
        crop.w > 0f && crop.h > 0f && crop.x >= -CROP_TOLERANCE && crop.y >= -CROP_TOLERANCE &&
        crop.x + crop.w <= 1f + CROP_TOLERANCE && crop.y + crop.h <= 1f + CROP_TOLERANCE
}

/** `RemoveFill.offsets` allocates by columns × rows, before it can tell that the runs do not cover them. */
private const val MAX_FILL_SIDE = 1024
private const val CROP_TOLERANCE = 0.001f

/** A file name that every system accepts, or [fallback] when the recipe's name leaves nothing of it. */
fun imagoFileName(recipeName: String?, fallback: String): String {
    val stem = recipeName.orEmpty()
        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("")
        .trim(' ', '.')
        .take(80)
        .trimEnd { it.isHighSurrogate() }
        .trim(' ', '.')
    return "${stem.ifEmpty { fallback }}.$IMAGO_FILE_EXTENSION"
}
