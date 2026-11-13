package eu.studio742.imago.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditTest {
    private fun recipe(geometry: Geometry = Geometry(), video: VideoEdit? = null) = EditRecipe(
        assetId = "video-1",
        originalChecksum = "sum",
        createdAt = "2026-11-11T10:00:00Z",
        updatedAt = "2026-11-11T10:00:00Z",
        geometry = geometry,
        video = video,
    )

    @Test
    fun aRecipeWithoutVideoEditsChangesNoVideo() {
        assertFalse(recipe().changesTheVideo())
    }

    @Test
    fun aRotationAloneChangesTheVideoWithoutATrim() {
        val turned = recipe(geometry = Geometry(rotation = 90))
        assertTrue(turned.changesTheVideo())
        assertFalse(turned.trimsTheVideo())
        assertEquals(90, turned.videoRotationDegrees())
    }

    @Test
    fun aNegativeRotationIsReadAsItsPositiveTurn() {
        assertEquals(270, recipe(geometry = Geometry(rotation = -90)).videoRotationDegrees())
    }

    @Test
    fun keepingTheWholeVideoStoresNoTrim() {
        val trimmed = recipe().withVideoRange(2_000, 6_000, 10_000, "t")
        assertEquals(VideoEdit(2_000, 6_000), trimmed.video)
        val whole = trimmed.withVideoRange(0, 10_000, 10_000, "t2")
        assertNull(whole.video)
        assertFalse(whole.changesTheVideo())
    }

    @Test
    fun anEdgeOnTheFileEdgeIsNotStored() {
        assertEquals(VideoEdit(startMs = 3_000, endMs = null), recipe().withVideoRange(3_000, 10_000, 10_000, "t").video)
        assertEquals(VideoEdit(startMs = 0, endMs = 4_000), recipe().withVideoRange(0, 4_000, 10_000, "t").video)
    }

    @Test
    fun theKeptStretchNeverGetsShorterThanTheMinimum() {
        val range = VideoEdit(5_000, 5_100).rangeIn(10_000)
        assertEquals(5_000L, range.first)
        assertEquals(5_000 + MIN_VIDEO_TRIM_MS, range.last)
        // At the end of the file the start gives way instead.
        val tail = VideoEdit(9_900, null).rangeIn(10_000)
        assertEquals(10_000L - MIN_VIDEO_TRIM_MS, tail.first)
        assertEquals(10_000L, tail.last)
    }

    @Test
    fun theTrimSurvivesTheRoundTripAndAnOldRecipeStillReads() {
        val edited = recipe(video = VideoEdit(1_000, 4_000))
        assertEquals(edited, Json.decodeFromString<EditRecipe>(Json.encodeToString(edited)))
        val old = Json.encodeToString(recipe()).replace(Regex(""","video":null"""), "")
        assertNull(Json.decodeFromString<EditRecipe>(old).video)
    }

    @Test
    fun anEdgeSnapsToAKeyframeWhenNearAndToTenthsOtherwise() {
        val keyframes = listOf(0L, 2_000L, 4_000L)
        assertEquals(2_000L, snapVideoEdge(2_150, keyframes, 200))
        assertEquals(2_700L, snapVideoEdge(2_730, keyframes, 200))
    }
}
