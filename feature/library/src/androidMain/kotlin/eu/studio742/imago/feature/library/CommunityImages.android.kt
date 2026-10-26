package eu.studio742.imago.feature.library

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import eu.studio742.imago.core.sync.ForumImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal actual fun rememberCommunityImages(onImages: (List<ForumImage>) -> Unit, onFailure: () -> Unit, onBusy: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val receive by rememberUpdatedState(onImages)
    val fail by rememberUpdatedState(onFailure)
    val busy by rememberUpdatedState(onBusy)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try {
                val images = withContext(Dispatchers.IO) { uris.map { uri ->
                    // ImageDecoder applies orientation before resampling and never copies source metadata.
                    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    }
                    try {
                        val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                        try {
                            android.graphics.Canvas(flattened).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                            val bytes = (82 downTo 40 step 10).firstNotNullOfOrNull { quality ->
                                ByteArrayOutputStream().use { output ->
                                    check(flattened.compress(Bitmap.CompressFormat.JPEG, quality, output))
                                    output.toByteArray().takeIf { it.size <= 1_000_000 }
                                }
                            } ?: error("Image cannot fit the forum limit")
                            ForumImage(bytes)
                        } finally { flattened.recycle() }
                    } finally { decoded.recycle() }
                } }
                receive(images)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
            finally { busy(false) }
        } else busy(false)
    }
    return {
        busy(true)
        try { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { busy(false); fail() }
    }
}
