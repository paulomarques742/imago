package eu.studio742.imago.core.data

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * A write transaction, with the same shape as Room's `withTransaction` for Android.
 *
 * The DAOs called inside the block use the transaction's connection: Room ties it to the coroutine.
 */
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R =
    useWriterConnection { connection -> connection.immediateTransaction { block() } }
