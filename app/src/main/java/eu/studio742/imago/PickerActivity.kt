package eu.studio742.imago

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.FileProvider
import dagger.hilt.android.AndroidEntryPoint
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.designsystem.ImagoTheme
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.i18n.ProvideAppLanguage
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.feature.editor.EditorAsset
import eu.studio742.imago.feature.editor.EditorExporter
import eu.studio742.imago.feature.library.AssetUiModel
import eu.studio742.imago.feature.library.PickForOtherAppRoute
import eu.studio742.imago.feature.library.pickerAccepts
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Another app asking for a photo or a video (`GET_CONTENT`, `PICK`): the library, choosing, and the
 * chosen files handed back. A photo of the phone without edits goes as itself, by its MediaStore
 * `Uri`; a server's, and any edited version, are prepared in the cache and handed over by the
 * FileProvider. The read permission travels with the answer.
 */
@AndroidEntryPoint
class PickerActivity : ComponentActivity() {
    @Inject lateinit var languageSettings: AndroidLanguageSettings
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var exporter: EditorExporter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        languageSettings.refresh()
        val media = pickerAccepts(listOf(intent.type) + intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty())
        val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        val caller = callingActivity?.packageName?.let { name ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString() }.getOrNull()
        }
        setContent {
            val preference by languageSettings.preference.collectAsState()
            val systemLocale = LocalConfiguration.current.locales[0]
            CompositionLocalProvider(LocalLanguageSettings provides languageSettings) {
                ProvideAppLanguage(preference, systemLocale) {
                    ImagoTheme {
                        PickForOtherAppRoute(
                            caller = caller,
                            multiple = multiple,
                            allowsPhotos = media.photos,
                            allowsVideo = media.videos,
                            onPicked = ::deliver,
                            onCancel = {
                                setResult(RESULT_CANCELED)
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }

    private suspend fun deliver(assets: List<AssetUiModel>, edited: Boolean, progress: (Int, Int) -> Unit) {
        // Emptied on every pick: what the last app received it has already read, or never will.
        val folder = withContext(Dispatchers.IO) { File(cacheDir, "picked").apply { deleteRecursively(); mkdirs() } }
        val taken = mutableSetOf<String>()
        val picked = assets.mapIndexed { index, asset ->
            progress(index, assets.size)
            prepare(asset, edited, folder, taken)
        }
        progress(assets.size, assets.size)
        val clip = ClipData(
            ClipDescription("IMAGO", picked.map { it.second }.distinct().toTypedArray()),
            ClipData.Item(picked.first().first),
        ).apply { picked.drop(1).forEach { addItem(ClipData.Item(it.first)) } }
        setResult(
            RESULT_OK,
            Intent().apply {
                // A single pick reads the data; several, the ClipData. Both are given.
                data = picked.first().first
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        finish()
    }

    /** One chosen asset as a `Uri` the other app can read, with its type. */
    private suspend fun prepare(asset: AssetUiModel, edited: Boolean, folder: File, taken: MutableSet<String>): Pair<Uri, String> {
        val reference = AssetReference.parse(asset.id)
        val recipe = asset.recipe?.takeIf { edited && !asset.isVideo }
        val type = if (asset.isVideo) "video/*" else "image/*"
        if (recipe == null && reference.libraryId == DEVICE_LIBRARY_ID) return Uri.parse(reference.localId) to type
        val file = if (recipe != null) {
            val target = EditorAsset(asset.id, asset.checksum, asset.fileName, asset.previewUrl, asset.apiKey, asset.fileCreatedAt)
            val jpeg = exporter.renderJpeg(target, recipe) {}
            withContext(Dispatchers.IO) {
                File(folder, uniqueName(asset.fileName.substringBeforeLast('.') + ".jpg", taken)).also { jpeg.copyTo(it); jpeg.delete() }
            }
        } else {
            File(folder, uniqueName(asset.fileName, taken)).also { library.downloadOriginal(asset.id, it) }
        }
        return FileProvider.getUriForFile(this, "$packageName.share", file) to (if (recipe != null) "image/jpeg" else type)
    }

    /** Two photos named IMG_0001.JPG, from different folders, cannot both be that file here. */
    private fun uniqueName(name: String, taken: MutableSet<String>): String {
        val base = name.ifBlank { "IMAGO" }
        val stem = base.substringBeforeLast('.', base)
        val extension = base.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = base
        var counter = 2
        while (!taken.add(candidate.lowercase())) candidate = "$stem (${counter++})$extension"
        return candidate
    }
}
