package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.feature.editor.resources.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.render.BitmapPhotoProcessor
import eu.studio742.imago.core.render.renderInPlace
import eu.studio742.imago.core.render.activeGeometry
import eu.studio742.imago.core.render.toRenderParameters
import eu.studio742.imago.core.render.frameGeometry
import eu.studio742.imago.core.render.straightenCoverScale
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt

class FullResolutionExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: eu.studio742.imago.core.data.LibraryRepository,
    private val immichApi: ImmichApi,
) {
    suspend fun renderJpeg(
        asset: EditorAsset,
        recipe: EditRecipe,
        onPhase: (UiText) -> Unit,
    ): File {
        val source = File.createTempFile("imago-source-", ".asset", context.cacheDir)
        val output = File.createTempFile("imago-export-", ".jpg", context.cacheDir)
        var bitmap: Bitmap? = null
        try {
            onPhase(uiText(Res.string.editor_phase_reading))
            library.downloadOriginal(asset.id, source)
            onPhase(uiText(Res.string.editor_phase_decoding))
            val exif = runCatching { ExifInterface(source.absolutePath) }.getOrNull()
            bitmap = decodeMutable(source)
            bitmap = applyExifOrientation(checkNotNull(bitmap), exif)
            val parameters = recipe.toRenderParameters()
            // The dimensions of the image already oriented by EXIF, captured before the geometry throws
            // them away: this is the space the masks are defined in, and after the crop there is no
            // way to rebuild it.
            val oriented = checkNotNull(bitmap)
            val geometry = parameters.frameGeometry(oriented.width, oriented.height)
            bitmap = applyGeometry(oriented, recipe.activeGeometry())
            val working = checkNotNull(bitmap)
            onPhase(uiText(Res.string.editor_phase_processing, working.width, working.height))
            withContext(Dispatchers.Default) {
                BitmapPhotoProcessor.renderInPlace(working, parameters, geometry)
            }
            onPhase(uiText(Res.string.editor_phase_encoding))
            withContext(Dispatchers.IO) {
                FileOutputStream(output).buffered().use { stream ->
                    if (!working.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                        throw LocalizedException(uiText(Res.string.editor_jpeg_encode_failed_android))
                    }
                }
                copyExif(exif, output, working.width, working.height)
            }
            return output
        } catch (error: OutOfMemoryError) {
            output.delete()
            throw LocalizedException(uiText(Res.string.editor_out_of_memory), error)
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            bitmap?.recycle()
            source.delete()
        }
    }

    private fun decodeMutable(source: File): Bitmap {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(source.absolutePath, options)
            ?: throw LocalizedException(uiText(Res.string.editor_format_unsupported_android))
    }

    private fun applyExifOrientation(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val oriented = if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
        if (oriented.isMutable) return oriented
        return oriented.copy(Bitmap.Config.ARGB_8888, true).also { oriented.recycle() }
    }

    private fun applyGeometry(bitmap: Bitmap, geometry: Geometry): Bitmap {
        var working = bitmap
        val rotation = ((geometry.rotation % 360) + 360) % 360
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                working,
                0,
                0,
                working.width,
                working.height,
                Matrix().apply { setRotate(rotation.toFloat()) },
                true,
            )
            if (rotated !== working) working.recycle()
            working = rotated
        }
        if (geometry.mirrorH || geometry.mirrorV) {
            val mirrored = Bitmap.createBitmap(
                working,
                0,
                0,
                working.width,
                working.height,
                Matrix().apply {
                    setScale(if (geometry.mirrorH) -1f else 1f, if (geometry.mirrorV) -1f else 1f)
                },
                true,
            )
            if (mirrored !== working) working.recycle()
            working = mirrored
        }
        val straighten = geometry.straighten.coerceIn(-45f, 45f)
        if (straighten != 0f) {
            val straightened = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
            val scale = straightenCoverScale(working.width.toFloat() / working.height, straighten)
            AndroidCanvas(straightened).apply {
                translate(working.width / 2f, working.height / 2f)
                scale(scale, scale)
                rotate(straighten)
                translate(-working.width / 2f, -working.height / 2f)
                drawBitmap(
                    working,
                    0f,
                    0f,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
            working.recycle()
            working = straightened
        }
        val crop = geometry.cropRect
        val left = (crop.x.coerceIn(0f, 0.9999f) * working.width).roundToInt()
            .coerceAtMost(working.width - 1)
        val top = (crop.y.coerceIn(0f, 0.9999f) * working.height).roundToInt()
            .coerceAtMost(working.height - 1)
        val width = (crop.w.coerceIn(0.0001f, 1f - left.toFloat() / working.width) * working.width)
            .roundToInt()
            .coerceIn(1, working.width - left)
        val height = (crop.h.coerceIn(0.0001f, 1f - top.toFloat() / working.height) * working.height)
            .roundToInt()
            .coerceIn(1, working.height - top)
        if (left != 0 || top != 0 || width != working.width || height != working.height) {
            val cropped = Bitmap.createBitmap(working, left, top, width, height)
            if (cropped !== working) working.recycle()
            working = cropped
        }
        if (working.isMutable) return working
        return working.copy(Bitmap.Config.ARGB_8888, true).also { working.recycle() }
    }

    private fun copyExif(source: ExifInterface?, destination: File, width: Int, height: Int) {
        if (source == null) return
        val target = ExifInterface(destination.absolutePath)
        COPIED_EXIF_TAGS.forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        target.saveAttributes()
    }

    private companion object {
        val COPIED_EXIF_TAGS = listOf(
            "ApertureValue",
            "Artist",
            "BodySerialNumber",
            "CameraOwnerName",
            "Copyright",
            "DateTime",
            "DateTimeDigitized",
            "DateTimeOriginal",
            "DigitalZoomRatio",
            "ExposureBiasValue",
            "ExposureMode",
            "ExposureProgram",
            "ExposureTime",
            "FNumber",
            "Flash",
            "FocalLength",
            "FocalLengthIn35mmFilm",
            "GPSAltitude",
            "GPSAltitudeRef",
            "GPSDateStamp",
            "GPSLatitude",
            "GPSLatitudeRef",
            "GPSLongitude",
            "GPSLongitudeRef",
            "GPSProcessingMethod",
            "GPSTimeStamp",
            "ImageDescription",
            "ISOSpeedRatings",
            "LensMake",
            "LensModel",
            "LensSerialNumber",
            "LightSource",
            "Make",
            "Model",
            "Software",
            "SubSecTime",
            "SubSecTimeDigitized",
            "SubSecTimeOriginal",
            "UserComment",
            "WhiteBalance",
        )
    }
}
