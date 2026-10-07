package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.designsystem.NativeDialogs
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.decodePixels
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/**
 * The export on desktop: the original downloaded and decoded by Skia (or by ImageIO, for TIFF), the
 * recipe by the CPU engine, and the JPEG saved where the user chooses.
 */
class DesktopEditorExporter(private val library: LibraryRepository) : EditorExporter {
    override suspend fun renderJpeg(asset: EditorAsset, recipe: EditRecipe, onPhase: (UiText) -> Unit): File = withContext(Dispatchers.IO) {
        onPhase(uiText(Res.string.editor_phase_reading))
        val original = File.createTempFile("imago-original-", ".asset")
        try {
            library.downloadOriginal(asset.id, original)
            val pixels = decodePixels(original.readBytes()) ?: readWithImageIo(original)
                ?: throw LocalizedException(uiText(Res.string.editor_format_unsupported_desktop))
            onPhase(uiText(Res.string.editor_phase_applying))
            val rendered = RecipePixels.render(pixels, recipe)
            onPhase(uiText(Res.string.editor_phase_writing))
            File.createTempFile("imago-export-", ".jpg").also { output -> output.writeBytes(encodeJpeg(rendered)) }
        } finally {
            original.delete()
        }
    }

    override suspend fun saveToDevice(jpeg: File, fileName: String, createdAt: String): UiText? {
        val destination = NativeDialogs.saveFile(fileName, listOf(NativeDialogs.Filter("JPEG", listOf("jpg", "jpeg")))) ?: return null
        withContext(Dispatchers.IO) { Files.copy(jpeg.toPath(), destination, StandardCopyOption.REPLACE_EXISTING) }
        return uiText(Res.string.editor_export_saved_to, destination.fileName.toString())
    }

    override suspend fun saveOriginalToDevice(file: File, fileName: String, isVideo: Boolean, createdAt: String): UiText? {
        val destination = NativeDialogs.saveFile(fileName) ?: return null
        withContext(Dispatchers.IO) { Files.copy(file.toPath(), destination, StandardCopyOption.REPLACE_EXISTING) }
        return uiText(Res.string.editor_original_saved_to, destination.fileName.toString())
    }

    private fun readWithImageIo(file: File): PixelBuffer? = runCatching {
        ImageIO.read(file)?.let { image -> PixelBuffer(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width)) }
    }.getOrNull()

    private fun encodeJpeg(pixels: PixelBuffer): ByteArray {
        val bytes = ByteArray(pixels.width * pixels.height * 4)
        for (i in pixels.pixels.indices) {
            val p = pixels.pixels[i]; val o = i * 4
            bytes[o] = p.toByte(); bytes[o + 1] = (p ushr 8).toByte(); bytes[o + 2] = (p ushr 16).toByte(); bytes[o + 3] = 0xFF.toByte()
        }
        val info = ImageInfo(pixels.width, pixels.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, bytes, pixels.width * 4).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, 95)) { "Could not encode the JPEG." }.bytes
        }
    }
}

@Composable
actual fun editorViewModel(): EditorViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel {
        EditorViewModel(PlatformContext.INSTANCE, graph.recipes, graph.savedRecipes, graph.derivedAssets,
            graph.configuration, graph.api, DesktopEditorExporter(graph.library))
    }
}

@Composable
actual fun recipeLibraryViewModel(): RecipeLibraryViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { RecipeLibraryViewModel(graph.savedRecipes) }
}

@Composable
actual fun rememberSaveToDevice(onSave: () -> Unit, onDenied: () -> Unit): () -> Unit = onSave

actual val SaveToDeviceLabel: StringResource get() = Res.string.editor_save_as
