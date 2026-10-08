package eu.studio742.imago.feature.library

import eu.studio742.imago.core.model.MapMarker
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Where the map looks: the centre and the zoom, counted as MapLibre counts it — at zoom z the world
 * is [TILE_SIZE] × 2^z density-independent pixels wide.
 */
data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

/** Photos close enough on the screen to be one point. [city] is the one most of them name. */
data class MarkerCluster(val latitude: Double, val longitude: Double, val assetIds: List<String>, val city: String?)

/** The width of the world at zoom 0, as the vector tiles are drawn. */
const val TILE_SIZE = 512.0

const val MIN_MAP_ZOOM = 0.0
const val MAX_MAP_ZOOM = 18.0

/** Mercator stops at the latitude where the world becomes a square. */
private const val MAX_LATITUDE = 85.05112878

/** 0 at the antimeridian on the west, 1 on the east. */
fun mercatorX(longitude: Double): Double = (longitude + 180.0) / 360.0

/** 0 at the top of the world, 1 at the bottom. */
fun mercatorY(latitude: Double): Double {
    val sine = sin(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180.0)
    return 0.5 - ln((1 + sine) / (1 - sine)) / (4 * PI)
}

fun longitudeOf(x: Double): Double = x * 360.0 - 180.0

fun latitudeOf(y: Double): Double = atan(sinh(PI * (1 - 2 * y))) * 180.0 / PI

fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

/**
 * The markers that fall in the same cell of [cellSize] screen pixels at [zoom] become one point,
 * at their average place. A grid, and not distances between points, so that the same zoom always
 * gives the same groups, whatever was on the screen before.
 */
fun clusterMarkers(markers: List<MapMarker>, zoom: Double, cellSize: Double = CLUSTER_CELL): List<MarkerCluster> {
    val world = worldSize(zoom)
    return markers
        .groupBy { floor(mercatorX(it.longitude) * world / cellSize).toLong() to floor(mercatorY(it.latitude) * world / cellSize).toLong() }
        .values
        .map { group ->
            MarkerCluster(
                latitude = group.sumOf { it.latitude } / group.size,
                longitude = group.sumOf { it.longitude } / group.size,
                assetIds = group.map { it.assetId },
                city = group.mapNotNull { it.city }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            )
        }
}

/** The camera that shows every marker in a view of [width] × [height] pixels, with a margin. */
fun cameraFitting(markers: List<MapMarker>, width: Double, height: Double): MapCamera {
    if (markers.isEmpty()) return MapCamera(30.0, 0.0, 1.0)
    val xs = markers.map { mercatorX(it.longitude) }
    val ys = markers.map { mercatorY(it.latitude) }
    val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9)
    val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
    val zoom = log2(min(width * FIT_MARGIN / (spanX * TILE_SIZE), height * FIT_MARGIN / (spanY * TILE_SIZE)))
        .coerceIn(MIN_MAP_ZOOM, FIT_MAX_ZOOM)
    return MapCamera(latitudeOf((ys.max() + ys.min()) / 2), longitudeOf((xs.max() + xs.min()) / 2), zoom)
}

/** About a thumbnail: closer than this, two places would be two circles on top of each other. */
const val CLUSTER_CELL = 64.0

private const val FIT_MARGIN = 0.8

/** One photo alone should not open the map at street level. */
private const val FIT_MAX_ZOOM = 14.0
