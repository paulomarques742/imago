package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.asUiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.data.BrandKitRepository
import eu.studio742.imago.core.data.CompositionExportSnapshots
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import eu.studio742.imago.core.data.RecipeRepository
import java.io.File
import javax.inject.Inject

/**
 * The export on Android: a WorkManager job, with a notification, that survives leaving the app. The
 * project travels there in a snapshot, because the job runs outside this process.
 */
class WorkManagerCompositionExports @Inject constructor(
    @ApplicationContext context: Context,
    private val snapshots: CompositionExportSnapshots,
    private val exporter: CompositionProjectExporter,
) : CompositionExports {
    private val workManager = WorkManager.getInstance(context)

    override suspend fun export(
        project: CompositionProject,
        format: StaticExportFormat,
        pageIndex: Int?,
        targetLibraryId: String?,
        onProgress: (Int, Int) -> Unit,
    ): CompositionExportOutcome {
        val total = if (pageIndex != null) 1 else project.pages.size
        val snapshotId = snapshots.save(project)
        val request = OneTimeWorkRequestBuilder<CompositionExportWorker>().setInputData(
            workDataOf(
                CompositionExportWorker.KEY_PROJECT_ID to project.id,
                CompositionExportWorker.KEY_SNAPSHOT_ID to snapshotId,
                CompositionExportWorker.KEY_FORMAT to format.name,
                CompositionExportWorker.KEY_PAGE_INDEX to (pageIndex ?: -1),
                CompositionExportWorker.KEY_TARGET_LIBRARY to targetLibraryId,
            ),
        ).build()
        workManager.enqueueUniqueWork(CompositionExportWorker.uniqueName(project.id), ExistingWorkPolicy.REPLACE, request)
        val finalInfo = workManager.getWorkInfoByIdFlow(request.id)
            .filterNotNull()
            .onEach { info ->
                onProgress(
                    info.progress.getInt(CompositionExportWorker.KEY_COMPLETED, 0),
                    info.progress.getInt(CompositionExportWorker.KEY_TOTAL, total),
                )
            }
            .first { it.state.isFinished }
        snapshots.delete(snapshotId)
        return when (finalInfo.state) {
            WorkInfo.State.SUCCEEDED -> CompositionExportOutcome(
                locations = finalInfo.outputData.getStringArray(CompositionExportWorker.KEY_URIS).orEmpty().toList(),
                mimeTypes = finalInfo.outputData.getStringArray(CompositionExportWorker.KEY_MIME_TYPES).orEmpty().toList(),
                immichUploadFailures = finalInfo.outputData.getInt(CompositionExportWorker.KEY_UPLOAD_FAILURES, 0),
            )
            WorkInfo.State.CANCELLED -> throw CancellationException("export cancelled")
            // The worker already wrote the sentence in the app language; without it, the generic one stays.
            else -> throw LocalizedException(
                finalInfo.outputData.getString(CompositionExportWorker.KEY_ERROR)?.asUiText()
                    ?: uiText(Res.string.composer_export_failed),
            )
        }
    }

    override fun cancel(projectId: String) {
        workManager.cancelUniqueWork(CompositionExportWorker.uniqueName(projectId))
    }

    override suspend fun previewPage(project: CompositionProject, pageIndex: Int): File =
        exporter.previewPage(project, pageIndex)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ComposerBindings {
    @Binds abstract fun exports(implementation: WorkManagerCompositionExports): CompositionExports
}

@HiltViewModel
class HiltComposerEditorViewModel @Inject constructor(
    projects: CompositionRepository,
    templates: CompositionTemplateRepository,
    mediaRepository: CompositionMediaRepository,
    recipes: RecipeRepository,
    exports: CompositionExports,
    brandKits: BrandKitRepository,
) : ComposerEditorViewModel(projects, templates, mediaRepository, recipes, exports, brandKits)

@HiltViewModel
class HiltComposerHubViewModel @Inject constructor(
    projects: CompositionRepository,
    templates: CompositionTemplateRepository,
    mediaRepository: CompositionMediaRepository,
) : ComposerHubViewModel(projects, templates, mediaRepository)

@HiltViewModel
class HiltAddToCompositionViewModel @Inject constructor(
    projects: CompositionRepository,
    recipes: RecipeRepository,
) : AddToCompositionViewModel(projects, recipes)

@HiltViewModel
class HiltBrandKitViewModel @Inject constructor(
    brandKits: BrandKitRepository,
    mediaRepository: CompositionMediaRepository,
) : BrandKitViewModel(brandKits, mediaRepository)

@Composable
actual fun composerEditorViewModel(): ComposerEditorViewModel = hiltViewModel<HiltComposerEditorViewModel>()

@Composable
actual fun composerHubViewModel(): ComposerHubViewModel = hiltViewModel<HiltComposerHubViewModel>()

@Composable
actual fun addToCompositionViewModel(): AddToCompositionViewModel = hiltViewModel<HiltAddToCompositionViewModel>()

@Composable
actual fun brandKitViewModel(): BrandKitViewModel = hiltViewModel<HiltBrandKitViewModel>()

/** The exported pages go to the share menu, as before. */
@Composable
actual fun rememberShareExports(): (List<String>, List<String>) -> Unit {
    val context = LocalContext.current
    val chooserTitle = stringResource(Res.string.composer_share_title)
    return remember(context, chooserTitle) {
        { locations, mimeTypes ->
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (mimeTypes.any { it.startsWith("video/") }) "*/*" else "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(locations.map(Uri::parse)))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
        }
    }
}

@Composable
actual fun ProxyVideoPlayer(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose(player::release) }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = modifier,
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun ElementVideoPlayer(
    element: CompositionElement.Video,
    url: String,
    apiKey: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // The player survives changes to `timing`: having it in the `remember` key recreated it (and
    // reloaded the video) on every tap of the trim buttons. What changes is applied through an effect.
    val player = remember(element.id, url, apiKey) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(if (apiKey.isBlank()) emptyMap() else mapOf("x-api-key" to apiKey))
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, http))).build()
    }
    LaunchedEffect(player, element.timing.trimStartMs, element.timing.trimEndMs, url) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(element.timing.trimStartMs)
            .setEndPositionMs(element.timing.trimEndMs)
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(url).setClippingConfiguration(clipping).build())
        player.prepare()
    }
    LaunchedEffect(player, element.timing.muted, element.timing.volume, element.timing.loop) {
        player.volume = if (element.timing.muted) 0f else element.timing.volume
        player.repeatMode = if (element.timing.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    DisposableEffect(player) { onDispose(player::release) }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        update = { it.player = player },
        modifier = modifier,
    )
}
