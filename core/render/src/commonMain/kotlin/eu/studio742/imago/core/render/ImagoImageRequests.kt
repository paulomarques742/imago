package eu.studio742.imago.core.render

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.Transformation
import eu.studio742.imago.core.immich.IMMICH_API_KEY_HEADER
import eu.studio742.imago.core.model.EditRecipe

/**
 * The Immich API key in the request, when the library has one. Device photos carry no header: there
 * is no server to send it to.
 */
fun ImageRequest.Builder.libraryAuth(apiKey: String?): ImageRequest.Builder = apply {
    apiKey?.takeIf(String::isNotBlank)?.let { httpHeaders(NetworkHeaders.Builder().set(IMMICH_API_KEY_HEADER, it).build()) }
}

/**
 * The local recipe applied to the image Coil loads. Coil treats the transformation as part of the
 * key, so the processed version is cached like any other.
 */
fun ImageRequest.Builder.withRecipe(recipe: EditRecipe?): ImageRequest.Builder = apply {
    recipe?.let { transformations(recipeTransformation(it)) }
}

/** The transformation that applies [recipe]: Android's Bitmap on one side, Skia's on the other. */
expect fun recipeTransformation(recipe: EditRecipe): Transformation
