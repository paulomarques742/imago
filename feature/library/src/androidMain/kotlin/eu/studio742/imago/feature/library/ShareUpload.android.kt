package eu.studio742.imago.feature.library

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.SharedUploadBatch
import eu.studio742.imago.core.data.SharedUploadItem
import eu.studio742.imago.core.data.SharedUploadQueue
import eu.studio742.imago.core.data.SharedUploadStaging
import eu.studio742.imago.core.data.SharedUploadSummary
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.SheetHandle
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.asUiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.library.resources.*
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

sealed interface ShareUploadStage {
    /** Copying what the other app shared, while the permission over the files lasts. */
    data class Preparing(val copied: Int, val total: Int) : ShareUploadStage
    data object Ready : ShareUploadStage

    /** Nothing that came was a photo or a video that could be read. */
    data object Unreadable : ShareUploadStage

    /** Handed to WorkManager. [waiting]: it has not started yet — no network, or between attempts. */
    data class Sending(val completed: Int, val total: Int, val waiting: Boolean) : ShareUploadStage
    data class Finished(val summary: SharedUploadSummary, val libraryName: String) : ShareUploadStage
    data class Failed(val message: UiText) : ShareUploadStage
}

data class ShareUploadState(
    val photos: Int = 0,
    val videos: Int = 0,
    val stage: ShareUploadStage = ShareUploadStage.Preparing(0, 0),
)

/**
 * What another app shared with IMAGO, from when it arrives until it is handed to the upload.
 *
 * The files are copied right on arrival, while the person chooses the library: the permission to read
 * them belongs to the activity that received them and ends with it, and the upload ends long after.
 * Until they are handed over, the copy belongs to this ViewModel — if the person gives up, it is
 * deleted.
 */
@HiltViewModel
class ShareUploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val staging: SharedUploadStaging,
    private val configuration: ConfigurationRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ShareUploadState())
    val state: StateFlow<ShareUploadState> = mutableState.asStateFlow()

    private var directory: File? = null
    private var items: List<SharedUploadItem> = emptyList()
    private var copying: Job? = null
    private var handedOver = false

    /** Called on every creation of the activity; only the first one counts. */
    fun receive(uris: List<Uri>, intentType: String?) {
        if (directory != null) return
        val target = staging.newDirectory().also { directory = it }
        copying = viewModelScope.launch(Dispatchers.IO) {
            staging.cleanAbandoned()
            val resolver = context.contentResolver
            val media = uris.distinct().map { describe(resolver, it, intentType) }.filter { it.isPhotoOrVideo }
            mutableState.value = ShareUploadState(
                photos = media.count { it.isPhoto },
                videos = media.count { !it.isPhoto },
                stage = ShareUploadStage.Preparing(0, media.size),
            )
            val copied = mutableListOf<SharedUploadItem>()
            media.forEachIndexed { index, item ->
                val copy = File(target, index.toString())
                try {
                    copy(resolver, item.uri, copy)
                    copied += SharedUploadItem(copy.name, item.fileName, item.mimeType, item.createdAt)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unreadable: Exception) {
                    // A file the other app no longer serves is left out; the others go on.
                    copy.delete()
                }
                mutableState.update { it.copy(stage = ShareUploadStage.Preparing(index + 1, media.size)) }
            }
            items = copied
            mutableState.value = if (copied.isEmpty()) {
                ShareUploadState(stage = ShareUploadStage.Unreadable)
            } else {
                ShareUploadState(
                    photos = copied.count { it.mimeType.startsWith("image/") },
                    videos = copied.count { it.mimeType.startsWith("video/") },
                    stage = ShareUploadStage.Ready,
                )
            }
        }
    }

    fun send(libraryId: String) {
        val target = directory ?: return
        if (handedOver || state.value.stage != ShareUploadStage.Ready) return
        SharedUploadQueue.create(target, SharedUploadBatch(libraryId, items))
        handedOver = true
        configuration.lastExportLibraryId = libraryId
        val libraryName = configuration.source(libraryId).name
        val total = items.size
        val work = SharedUploadWorker.enqueue(context, target)
        mutableState.update { it.copy(stage = ShareUploadStage.Sending(0, total, waiting = true)) }
        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(work).filterNotNull().collect { info ->
                mutableState.update { it.copy(stage = info.toStage(it.stage, total, libraryName)) }
            }
        }
    }

    override fun onCleared() {
        if (!handedOver) {
            copying?.cancel()
            directory?.deleteRecursively()
        }
    }
}

private fun WorkInfo.toStage(current: ShareUploadStage, total: Int, libraryName: String): ShareUploadStage = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ShareUploadStage.Sending(
        completed = (current as? ShareUploadStage.Sending)?.completed ?: 0,
        total = total,
        waiting = true,
    )
    WorkInfo.State.RUNNING -> ShareUploadStage.Sending(
        completed = progress.getInt(SharedUploadWorker.KEY_COMPLETED, (current as? ShareUploadStage.Sending)?.completed ?: 0),
        total = progress.getInt(SharedUploadWorker.KEY_TOTAL, total),
        waiting = false,
    )
    WorkInfo.State.SUCCEEDED -> ShareUploadStage.Finished(
        SharedUploadSummary(
            uploaded = outputData.getInt(SharedUploadWorker.KEY_UPLOADED, 0),
            duplicates = outputData.getInt(SharedUploadWorker.KEY_DUPLICATES, 0),
            failed = outputData.getInt(SharedUploadWorker.KEY_FAILED, 0),
        ),
        libraryName,
    )
    // The worker already wrote the sentence in the app language; without it, the generic one stays.
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ShareUploadStage.Failed(
        outputData.getString(SharedUploadWorker.KEY_ERROR)?.asUiText() ?: uiText(Res.string.library_share_failed),
    )
}

private class SharedMedia(val uri: Uri, val fileName: String, val mimeType: String, val createdAt: String) {
    val isPhoto: Boolean get() = mimeType.startsWith("image/")
    val isPhotoOrVideo: Boolean get() = isPhoto || mimeType.startsWith("video/")
}

/**
 * The name, type and date of a shared file, with whatever the provider can say. The gallery
 * (MediaStore) says everything; a FileProvider usually only says the name. Immich reads the date from
 * EXIF when the file has it — the one sent here only counts for those that do not.
 */
private fun describe(resolver: ContentResolver, uri: Uri, intentType: String?): SharedMedia {
    var displayName: String? = null
    var takenMillis: Long? = null
    var modifiedSeconds: Long? = null
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                takenMillis = cursor.longOrNull(MediaStore.MediaColumns.DATE_TAKEN)
                modifiedSeconds = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)
            }
        }
    }
    val baseName = displayName?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "IMAGO_${System.currentTimeMillis()}"
    val mimes = MimeTypeMap.getSingleton()
    val mimeType = resolver.getType(uri)?.takeIf { it.isConcreteType() }
        ?: baseName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let(mimes::getMimeTypeFromExtension)
        ?: intentType?.takeIf { it.isConcreteType() }
        ?: intentType?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
        ?: "application/octet-stream"
    // Immich decides by the extension; a name without one is refused.
    val fileName = if ('.' in baseName) baseName else mimes.getExtensionFromMimeType(mimeType)?.let { "$baseName.$it" } ?: baseName
    val createdMillis = takenMillis?.takeIf { it > 0 }
        ?: modifiedSeconds?.takeIf { it > 0 }?.times(1000)
        ?: System.currentTimeMillis()
    return SharedMedia(uri, fileName, mimeType, Instant.ofEpochMilli(createdMillis).toString())
}

private fun String.isConcreteType(): Boolean = contains('/') && !endsWith("/*")

private fun Cursor.stringOrNull(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.longOrNull(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

/** Copies in chunks, so it can give up halfway through a large video when the person cancels. */
private suspend fun copy(resolver: ContentResolver, uri: Uri, destination: File) {
    val input = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    input.use { source ->
        destination.outputStream().use { sink ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
        }
    }
}

private const val COPY_BUFFER_BYTES = 256 * 1024

/**
 * The sheet that appears over the app the share came from: how many files, to which library, and the
 * upload running. Closing it after sending stops nothing — the upload belongs to WorkManager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareUploadSheet(onClose: () -> Unit, viewModel: ShareUploadViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var library by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // The permission is asked for when sending, which is when the notification has something to say.
    // Refused, the upload runs anyway; it just does not say when it finishes.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val send: () -> Unit = {
        // Before Android 13 notifications do not ask for permission.
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        library?.let(viewModel::send)
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = ImagoColors.Surface,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ImagoSpacing.Lg)
                .padding(bottom = ImagoSpacing.Lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
        ) {
            Text(stringResource(Res.string.library_share_title), style = MaterialTheme.typography.titleLarge)
            ShareCount(state.photos, state.videos)
            when (val stage = state.stage) {
                is ShareUploadStage.Preparing, ShareUploadStage.Ready -> {
                    ExportLibrarySelector(onSelected = { library = it })
                    if (stage is ShareUploadStage.Preparing && stage.total > 0) {
                        Text(
                            stringResource(Res.string.library_share_preparing, stage.copied, stage.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = ImagoColors.TextSecondary,
                        )
                        LinearProgressIndicator(
                            progress = { stage.copied / stage.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                ShareUploadStage.Unreadable -> Text(
                    stringResource(Res.string.library_share_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.TextSecondary,
                )
                is ShareUploadStage.Sending -> {
                    Text(
                        if (stage.waiting) stringResource(Res.string.library_share_waiting)
                        else pluralStringResource(Res.plurals.library_share_progress, stage.total, stage.completed, stage.total),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (stage.waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { stage.completed / stage.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(Res.string.library_share_background),
                        style = MaterialTheme.typography.bodySmall,
                        color = ImagoColors.TextTertiary,
                    )
                }
                is ShareUploadStage.Finished -> stage.summary.describe(stage.libraryName).forEach {
                    Text(it.resolve(), style = MaterialTheme.typography.bodyMedium)
                }
                is ShareUploadStage.Failed -> Text(
                    stage.message.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.Danger,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm, Alignment.End)) {
                when (state.stage) {
                    is ShareUploadStage.Preparing, ShareUploadStage.Ready -> {
                        TextButton(onClick = onClose) { Text(stringResource(Res.string.library_cancel)) }
                        Button(enabled = state.stage == ShareUploadStage.Ready && library != null, onClick = send) {
                            Text(stringResource(Res.string.library_share_send))
                        }
                    }
                    is ShareUploadStage.Sending -> TextButton(onClick = onClose) { Text(stringResource(Res.string.library_share_close)) }
                    is ShareUploadStage.Finished -> Button(onClick = onClose) { Text(stringResource(Res.string.library_share_done)) }
                    ShareUploadStage.Unreadable, is ShareUploadStage.Failed ->
                        Button(onClick = onClose) { Text(stringResource(Res.string.library_share_close)) }
                }
            }
        }
    }
}

@Composable
private fun ShareCount(photos: Int, videos: Int) {
    val text = when {
        photos > 0 && videos > 0 -> pluralStringResource(Res.plurals.library_share_media, photos + videos, photos + videos)
        videos > 0 -> pluralStringResource(Res.plurals.library_share_videos, videos, videos)
        photos > 0 -> pluralStringResource(Res.plurals.library_share_photos, photos, photos)
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
}
