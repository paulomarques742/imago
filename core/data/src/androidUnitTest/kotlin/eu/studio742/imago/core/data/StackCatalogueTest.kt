package eu.studio742.imago.core.data

import androidx.room.Room
import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import eu.studio742.imago.core.data.db.StackMemberEntity
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The stack a cover holds, as the catalogue keeps it.
 *
 * Only the timeline says which photo is a stack's cover and how many it holds. Albums, people and
 * searches write the same photos into the catalogue without knowing — and must not erase it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StackCatalogueTest {
    private val library = "server"

    private fun asset(id: String, stackId: String? = null, stackCount: Int? = null) = ImmichAsset(
        id = id, checksum = "", originalFileName = "$id.jpg",
        fileCreatedAt = "2026-08-02T10:00:00.000Z", localDateTime = "2026-08-02T11:00",
        width = null, height = null, isFavorite = false, isEdited = false, type = AssetType.IMAGE,
        stackId = stackId, stackCount = stackCount,
    )

    @Test fun aSearchKeepsTheStackTheTimelineGave() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            // The timeline: "cover" holds a stack of three.
            database.assetDao().upsertAll(listOf(AssetEntity.fromDomain(library, asset("cover", "s1", 3))))

            // An album brings the same photo, and another one, knowing nothing of stacks.
            val returned = database.upsertFromSearch(library, listOf(asset("cover"), asset("loose")))

            val rows = database.assetDao().byIds(library, listOf("cover", "loose")).associateBy { it.id }
            assertEquals("s1", rows.getValue("cover").stackId)
            assertEquals(3, rows.getValue("cover").stackCount)
            assertNull(rows.getValue("loose").stackId)
            // And the album's grid gets the cover with its count, for the badge.
            assertEquals(3, returned.single { it.id == "cover" }.stackCount)
            assertNull(returned.single { it.id == "loose" }.stackCount)
        } finally {
            database.close()
        }
    }

    @Test fun aSearchLeavesThePhotosUnderACoverOutAndGivesTheCoverItsSize() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            // The server's list of stacks: "cover" over "under-1" and "under-2".
            database.stackMemberDao().insertAll(
                listOf("cover", "under-1", "under-2").map { StackMemberEntity(library, it, "s1", "cover") },
            )

            // An album that has all three, and one more, none of them seen by the timeline yet.
            val returned = database.upsertFromSearch(
                library,
                listOf(asset("cover"), asset("under-1"), asset("under-2"), asset("loose")),
            )

            assertEquals(listOf("cover", "loose"), returned.map { it.id })
            assertEquals(3, returned.first().stackCount)
            assertEquals("s1", returned.first().stackId)
            assertEquals(
                setOf("cover", "loose"),
                database.assetDao().byIds(library, listOf("cover", "under-1", "under-2", "loose")).map { it.id }.toSet(),
            )
        } finally {
            database.close()
        }
    }

    @Test fun photosAlreadyInTheCatalogueUnderACoverLeaveIt() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            // What albums wrote before the app knew about stacks.
            database.assetDao().upsertAll(listOf("cover", "under", "loose").map { AssetEntity.fromDomain(library, asset(it)) })
            database.stackMemberDao().insertAll(listOf("cover", "under").map { StackMemberEntity(library, it, "s1", "cover") })

            database.stackMemberDao().removeCoveredFromCatalogue(library)

            assertEquals(
                setOf("cover", "loose"),
                database.assetDao().byIds(library, listOf("cover", "under", "loose")).map { it.id }.toSet(),
            )
        } finally {
            database.close()
        }
    }

    @Test fun anExportNeverShowsInASearch() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ImmichRoomDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            // "edit" is Immich's cover over "original"; here the original stands for the stack.
            database.stackMemberDao().insertAll(listOf(StackMemberEntity(library, "original", "s1", "original")))

            database.derivedAssetDao().insertMissing(
                listOf(
                    eu.studio742.imago.core.data.db.DerivedAssetEntity(library, "edit", "original", createdAt = "2026-10-08T00:00:00Z"),
                    eu.studio742.imago.core.data.db.DerivedAssetEntity(library, "stray", "deleted", createdAt = "2026-10-08T00:00:00Z"),
                ),
            )

            val returned = database.upsertFromSearch(
                library,
                listOf(asset("edit"), asset("original"), asset("stray")),
                derivedIds = setOf("edit", "stray"),
            )

            // "stray" came from a photo that is gone: it is the only copy left, and shows.
            assertEquals(listOf("original", "stray"), returned.map { it.id })
            // A stack of one photo once the exports are out: no badge.
            assertNull(returned.first().stackCount)
        } finally {
            database.close()
        }
    }

    @Test fun theTimelineStaysTheAuthority() {
        // A month read again from the timeline says what the stack is now — even that there is none.
        val before = AssetEntity.fromDomain(library, asset("cover", "s1", 3))
        val fromSearch = AssetEntity.fromDomain(library, asset("cover"))
        assertEquals("s1", fromSearch.keepingStackOf(before).stackId)
        val fromTimeline = AssetEntity.fromDomain(library, asset("cover", "s2", 2))
        assertEquals("s2", fromTimeline.keepingStackOf(before).stackId)
        assertEquals(2, fromTimeline.keepingStackOf(before).stackCount)
    }
}
