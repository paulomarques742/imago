package eu.studio742.imago.feature.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import eu.studio742.imago.core.interchange.RECIPE_FILE_MAX_BYTES
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.ByteArrayOutputStream
import java.io.File

/** What IMAGO sends its files as, and one of the types it answers to. */
private const val RECIPE_FILE_TYPE = "application/vnd.imago+json"

/** The file in the cache that waits for the system's "save as", so that a rotation does not lose it. */
private const val PENDING_SAVE = "recipes-pending-save.imago"

private fun Context.shareFolder() = File(cacheDir, "share").apply { mkdirs() }

/** What a file manager or a messenger calls a file whose extension Android does not know. */
private const val UNKNOWN_FILE_TYPE = "application/octet-stream"

/** The files an intent brings, or nothing when it is not about recipe files. */
fun Intent.recipeFileUris(context: Context): List<Uri> {
    if (resolveType(context.contentResolver) !in setOf(RECIPE_FILE_TYPE, UNKNOWN_FILE_TYPE)) return emptyList()
    return when (action) {
        Intent.ACTION_VIEW -> listOfNotNull(data)
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}

suspend fun readRecipeFiles(context: Context, uris: List<Uri>): List<RecipeFile> = withContext(Dispatchers.IO) {
    uris.map { uri -> RecipeFile(displayName(context, uri), readLimited(context, uri)) }
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()

private fun readLimited(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > RECIPE_FILE_MAX_BYTES) return@use null
        }
        out.toByteArray()
    }
}.getOrNull()

@Composable
internal actual fun rememberRecipeFileActions(
    onOpened: (List<RecipeFile>) -> Unit,
    onFailed: () -> Unit,
): RecipeFileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val opened by rememberUpdatedState(onOpened)
    val failed by rememberUpdatedState(onFailed)
    val chooserTitle = stringResource(Res.string.editor_recipe_share)
    val currentChooserTitle by rememberUpdatedState(chooserTitle)

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { opened(readRecipeFiles(context, uris)) }
    }
    // The generic type keeps the name as it was given: a provider that knows a type may add its own extension.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                            context.shareFolder().resolve(PENDING_SAVE).inputStream().use { it.copyTo(output) }
                        }
                    }.isSuccess
                }
                if (!written) failed()
            }
        }
    }

    return remember(context, scope, openLauncher, saveLauncher) {
        RecipeFileActions(
            share = { fileName, bytes ->
                scope.launch {
                    runCatching {
                        val file = withContext(Dispatchers.IO) { context.shareFolder().resolve(fileName).apply { writeBytes(bytes) } }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = RECIPE_FILE_TYPE
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                currentChooserTitle,
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { failed() }
                }
            },
            saveAs = { fileName, bytes ->
                scope.launch {
                    val ready = withContext(Dispatchers.IO) {
                        runCatching { context.shareFolder().resolve(PENDING_SAVE).writeBytes(bytes) }.isSuccess
                    }
                    if (ready) saveLauncher.launch(fileName) else failed()
                }
            },
            // Providers do not agree on a type for an unknown extension, so the picker is not narrowed by one.
            open = { openLauncher.launch(arrayOf("*/*")) },
        )
    }
}
