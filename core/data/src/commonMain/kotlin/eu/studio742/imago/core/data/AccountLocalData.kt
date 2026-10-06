package eu.studio742.imago.core.data

import androidx.room.Transactor
import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What signing out does to this device's data.
 *
 * Neither path touches the libraries or the keys: those live in the configuration and do not belong
 * to the account.
 */
@Singleton
class AccountLocalData @Inject constructor(private val database: ImmichRoomDatabase) {

    /**
     * "Keep a copy": the data stays, but no longer belongs to an account.
     *
     * It forgets the remote revision — another account does not have those revisions, and the same
     * account later merges them again as on the first sign-in — and the deletion marks no longer
     * have anyone to tell. The cursors and the links to remote libraries belonged to that account.
     */
    suspend fun detachFromAccount() = transaction {
        for ((table, _) in SyncTables.SYNCED) {
            execSQL("DELETE FROM $table WHERE deletedAt IS NOT NULL")
            execSQL("UPDATE $table SET remoteRevision = NULL, editedByDevice = NULL")
        }
        clearAccountTables()
    }

    /**
     * What never went up is left waiting to be sent — what existed before the account, what was
     * left from a previous account and what the migration brought. Whatever has no remote revision
     * never went up; `editedAt` stays as it was, so conflicts compare the real edits.
     */
    suspend fun markUnsentForUpload() = transaction {
        for ((table, _) in SyncTables.SYNCED) {
            execSQL("UPDATE $table SET dirty = 1 WHERE remoteRevision IS NULL AND deletedAt IS NULL AND dirty = 0")
        }
    }

    /** "Remove the account data from this device": everything that syncs leaves Room. */
    suspend fun removeAccountData() = transaction {
        for ((table, _) in SyncTables.SYNCED) execSQL("DELETE FROM $table")
        execSQL("UPDATE assets SET hasLocalRecipe = 0")
        clearAccountTables()
    }

    private suspend fun Transactor.clearAccountTables() {
        for (table in listOf("sync_cursors", "library_links", "remote_recipes", "recipe_conflicts", "candidate_rejections")) {
            execSQL("DELETE FROM $table")
        }
    }

    private suspend fun transaction(block: suspend Transactor.() -> Unit) =
        database.useWriterConnection { it.immediateTransaction { it.block() } }
}
