package eu.studio742.imago.feature.detail

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.LocalAppLocale
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.feature.detail.resources.*
import eu.studio742.imago.feature.library.PlaceMap
import eu.studio742.imago.feature.library.rememberAddress
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything known about the photo, under it, as the system gallery shows it: when, the file and
 * where it lives, the camera and the shot, the place on a map, and who is in it.
 */
@Composable
internal fun DetailInfoPanel(
    asset: DetailAsset,
    state: DetailUiState,
    /** Null where the file cannot be renamed: a server's original keeps its name. */
    onRename: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** The library's map, centred here. */
    onShowOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    /** The photos of someone in this one. */
    onShowPerson: ((DetailPerson) -> Unit)? = null,
) {
    val locale = LocalAppLocale.current
    val exif = state.exif
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
            Text(
                formatTakenAt(asset.date.ifBlank { asset.fileCreatedAt }),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ImagoColors.TextPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.fileName ?: asset.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                onRename?.let {
                    IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Edit, stringResource(Res.string.detail_rename), tint = ImagoColors.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            state.folder?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
            }
        }

        val file = fileFacts(exif, locale)
        val shot = shotFacts(exif, locale)
        val camera = cameraName(exif)
        if (camera != null || file.isNotEmpty() || shot.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
                camera?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ImagoColors.TextPrimary) }
                exif.lensModel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary) }
                if (file.isNotEmpty()) Facts(file)
                if (shot.isNotEmpty()) Facts(shot)
            }
        }

        val latitude = exif.latitude
        val longitude = exif.longitude
        if (latitude != null && longitude != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ImagoRadii.Medium))
                    .background(ImagoColors.SurfaceElevated)
                    .then(
                        onShowOnMap?.let { show ->
                            Modifier.clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detail_show_on_map)) { show(latitude, longitude) }
                        } ?: Modifier,
                    ),
            ) {
                Box {
                    PlaceMap(latitude, longitude, Modifier.fillMaxWidth().height(160.dp))
                    // The map is a view of its own on Android and would keep the tap: this layer takes it.
                    if (onShowOnMap != null) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detail_show_on_map)) { onShowOnMap(latitude, longitude) },
                        )
                    }
                }
                val address = rememberAddress(latitude, longitude)
                    ?: listOfNotNull(exif.city, exif.state, exif.country).distinct().joinToString(", ").ifBlank { null }
                    ?: "%.5f, %.5f".format(locale, latitude, longitude)
                Text(
                    address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.TextPrimary,
                    modifier = Modifier.padding(ImagoSpacing.Md),
                )
            }
        }

        if (state.people.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
            ) {
                state.people.forEach { person -> Face(person, onClick = onShowPerson?.let { show -> { show(person) } }) }
            }
        }
    }
}

@Composable
private fun Facts(facts: List<String>) {
    Text(
        facts.joinToString("   |   "),
        style = MaterialTheme.typography.bodyMedium,
        color = ImagoColors.TextSecondary,
    )
}

@Composable
private fun Face(person: DetailPerson, onClick: (() -> Unit)?) {
    val context = LocalPlatformContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .then(onClick?.let { Modifier.clickable(role = Role.Button, onClickLabel = person.name.ifBlank { null }, onClick = it) } ?: Modifier),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(person.thumbnailUrl).libraryAuth(person.apiKey).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(ImagoColors.SurfaceElevated),
        )
        // As the system gallery does, a face nobody named shows a question mark.
        Text(
            person.name.ifBlank { "?" },
            style = MaterialTheme.typography.bodySmall,
            color = ImagoColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = ImagoSpacing.Xs),
        )
    }
}

/** The file: "3,06 MB | 3000x4000 | 12MP". */
internal fun fileFacts(exif: AssetExif, locale: Locale): List<String> = buildList {
    exif.fileSizeBytes?.takeIf { it > 0 }?.let { add(fileSize(it, locale)) }
    val width = exif.imageWidth?.takeIf { it > 0 }
    val height = exif.imageHeight?.takeIf { it > 0 }
    if (width != null && height != null) {
        add("${width}x$height")
        add("${(width.toLong() * height / 1_000_000.0).roundToInt()}MP")
    }
}

/** The shot: "ISO 10 | 23mm | 0,0ev | F1,7 | 1/315 s". */
internal fun shotFacts(exif: AssetExif, locale: Locale): List<String> = buildList {
    exif.iso?.let { add("ISO $it") }
    exif.focalLength?.let { add("%.0fmm".format(locale, it)) }
    exif.exposureBias?.let { add("%.1fev".format(locale, it)) }
    exif.fNumber?.let { add("F%.1f".format(locale, it)) }
    exif.exposureTime?.let(::shutter)?.let(::add)
}

private fun fileSize(bytes: Long, locale: Locale): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(locale, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.2f MB".format(locale, bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(locale, bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * The shutter as photographers write it: "1/315 s" whether the file said "1/315" or 0.0031746, and
 * "2 s" for a long one.
 */
internal fun shutter(value: String): String? {
    val text = value.trim().removeSuffix("s").trim()
    if (text.contains('/')) return "$text s"
    val seconds = text.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    return if (seconds >= 1) "%s s".format(if (seconds % 1.0 == 0.0) seconds.toLong().toString() else "%.1f".format(Locale.ROOT, seconds))
    else "1/${(1 / seconds).roundToInt()} s"
}

/** "Galaxy S23 Ultra" and not "samsung Galaxy S23 Ultra": most phones repeat the brand in the model. */
internal fun cameraName(exif: AssetExif): String? {
    val make = exif.make?.trim()?.takeIf(String::isNotEmpty)
    val model = exif.model?.trim()?.takeIf(String::isNotEmpty)
    return when {
        model != null && make != null && model.startsWith(make, ignoreCase = true) -> model
        model != null && make != null && make.equals("samsung", ignoreCase = true) -> model
        model != null && make != null -> "$make $model"
        else -> model ?: make
    }
}

/**
 * The new name, without the extension, which stays: changing it would not change the format, and
 * other apps go by it. A name the folder already has comes back as [error] and the dialog stays.
 */
@Composable
internal fun RenameDialog(fileName: String, error: UiText?, busy: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val dot = fileName.lastIndexOf('.').takeIf { it > 0 }
    val extension = dot?.let { fileName.substring(it) }.orEmpty()
    var value by remember(fileName) { mutableStateOf(dot?.let { fileName.substring(0, it) } ?: fileName) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = value.trim().takeIf(String::isNotEmpty)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.detail_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    enabled = !busy,
                    suffix = { Text(extension, color = ImagoColors.TextTertiary) },
                    label = { Text(stringResource(Res.string.detail_rename_label)) },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { name?.let(onConfirm) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = { name?.let(onConfirm) }, enabled = name != null && !busy) { Text(stringResource(Res.string.detail_rename_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.detail_cancel)) } },
    )
}
