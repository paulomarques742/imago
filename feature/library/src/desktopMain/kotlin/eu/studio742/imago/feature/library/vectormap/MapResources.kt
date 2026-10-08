package eu.studio742.imago.feature.library.vectormap

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The network side of the map: the style, the tiles and the sprite, with a disk cache. OpenFreeMap
 * lets its tiles be kept for ten years, so a place seen once draws again without the network.
 */
class MapResources(cacheDir: Path) {
    private val client = OkHttpClient.Builder()
        .cache(Cache(cacheDir.toFile(), CACHE_BYTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body.bytes()
        }
    }

    suspend fun text(url: String): String = bytes(url).toString(Charsets.UTF_8)

    /** The style, the tile template of its vector source and its sprite, ready to draw with. */
    suspend fun load(styleUrl: String, density: Float): MapSetup {
        val style = MapStyle.parse(text(styleUrl))
        val tileJson = Json.parseToJsonElement(text(checkNotNull(style.vectorSourceUrl) { "The style has no vector source" })).jsonObject
        val template = tileJson["tiles"]!!.let { (it as kotlinx.serialization.json.JsonArray).first().jsonPrimitive.content }
        val maxZoom = tileJson["maxzoom"]?.jsonPrimitive?.int ?: 14
        val sprite = style.spriteUrl?.let { base ->
            // Without the sprite the map still draws, only without its icons and the wood pattern.
            runCatching {
                val suffix = if (density >= 1.5f) "@2x" else ""
                Sprite.parse(text("$base$suffix.json"), bytes("$base$suffix.png"), if (suffix.isEmpty()) 1f else 2f)
            }.getOrNull()
        }
        return MapSetup(style, template, maxZoom, sprite)
    }

    private companion object {
        const val CACHE_BYTES = 512L * 1024 * 1024
        const val USER_AGENT = "IMAGO-desktop (https://github.com/paulomarques742/imago)"
    }
}

class MapSetup(val style: MapStyle, val tileTemplate: String, val maxZoom: Int, val sprite: Sprite?)

data class TileKey(val z: Int, val x: Int, val y: Int) {
    fun parent(): TileKey? = if (z == 0) null else TileKey(z - 1, x / 2, y / 2)
}

/**
 * The decoded tiles, the most recently drawn kept in memory. A tile is fetched once at a time; one
 * that failed is not asked for again in this session until [RETRY_MS] passed.
 */
class TileStore(
    private val resources: MapResources,
    private val template: String,
    private val onLoaded: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tiles = object : LinkedHashMap<TileKey, TileEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, TileEntry>) = size > MEMORY_TILES
    }
    private val pending = HashSet<TileKey>()
    private val failed = HashMap<TileKey, Long>()

    @Synchronized fun get(key: TileKey): TileEntry? = tiles[key]

    @Synchronized fun request(key: TileKey) {
        if (key in tiles || key in pending) return
        if ((failed[key] ?: 0L) > System.currentTimeMillis() - RETRY_MS) return
        pending += key
        scope.launch {
            val tile = runCatching {
                VectorTileDecoder.decode(resources.bytes(template.replace("{z}", "${key.z}").replace("{x}", "${key.x}").replace("{y}", "${key.y}")))
            }
            synchronized(this@TileStore) {
                pending -= key
                tile.onSuccess { tiles[key] = TileEntry(it) }.onFailure { failed[key] = System.currentTimeMillis() }
            }
            if (tile.isSuccess) onLoaded()
        }
    }

    fun close() = scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

    private companion object {
        const val MEMORY_TILES = 160
        const val RETRY_MS = 30_000L
    }
}

/** A decoded tile, and what was worked out to draw it, kept with it so both leave memory together. */
class TileEntry(val tile: VectorTile) {
    val drawCache = HashMap<Pair<Int, Int>, Any>()
}

/** The style's icons: one image with every icon, and where each one is in it. */
class Sprite(val image: ImageBitmap, val icons: Map<String, SpriteIcon>, val pixelRatio: Float) {
    companion object {
        fun parse(index: String, png: ByteArray, pixelRatio: Float): Sprite {
            val image = org.jetbrains.skia.Image.makeFromEncoded(png).toComposeImageBitmap()
            val icons = Json.parseToJsonElement(index).jsonObject.mapValues { (_, value) ->
                val icon = value.jsonObject
                SpriteIcon(icon["x"]!!.jsonPrimitive.int, icon["y"]!!.jsonPrimitive.int, icon["width"]!!.jsonPrimitive.int, icon["height"]!!.jsonPrimitive.int)
            }
            return Sprite(image, icons, pixelRatio)
        }
    }
}

data class SpriteIcon(val x: Int, val y: Int, val width: Int, val height: Int)
