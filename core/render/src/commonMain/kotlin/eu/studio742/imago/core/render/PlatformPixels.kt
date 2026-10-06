package eu.studio742.imago.core.render

import androidx.compose.ui.graphics.ImageBitmap
import coil3.request.ImageRequest

/*
 * The bridges between each platform's images and the engine's PixelBuffer. Coil's Bitmap is
 * Android's on one side and Skia's on the other; the shared code only needs the dimensions, the
 * pixels and to show it.
 */

expect val coil3.Bitmap.pixelWidth: Int
expect val coil3.Bitmap.pixelHeight: Int

/** The bitmap's ARGB pixels, without premultiplication. */
expect fun coil3.Bitmap.toPixelBuffer(): PixelBuffer

expect fun PixelBuffer.toImageBitmap(): ImageBitmap

/** An encoded image (JPEG, PNG, WebP) as pixels, or null if it cannot be read. */
expect fun decodePixels(bytes: ByteArray): PixelBuffer?

/** A bitmap the CPU processor can read: on Android, never a hardware bitmap. */
expect fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder
