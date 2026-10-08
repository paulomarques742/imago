package eu.studio742.imago.feature.library

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.model.MapMarker
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * MapLibre's own view, with OpenFreeMap's tiles. The groups are not MapLibre's clustering but
 * [clusterMarkers], worked out again whenever the camera stops: the same groups as on the
 * computer, and a tap knows which photos it holds without asking the map.
 */
@Composable
actual fun PhotoMap(
    markers: List<MapMarker>,
    camera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    onOpenPlace: (MarkerCluster) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var clusters by remember { mutableStateOf(emptyList<MarkerCluster>()) }
    val currentMarkers by rememberUpdatedState(markers)
    val reportCamera by rememberUpdatedState(onCameraChange)
    val openPlace by rememberUpdatedState(onOpenPlace)
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { ready ->
            ready.uiSettings.isRotateGesturesEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            ready.setMaxZoomPreference(MAX_MAP_ZOOM)
            camera?.let { ready.moveCamera(CameraUpdateFactory.newCameraPosition(it.toPosition())) }
            ready.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                style.addSource(GeoJsonSource(SOURCE))
                style.addLayer(
                    CircleLayer(CIRCLES, SOURCE).withProperties(
                        PropertyFactory.circleRadius(
                            Expression.interpolate(
                                Expression.linear(), Expression.get(COUNT),
                                Expression.stop(1, 9f), Expression.stop(10, 14f), Expression.stop(100, 20f), Expression.stop(1000, 26f),
                            ),
                        ),
                        PropertyFactory.circleColor(ImagoColors.Ivory.toArgb()),
                        PropertyFactory.circleStrokeColor(ImagoColors.BrandBlack.toArgb()),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                style.addLayer(
                    SymbolLayer(COUNTS, SOURCE).withProperties(
                        PropertyFactory.textField(Expression.toString(Expression.get(COUNT))),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(ImagoColors.BrandBlack.toArgb()),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    ),
                )
                map = ready
            }
            ready.addOnCameraIdleListener {
                val position = ready.cameraPosition
                val target = position.target ?: return@addOnCameraIdleListener
                reportCamera(MapCamera(target.latitude, target.longitude, position.zoom))
                clusters = clusterMarkers(currentMarkers, position.zoom)
            }
            ready.addOnMapClickListener { point ->
                val screen = ready.projection.toScreenLocation(point)
                val hit = ready.queryRenderedFeatures(screen, CIRCLES).firstOrNull()
                val index = hit?.getNumberProperty(INDEX)?.toInt()
                clusters.getOrNull(index ?: -1)?.let { openPlace(it); true } ?: false
            }
        }
    }

    // The first time the photos arrive with nowhere to look yet, the map fits them all.
    var fitted by remember { mutableStateOf(camera != null) }
    LaunchedEffect(map, markers) {
        val ready = map ?: return@LaunchedEffect
        if (!fitted && markers.isNotEmpty() && mapView.width > 0) {
            val density = context.resources.displayMetrics.density
            val fit = cameraFitting(markers, mapView.width / density.toDouble(), mapView.height / density.toDouble())
            ready.moveCamera(CameraUpdateFactory.newCameraPosition(fit.toPosition()))
            fitted = true
        }
        clusters = clusterMarkers(markers, ready.cameraPosition.zoom)
    }
    LaunchedEffect(map, clusters) {
        val source = map?.style?.getSourceAs<GeoJsonSource>(SOURCE) ?: return@LaunchedEffect
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                clusters.mapIndexed { index, cluster ->
                    Feature.fromGeometry(Point.fromLngLat(cluster.longitude, cluster.latitude)).apply {
                        addNumberProperty(INDEX, index)
                        addNumberProperty(COUNT, cluster.assetIds.size)
                    }
                },
            ),
        )
    }
}

private fun MapCamera.toPosition(): CameraPosition = CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(zoom).build()

private const val SOURCE = "imago-photos"
private const val CIRCLES = "imago-photo-circles"
private const val COUNTS = "imago-photo-counts"
private const val INDEX = "index"
private const val COUNT = "count"

@Composable
actual fun rememberLocationAccess(onGranted: () -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) granted() }
    return remember(context) {
        {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) granted()
            else launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
}
