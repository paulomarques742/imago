package eu.studio742.imago.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.backhandler.BackHandler
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.model.MapMarker
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The map of the open library. [PhotoMap] is each platform's; what is said around it — that the
 * places are still being read, that Android hides them, that there are none — is the same on both.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PhotoMapScreen(
    state: LibraryUiState,
    onBack: () -> Unit,
    onOpenPlace: (MarkerCluster) -> Unit,
    onCameraChange: (MapCamera) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val contents = state.map
    val allowLocations = rememberLocationAccess(onGranted = onRetry)
    Box(modifier.fillMaxSize().background(ImagoColors.Background)) {
        PhotoMap(
            markers = contents?.markers.orEmpty(),
            camera = state.mapCamera,
            onCameraChange = onCameraChange,
            onOpenPlace = onOpenPlace,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.library_map_close), tint = ImagoColors.TextPrimary)
            }
            Text(stringResource(Res.string.library_map), style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary)
        }
        val reading = contents?.reading
        val note: (@Composable () -> Unit)? = when {
            state.mapError != null -> {
                {
                    MapNote(state.mapError.resolve()) { TextButton(onClick = onRetry) { Text(stringResource(Res.string.library_retry)) } }
                }
            }
            contents?.needsLocationAccess == true -> {
                {
                    MapNote(stringResource(Res.string.library_map_location_hidden)) {
                        allowLocations?.let { TextButton(onClick = it) { Text(stringResource(Res.string.library_map_location_allow)) } }
                    }
                }
            }
            reading != null -> {
                {
                    MapNote(pluralStringResource(Res.plurals.library_map_reading, reading.total, reading.done, reading.total)) {}
                    LinearProgressIndicator(
                        progress = { reading.done.toFloat() / reading.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(top = ImagoSpacing.Sm),
                        color = ImagoColors.Ivory,
                    )
                }
            }
            contents != null && contents.markers.isEmpty() -> {
                { MapNote(stringResource(Res.string.library_map_empty)) {} }
            }
            else -> null
        }
        note?.let {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(ImagoSpacing.Lg)
                    .clip(RoundedCornerShape(ImagoRadii.Medium))
                    .background(ImagoColors.SurfaceElevated.copy(alpha = 0.94f))
                    .padding(ImagoSpacing.Lg),
            ) { it() }
        }
    }
}

@Composable
private fun MapNote(text: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary, modifier = Modifier.weight(1f))
        action()
    }
}

/**
 * The map itself, with the photos as groups that come apart as one zooms in. [camera] is where to
 * look on arrival — null fits every marker — and [onCameraChange] says where it was left, so that
 * coming back from a place returns to the same view.
 */
@Composable
expect fun PhotoMap(
    markers: List<MapMarker>,
    camera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    onOpenPlace: (MarkerCluster) -> Unit,
    modifier: Modifier = Modifier,
)

/** Asks for the places Android hides in the files; null where nothing hides them. */
@Composable
expect fun rememberLocationAccess(onGranted: () -> Unit): (() -> Unit)?

/** What the map's tiles must credit, wherever the map is drawn. */
internal const val MAP_ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"

/** OpenFreeMap's "bright": colour reads at a glance where the dark style was all black. */
internal const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/bright"

/** The same style, for the snapshots the desktop tests draw. */
const val MAP_STYLE_URL_FOR_TESTS = MAP_STYLE_URL
