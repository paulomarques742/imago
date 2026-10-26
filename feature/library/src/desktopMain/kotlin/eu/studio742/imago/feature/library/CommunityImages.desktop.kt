package eu.studio742.imago.feature.library

import androidx.compose.runtime.*
import eu.studio742.imago.core.sync.ForumImage
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
internal actual fun rememberCommunityImages(onImages: (List<ForumImage>) -> Unit, onFailure: () -> Unit, onBusy: (Boolean) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val receive by rememberUpdatedState(onImages)
    val fail by rememberUpdatedState(onFailure)
    val busy by rememberUpdatedState(onBusy)
    val title = stringResource(Res.string.community_add_images)
    var picking by remember { mutableStateOf(false) }
    return {
        if (!picking) {
            picking = true
            busy(true)
            scope.launch {
                try {
                    val files = withContext(Dispatchers.IO) {
                        var selected = emptyList<File>()
                        java.awt.EventQueue.invokeAndWait {
                            FileDialog(null as Frame?, title, FileDialog.LOAD).let { dialog ->
                                try { dialog.isMultipleMode = true; dialog.isVisible = true; selected = dialog.files.toList() }
                                finally { dialog.dispose() }
                            }
                        }
                        selected
                    }
                    if (files.isNotEmpty()) {
                        check(files.size <= 4)
                        receive(withContext(Dispatchers.IO) { files.map(::prepareCommunityImage) })
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fail() }
                finally { picking = false; busy(false) }
            }
        }
    }
}

internal fun prepareCommunityImage(file: File): ForumImage {
    require(file.length() in 1..32_000_000)
    Image.makeFromEncoded(file.readBytes()).use { image ->
        require(image.width.toLong() * image.height <= 64_000_000)
        val scale = minOf(1.0, 2048.0 / maxOf(image.width, image.height))
        val width = maxOf(1, (image.width * scale).toInt())
        val height = maxOf(1, (image.height * scale).toInt())
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(Color.WHITE)
            surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
            surface.makeImageSnapshot().use { reduced ->
                for (quality in 82 downTo 40 step 10) {
                    reduced.encodeToData(EncodedImageFormat.JPEG, quality)?.use { data ->
                        if (data.size <= 1_000_000) return ForumImage(data.bytes)
                    }
                }
            }
        }
    }
    error("Image cannot fit the forum limit")
}
