package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import eu.studio742.imago.core.model.EditRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerMediaFramesTest {
    private val photo = CompositionElement.Photo("photo", ElementTransform(NormalizedRect(.25f, .25f, .5f, .5f)), 0,
        MediaReference("asset", "hash", "photo.png", width = 600, height = 300),
        PhotoRecipeSnapshot(EditRecipe(assetId = "asset", originalChecksum = "hash", createdAt = "", updatedAt = "")),
        crop = PlacementCrop(.3f, -.2f, 1.4f))
    private val other = photo.copy(id = "other", zIndex = 1, transform = photo.transform.copy(bounds = NormalizedRect(.1f, .1f, .2f, .2f)))
    private val paper = MediaFrameDecoration(MediaFrameKind.TORN_PAPER, width = 36f, roughness = .7f, seed = 44)
    private fun source(elements: List<CompositionElement> = listOf(photo)) = CompositionProject(id = "frame-test", name = "Frames",
        format = PageFormatPreset.SQUARE_1_1.format, pages = listOf(CompositionPage("page", 0)), elements = elements,
        background = CompositionBackground.Solid(0), createdAt = "", updatedAt = "")
    private class Projects(var stored: CompositionProject) : CompositionRepository {
        override fun observeAll() = flowOf(listOf(stored))
        override suspend fun get(id: String) = stored
        override suspend fun save(project: CompositionProject) { stored = Json.decodeFromString(Json.encodeToString(project)) }
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
    }
    private fun model(elements: List<CompositionElement> = listOf(photo), projects: Projects = Projects(source(elements)),
        templates: CompositionTemplateRepository = FakeComposerRepos.templates): ComposerEditorViewModel {
        val f = FakeComposerRepos
        return ComposerEditorViewModel(projects, templates, f.media, f.recipes, f.exports, f.brandKits).also { it.open(projects.stored.id); it.selectElement(elements.first().id) }
    }
    private fun bytes(): ByteArray {
        val image = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, if (x < 300) 0xFFCC3322.toInt() else 0xFF2255CC.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    private fun render(element: CompositionElement.Photo): BufferedImage = ImageIO.read(ByteArrayInputStream(
        CompositionRenderer().render(source(listOf(element)), 0, mapOf("asset" to bytes()), 240)))
    private fun alpha(image: BufferedImage, x: Int, y: Int) = image.getRGB(x, y) ushr 24 and 255
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `applying replacing and removing a design preserves the media recipe crop and base frame`() {
        val original = photo.copy(frame = FrameStyle(cornerRadius = .12f, borderWidth = 18f, borderArgb = 0xFFAACC33))
        val vm = model(listOf(original))
        vm.setMediaFrameDesign(MediaCutout.HEART, paper)
        var result = vm.state.value.project!!.elements.single() as CompositionElement.Photo
        assertEquals(original.media, result.media); assertEquals(original.recipe, result.recipe); assertEquals(original.crop, result.crop)
        assertEquals(original.frame.borderWidth, result.frame.borderWidth, .001f); assertEquals(original.transform, result.transform)
        vm.setMediaFrameDesign(MediaCutout.CIRCLE, paper.copy(kind = MediaFrameKind.POLAROID))
        vm.setMediaFrameDesign(MediaCutout.RECTANGLE, paper.copy(kind = MediaFrameKind.NONE))
        result = vm.state.value.project!!.elements.single() as CompositionElement.Photo
        assertEquals(original.frame.cutout, result.frame.cutout); assertEquals(MediaFrameKind.NONE, result.frame.decoration.kind)
        vm.undo(); assertEquals(MediaCutout.CIRCLE, vm.state.value.project!!.elements.single().mediaFrame()!!.cutout)
    }

    @Test fun `collective designs apply to exactly the compatible selection and undo in one step`() {
        val vm = model(listOf(photo, other))
        vm.beginMultiSelection(photo.id); vm.toggleElementInSelection(other.id)
        vm.beginGesture(stage = false); vm.setSelectedOpacity(.7f)
        vm.setMediaFrameDesign(MediaCutout.STAR, paper)
        assertTrue(vm.state.value.project!!.elements.all { it.mediaFrame()!!.cutout == MediaCutout.STAR })
        vm.undo(); assertTrue(vm.state.value.project!!.elements.all { it.mediaFrame()!!.cutout == MediaCutout.RECTANGLE })
        assertTrue(vm.state.value.project!!.elements.all { it.transform.opacity == .7f })
    }

    @Test fun `locked members and incompatible mixtures cannot be changed indirectly`() {
        val locked = other.copy(locked = true, groupId = "group")
        val grouped = photo.copy(groupId = "group")
        val vm = model(listOf(grouped, locked)); val original = vm.state.value.project
        vm.setMediaFrameDesign(MediaCutout.HEART, paper); assertEquals(original, vm.state.value.project)
        vm.focusGroupMember(grouped.id); vm.setMediaFrameDesign(MediaCutout.HEART, paper)
        assertEquals(locked, vm.state.value.project!!.elements.last())
        val shape = CompositionElement.Shape("shape", other.transform, 2, ShapeKind.ELLIPSE)
        val mixed = model(listOf(photo, shape)); mixed.beginMultiSelection(photo.id); mixed.toggleElementInSelection(shape.id)
        val before = mixed.state.value.project; mixed.setMediaFrameDesign(MediaCutout.STAR, paper)
        assertEquals(before, mixed.state.value.project)
    }

    @Test fun `designs survive duplicate reopen and templates with media removed`() {
        var savedTemplate: CompositionTemplate? = null
        val templates = object : CompositionTemplateRepository {
            override fun observeAll() = flowOf(emptyList<CompositionTemplate>())
            override suspend fun get(id: String) = savedTemplate
            override suspend fun save(template: CompositionTemplate) { savedTemplate = Json.decodeFromString(Json.encodeToString(template)) }
            override suspend fun delete(id: String) = Unit
        }
        val projects = Projects(source()); val vm = model(projects = projects, templates = templates)
        vm.setMediaFrameDesign(MediaCutout.TORN_PAPER, paper); vm.duplicateSelected()
        assertEquals(2, vm.state.value.project!!.elements.size)
        assertTrue(vm.state.value.project!!.elements.all { it.mediaFrame()!!.decoration.seed == 44 })
        vm.saveAsTemplate("Frames", false)
        assertTrue(savedTemplate!!.project.elements.all { it is CompositionElement.MediaPlaceholder && it.frame.cutout == MediaCutout.TORN_PAPER })
        vm.saveDraft(); vm.open(projects.stored.id)
        assertTrue(vm.state.value.project!!.elements.all { it.mediaFrame()!!.decoration == paper })
    }

    @Test fun `geometric cutouts export transparent corners while retaining opaque content`() {
        MediaCutout.entries.filter { it != MediaCutout.RECTANGLE }.forEach { cutout ->
            val image = render(photo.copy(frame = FrameStyle(cutout = cutout)))
            assertEquals("$cutout corner", 0, alpha(image, 60, 60))
            assertTrue("$cutout centre", alpha(image, 120, 120) > 240)
        }
    }

    @Test fun `torn mask and paper render identically after serialization and vary only when the seed changes`() {
        val original = photo.copy(frame = FrameStyle(cutout = MediaCutout.TORN_PAPER, decoration = paper))
        val restored = Json.decodeFromString<CompositionElement.Photo>(Json.encodeToString(original))
        assertArrayEquals(render(original).getRGB(0, 0, 240, 240, null, 0, 240), render(restored).getRGB(0, 0, 240, 240, null, 0, 240))
        assertFalse(render(original).getRGB(0, 0, 240, 240, null, 0, 240).contentEquals(
            render(original.copy(frame = original.frame.copy(decoration = paper.copy(seed = 45)))).getRGB(0, 0, 240, 240, null, 0, 240)))
    }

    @Test fun `Polaroid bottom margin extends beyond the other three sides`() {
        val image = render(photo.copy(frame = FrameStyle(decoration = paper.copy(kind = MediaFrameKind.POLAROID, width = 45f, bottomWeight = 2f))))
        assertTrue(alpha(image, 120, 200) > 240)
        assertEquals(0, alpha(image, 120, 40))
    }

    @Test fun `element opacity is applied once across both the paper and the photo`() {
        val image = render(photo.copy(transform = photo.transform.copy(opacity = .5f),
            frame = FrameStyle(decoration = paper.copy(kind = MediaFrameKind.POLAROID, width = 60f))))
        assertEquals(128.0, alpha(image, 120, 120).toDouble(), 1.0)
        assertEquals(128.0, alpha(image, 120, 190).toDouble(), 1.0)
    }

    @Test fun `decorative opacity stays uniform where bevel details overlap the frame paper`() {
        val image = render(photo.copy(frame = FrameStyle(decoration = paper.copy(kind = MediaFrameKind.CLASSIC, width = 90f, opacity = .5f))))
        assertEquals(128.0, alpha(image, 120, 50).toDouble(), 2.0)
        assertEquals(128.0, alpha(image, 120, 42).toDouble(), 2.0)
    }

    @Test fun `crop panning chooses the same source side as the stage contract`() {
        val left = render(photo.copy(crop = PlacementCrop(-1f, 0f, 1f))).getRGB(120, 120)
        val right = render(photo.copy(crop = PlacementCrop(1f, 0f, 1f))).getRGB(120, 120)
        assertTrue((left shr 16 and 255) > (left and 255))
        assertTrue((right and 255) > (right shr 16 and 255))
    }
}
