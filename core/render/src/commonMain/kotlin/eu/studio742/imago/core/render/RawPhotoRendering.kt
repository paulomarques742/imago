package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.KNOWN_RAW_ENGINES
import eu.studio742.imago.core.model.rawHighlightMode
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.appliesBaselineExposure
import eu.studio742.imago.core.model.needsNewerRawRendering
import eu.studio742.imago.core.model.requireUser
import eu.studio742.imago.core.raw.RawDecoder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Recognize the original's contents; an unsupported RAW never falls back to its embedded JPEG.
 * [baselineExposure] is the recipe's [appliesBaselineExposure], or true for a photo without one.
 */
suspend fun decodeRawPhoto(file: File, baselineExposure: Boolean, halfSize: Boolean = false, highlight: Int = CURRENT_RAW_HIGHLIGHT): FloatPixelBuffer? =
    runInterruptible(Dispatchers.IO) {
        RawDecoder.decode(file, halfSize, baselineExposure, highlight)?.let { FloatPixelBuffer(it.width, it.height, it.rgb) }
    }

/** Reduces inside the native bridge so a thumbnail never allocates a sensor-sized JVM array. */
suspend fun decodeRawPreview(file: File, maxSide: Int, baselineExposure: Boolean, highlight: Int = CURRENT_RAW_HIGHLIGHT): FloatPixelBuffer? =
    runInterruptible(Dispatchers.IO) {
        RawDecoder.decodePreview(file, maxSide, baselineExposure, highlight)?.let { FloatPixelBuffer(it.width, it.height, it.rgb) }
    }

/** The editor's preview of a RAW: the pixels, and the camera's colour for white balance in kelvin. */
class RawPreview(val pixels: FloatPixelBuffer, val light: CameraLight?, val baselineExposure: Float = 0f)

/** As [decodeRawPreview], with the camera's colour the editor needs. */
suspend fun decodeRawEditorPreview(file: File, maxSide: Int, baselineExposure: Boolean, highlight: Int = CURRENT_RAW_HIGHLIGHT): RawPreview? =
    runInterruptible(Dispatchers.IO) {
        RawDecoder.decodePreview(file, maxSide, baselineExposure, highlight)?.let {
            RawPreview(FloatPixelBuffer(it.width, it.height, it.rgb), CameraLight.of(it.xyzToDeveloped), it.baselineExposure)
        }
    }

/** LibRaw's highlight mode of a photo not developed yet: the current engine's. */
const val CURRENT_RAW_HIGHLIGHT = 2

fun requireRawEngine(recipe: EditRecipe) {
    requireUser(recipe.processVersion <= CURRENT_PROCESS_VERSION, UserMessage.EDIT_NEEDS_NEWER_APP)
    requireUser(recipe.rawDevelopment?.engine?.let { it in KNOWN_RAW_ENGINES } != false, UserMessage.EDIT_NEEDS_NEWER_APP)
    requireUser(!recipe.needsNewerRawRendering(), UserMessage.EDIT_NEEDS_NEWER_APP)
}

/** A fetch hint, not the decoder's support list. Unknown suffixes and TIFF still inspect the original. */
fun shouldInspectRawOriginal(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif", "avif")
