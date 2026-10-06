package eu.studio742.imago.core.designsystem.i18n

import eu.studio742.imago.core.designsystem.resources.Res
import eu.studio742.imago.core.designsystem.resources.sync_conflict_copy_name
import eu.studio742.imago.core.designsystem.resources.sync_unknown_device
import eu.studio742.imago.core.model.SyncTexts

/** The names sync writes into the data, in the app language at the moment they are written. */
object AppSyncTexts : SyncTexts {
    override suspend fun conflictCopyName(name: String, deviceName: String): String =
        appString(Res.string.sync_conflict_copy_name, name, deviceName)

    override suspend fun unknownDevice(): String = appString(Res.string.sync_unknown_device)
}
