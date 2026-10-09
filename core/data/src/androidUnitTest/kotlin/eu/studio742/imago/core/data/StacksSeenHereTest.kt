package eu.studio742.imago.core.data

import eu.studio742.imago.core.data.db.AssetEntity
import eu.studio742.imago.core.data.db.DerivedAssetEntity
import eu.studio742.imago.core.data.db.StackMemberEntity
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stacks as IMAGO shows them: without this app's exports, whose originals stand in for them.
 *
 * On the server an edit exported from here is its original's stack's cover. In the app it is the
 * original that is edited, so a photo with only its exports beside it is no stack at all.
 */
class StacksSeenHereTest {
    private val library = "server"

    private fun asset(id: String, name: String = "$id.jpg", stackId: String? = null, stackCount: Int? = null) = ImmichAsset(
        id = id, checksum = "", originalFileName = name,
        fileCreatedAt = "2026-08-02T10:00:00.000Z", localDateTime = "2026-08-02T11:00",
        width = null, height = null, isFavorite = false, isEdited = false, type = AssetType.IMAGE,
        stackId = stackId, stackCount = stackCount,
    )

    @Test fun aPhotoWithOnlyItsExportsIsNoStack() {
        val stack = ImmichStack("s1", "edit-2", listOf(asset("edit-2"), asset("edit-1"), asset("original")))

        val seen = stack.seenHere(mapOf("edit-1" to "original", "edit-2" to "original"))!!

        assertEquals("original", seen.cover.id)
        assertTrue(seen.coverIsStandIn)
        assertFalse(seen.isStack)
        assertNull(seen.size)
    }

    @Test fun aRealStackKeepsItsPhotosWithTheOriginalAsCover() {
        // RAW and JPEG stacked on the web, and an edit of the JPEG exported from here on top.
        val stack = ImmichStack("s1", "edit", listOf(asset("edit"), asset("raw", "photo.dng"), asset("jpeg")))

        val seen = stack.seenHere(mapOf("edit" to "jpeg"))!!

        assertEquals("jpeg", seen.cover.id)
        assertEquals(listOf("jpeg", "raw"), seen.photos.map { it.id })
        assertEquals(2, seen.size)
    }

    @Test fun aStackWithoutExportsIsLeftAsItIs() {
        val stack = ImmichStack("s1", "b", listOf(asset("a"), asset("b"), asset("c")))

        val seen = stack.seenHere(emptyMap())!!

        assertEquals("b", seen.cover.id)
        assertFalse(seen.coverIsStandIn)
        assertEquals(3, seen.size)
    }

    @Test fun anExportWithoutItsRecordIsToldApartByItsName() {
        // Exported from another device before the record came over.
        val stack = ImmichStack("s1", "edit", listOf(asset("edit", "photo_ImmichRoom.jpg"), asset("original", "photo.heic")))

        val seen = stack.seenHere(emptyMap())!!

        assertEquals("original", seen.cover.id)
        assertFalse(seen.isStack)
    }

    @Test fun theTimelineShowsTheOriginalWhereItGivesAnExportCover() {
        val original = AssetEntity.fromDomain(library, asset("original"))
        val timeline = listOf(
            asset("edit", stackId = "s1", stackCount = 2),
            asset("real-cover", stackId = "s2", stackCount = 4),
            asset("stray"),
            asset("plain"),
        )
        val stacks = mapOf(
            "s1" to listOf(StackMemberEntity(library, "original", "s1", "original")),
            "s2" to listOf("real-cover", "x", "y").map { StackMemberEntity(library, it, "s2", "real-cover") },
        )
        val originalOf = mapOf("edit" to "original", "stray" to "plain")

        assertEquals(listOf("original"), standInsNeeded(timeline, stacks, originalOf))
        val rows = timelineRows(library, timeline, mapOf("original" to original), stacks, originalOf, presentOriginals = setOf("original", "plain")).associateBy { it.id }

        // The export's place goes to its original, with no badge: the stack is only it and its edit.
        assertEquals(setOf("original", "real-cover", "plain"), rows.keys)
        assertNull(rows.getValue("original").stackCount)
        // The real stack counts only what is not an export: three here, not the timeline's four.
        assertEquals(3, rows.getValue("real-cover").stackCount)
        assertEquals("s2", rows.getValue("real-cover").stackId)
    }

    @Test fun withoutTheListOfStacksTheTimelineCountStays() {
        // A key without stack.read: nothing to count with but what the timeline says.
        val rows = timelineRows(
            library,
            listOf(asset("cover", stackId = "s1", stackCount = 3)),
            known = emptyMap(),
            stacks = emptyMap(),
            originalOf = emptyMap(),
            presentOriginals = emptySet(),
        )
        assertEquals(3, rows.single().stackCount)
    }

    @Test fun anExportWhoseOriginalIsGoneShowsInTheTimeline() {
        val rows = timelineRows(
            library,
            listOf(asset("stray"), asset("edit", stackId = "s1", stackCount = 2)),
            known = emptyMap(),
            stacks = emptyMap(),
            originalOf = mapOf("stray" to "deleted", "edit" to "also-deleted"),
            presentOriginals = emptySet(),
        )
        assertEquals(setOf("stray", "edit"), rows.map { it.id }.toSet())
    }

    @Test fun anExportWithoutItsOriginalInTheStackIsOneOfItsPhotos() {
        // The original was deleted; the export and a RAW are what is left of the stack.
        val stack = ImmichStack("s1", "edit", listOf(asset("edit"), asset("raw", "photo.dng")))

        val seen = stack.seenHere(mapOf("edit" to "deleted"))!!

        assertEquals("edit", seen.cover.id)
        assertEquals(2, seen.size)
    }

    private fun export(id: String, of: String, at: String) = DerivedAssetEntity(library, id, of, createdAt = at)

    @Test fun aPhotoIsCoveredOnTheServerByItsNewestExport() {
        val exports = listOf(export("e1", "o", "2026-10-01T00:00:00Z"), export("e2", "o", "2026-10-08T00:00:00Z"))
        assertEquals("e2", faceOf("o", listOf("o", "e1", "e2"), exports))
        // Only an export in the stack counts.
        assertEquals("e1", faceOf("o", listOf("o", "e1"), exports))
        assertEquals("x", faceOf("x", listOf("x", "o", "e2"), exports))
    }

    @Test fun takingAPhotoOutTakesItsExportsAlongAndStacksThemAgain() {
        // Immich's cover is o's edit; raw and jpeg stay.
        val stack = ImmichStack("s1", "e1", listOf(asset("e1"), asset("o"), asset("raw"), asset("jpeg")))
        val plan = stack.removing("o", listOf(export("e1", "o", "2026-10-08T00:00:00Z")))

        assertEquals(listOf("o", "e1"), plan.leaving)
        // The cover is leaving: Immich will not take it out, so another one first.
        assertEquals("raw", plan.newCover)
        assertFalse(plan.undo)
        assertEquals(listOf("e1", "o"), plan.regroup)
    }

    @Test fun aStackLeftWithOnePhotoIsUndone() {
        val stack = ImmichStack("s1", "a", listOf(asset("a"), asset("b")))
        val plan = stack.removing("b", emptyList())

        assertTrue(plan.undo)
        assertTrue(plan.regroup.isEmpty())
    }

    @Test fun undoingAStackKeepsEachOriginalUnderItsEdits() {
        val stack = ImmichStack("s1", "e2", listOf(asset("e2"), asset("e1"), asset("o"), asset("raw")))
        val exports = listOf(export("e1", "o", "2026-10-01T00:00:00Z"), export("e2", "o", "2026-10-08T00:00:00Z"))

        assertEquals(listOf(listOf("e2", "o", "e1")), stack.pairsAfterUndoing(exports))
    }
}
