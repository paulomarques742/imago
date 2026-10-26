package eu.studio742.imago.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.feature.editor.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

enum class ImmichExportOrigin {
    NOT_CHECKED, CHECKING, EXTERNAL, OTHER, UNAVAILABLE;

    val isChecking: Boolean get() = this == NOT_CHECKED || this == CHECKING
}

/** Reads fresh detail because timeline buckets do not carry the external library ID. */
internal class ExportOriginChecker(
    private val scope: CoroutineScope,
    private val externalLibraryId: suspend (String) -> String?,
) {
    private val mutableState = MutableStateFlow(ImmichExportOrigin.NOT_CHECKED)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun reset() {
        generation++
        job?.cancel()
        mutableState.value = ImmichExportOrigin.NOT_CHECKED
    }

    fun check(assetId: String) {
        reset()
        val request = generation
        val reference = runCatching { AssetReference.parse(assetId) }.getOrNull()
        if (reference == null) {
            mutableState.value = ImmichExportOrigin.UNAVAILABLE
            return
        }
        if (reference.libraryId == DEVICE_LIBRARY_ID) {
            mutableState.value = ImmichExportOrigin.OTHER
            return
        }
        mutableState.value = ImmichExportOrigin.CHECKING
        job = scope.launch {
            val origin = try {
                if (externalLibraryId(assetId).isNullOrBlank()) ImmichExportOrigin.OTHER else ImmichExportOrigin.EXTERNAL
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ImmichExportOrigin.UNAVAILABLE
            }
            // A late response for a previous photo cannot change the current export's warning.
            if (request == generation) mutableState.value = origin
        }
    }
}

@Composable
internal fun ImmichExportAction(origin: ImmichExportOrigin, hasDestination: Boolean, onSend: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val notice = when (origin) {
            ImmichExportOrigin.NOT_CHECKED, ImmichExportOrigin.CHECKING -> Res.string.editor_export_checking_origin
            ImmichExportOrigin.EXTERNAL -> Res.string.editor_export_external_library
            ImmichExportOrigin.UNAVAILABLE -> Res.string.editor_export_origin_unavailable
            ImmichExportOrigin.OTHER -> null
        }
        if (notice != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(notice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }
        Button(onClick = onSend, enabled = hasDestination && !origin.isChecking, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.editor_send_to_immich))
        }
    }
}
