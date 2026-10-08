package eu.studio742.imago.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.MapMarker
import eu.studio742.imago.feature.library.resources.Res
import eu.studio742.imago.feature.library.resources.library_map_failed
import eu.studio742.imago.feature.library.resources.library_map_zoom_in
import eu.studio742.imago.feature.library.resources.library_map_zoom_out
import eu.studio742.imago.feature.library.resources.library_retry
import eu.studio742.imago.feature.library.vectormap.MapResources
import eu.studio742.imago.feature.library.vectormap.MapSetup
import eu.studio742.imago.feature.library.vectormap.TileStore
import eu.studio742.imago.feature.library.vectormap.VectorMapRenderer
import eu.studio742.imago.feature.library.vectormap.Viewport
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow

/**
 * The computer's map: OpenFreeMap's vector tiles drawn by [VectorMapRenderer], following the same
 * style the phone's MapLibre does. Dragging moves it, the wheel and a double click zoom where the
 * pointer is, and a click on a group opens its photos.
 */
@Composable
actual fun PhotoMap(
    markers: List<MapMarker>,
    camera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    onOpenPlace: (MarkerCluster) -> Unit,
    modifier: Modifier,
) {
    val graph = LocalDesktopDataGraph.current
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    val resources = remember { MapResources(graph.root.resolve("map-cache")) }
    var setup by remember { mutableStateOf<MapSetup?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var redraw by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        runCatching { resources.load(MAP_STYLE_URL, density) }.onSuccess { setup = it }.onFailure { failed = true }
    }
    val store = remember(setup) { setup?.let { TileStore(resources, it.tileTemplate) { redraw++ } } }
    DisposableEffect(store) { onDispose { store?.close() } }
    val renderer = remember(setup, store) { setup?.let { ready -> store?.let { VectorMapRenderer(ready, it, textMeasurer) } } }

    var size by remember { mutableStateOf(IntSize.Zero) }
    var viewport by remember {
        mutableStateOf(camera?.let { Viewport(mercatorX(it.longitude), mercatorY(it.latitude), it.zoom) } ?: Viewport(0.5, 0.4, 1.0))
    }
    var fitted by remember { mutableStateOf(camera != null) }
    LaunchedEffect(markers, size) {
        if (!fitted && markers.isNotEmpty() && size != IntSize.Zero) {
            val fit = cameraFitting(markers, size.width / density.toDouble(), size.height / density.toDouble())
            viewport = Viewport(mercatorX(fit.longitude), mercatorY(fit.latitude), fit.zoom)
            fitted = true
        }
    }
    // Where it was left goes up once the map stops moving, not at every frame of a drag.
    val reportCamera by rememberUpdatedState(onCameraChange)
    LaunchedEffect(viewport) {
        delay(CAMERA_REPORT_DELAY_MS)
        reportCamera(MapCamera(latitudeOf(viewport.centerY), longitudeOf(viewport.centerX), viewport.zoom))
    }
    val clusterZoom = (viewport.zoom * 4).toInt() / 4.0
    val clusters = remember(markers, clusterZoom) { clusterMarkers(markers, clusterZoom) }
    val currentClusters by rememberUpdatedState(clusters)
    val openPlace by rememberUpdatedState(onOpenPlace)

    fun worldPx(zoom: Double) = TILE_SIZE * density * 2.0.pow(zoom)
    fun screenOf(cluster: MarkerCluster): Offset {
        val world = worldPx(viewport.zoom)
        var dx = (mercatorX(cluster.longitude) - viewport.centerX)
        if (dx > 0.5) dx -= 1.0
        if (dx < -0.5) dx += 1.0
        return Offset((dx * world + size.width / 2).toFloat(), ((mercatorY(cluster.latitude) - viewport.centerY) * world + size.height / 2).toFloat())
    }
    fun zoomAround(delta: Double, point: Offset) {
        val zoom = (viewport.zoom + delta).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        val before = worldPx(viewport.zoom)
        val after = worldPx(zoom)
        val pointX = viewport.centerX + (point.x - size.width / 2) / before
        val pointY = viewport.centerY + (point.y - size.height / 2) / before
        viewport = Viewport(
            (pointX - (point.x - size.width / 2) / after).mod(1.0),
            (pointY - (point.y - size.height / 2) / after).coerceIn(0.0, 1.0),
            zoom,
        )
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val world = worldPx(viewport.zoom)
                        viewport = viewport.copy(
                            centerX = (viewport.centerX - drag.x / world).mod(1.0),
                            centerY = (viewport.centerY - drag.y / world).coerceIn(0.0, 1.0),
                        )
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val change = event.changes.first()
                                zoomAround(-change.scrollDelta.y * WHEEL_ZOOM_STEP, change.position)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { zoomAround(1.0, it) },
                        onTap = { point ->
                            currentClusters.minByOrNull { (screenOf(it) - point).getDistance() }
                                ?.takeIf { (screenOf(it) - point).getDistance() <= radiusOf(it.assetIds.size) * density + TAP_SLOP * density }
                                ?.let(openPlace)
                        },
                    )
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") redraw
            if (renderer == null) drawRect(ImagoColors.Background) else renderer.draw(this, viewport)
            for (cluster in clusters) {
                val center = screenOf(cluster)
                val radius = radiusOf(cluster.assetIds.size) * density
                if (center.x < -radius || center.y < -radius || center.x > size.width + radius || center.y > size.height + radius) continue
                // Dark on the light map, with a white edge for where a street runs dark underneath.
                drawCircle(ImagoColors.BrandBlack, radius, center)
                drawCircle(Color.White, radius, center, style = Stroke(width = 2 * density))
                val count = textMeasurer.measure(cluster.assetIds.size.toString(), TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
                drawText(count, color = ImagoColors.Ivory, topLeft = center - Offset(count.size.width / 2f, count.size.height / 2f))
            }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(ImagoSpacing.Md).clip(RoundedCornerShape(ImagoRadii.Small)).background(Color.Black.copy(alpha = 0.55f)),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = { zoomAround(1.0, Offset(size.width / 2f, size.height / 2f)) }) {
                Icon(Icons.Outlined.Add, stringResource(Res.string.library_map_zoom_in), tint = ImagoColors.TextPrimary)
            }
            IconButton(onClick = { zoomAround(-1.0, Offset(size.width / 2f, size.height / 2f)) }) {
                Icon(Icons.Outlined.Remove, stringResource(Res.string.library_map_zoom_out), tint = ImagoColors.TextPrimary)
            }
        }
        Text(
            MAP_ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextSecondary,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (failed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.library_map_failed), color = ImagoColors.TextSecondary)
                TextButton(onClick = { attempt++ }) { Text(stringResource(Res.string.library_retry)) }
            }
        }
    }
}

/** The size of a group's circle, in dp: the same steps as on the phone. */
private fun radiusOf(count: Int): Float {
    val stops = listOf(1 to 9f, 10 to 14f, 100 to 20f, 1000 to 26f)
    if (count <= stops.first().first) return stops.first().second
    if (count >= stops.last().first) return stops.last().second
    val upper = stops.indexOfFirst { it.first > count }
    val (c0, r0) = stops[upper - 1]
    val (c1, r1) = stops[upper]
    return r0 + (r1 - r0) * (count - c0) / (c1 - c0).toFloat()
}

private const val CAMERA_REPORT_DELAY_MS = 400L
private const val WHEEL_ZOOM_STEP = 0.5
private const val TAP_SLOP = 6f
