package eu.studio742.imago.feature.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.data.BrandKitRepository
import eu.studio742.imago.core.data.CompositionMediaRepository
import java.time.Instant

/**
 * The brand editor: the palette, the two fonts and the logos.
 *
 * Every change is saved right away — there is no save button — and always starts from the saved kit,
 * not from what the screen shows: two taps in a row do not trample each other, and a change arriving
 * from another device meanwhile is not erased by the next one made here.
 */
open class BrandKitViewModel(
    private val brandKits: BrandKitRepository,
    private val media: CompositionMediaRepository,
) : ViewModel() {

    /** The saved kit; while there is none, that of an unedited kit. */
    val kit: StateFlow<BrandKit> = brandKits.observe()
        .map { it ?: Unedited }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Unedited)

    fun thumbnailUrl(assetId: String) = media.thumbnailUrl(assetId)
    fun apiKey(assetId: String) = media.apiKey(assetId)

    fun setColor(index: Int, argb: Long) = edit { BrandKitEdits.setColor(it, index, argb) }
    fun addColor(argb: Long) = edit { BrandKitEdits.addColor(it, argb) }
    fun removeColor(index: Int) = edit { BrandKitEdits.removeColor(it, index) }
    fun moveColor(from: Int, to: Int) = edit { BrandKitEdits.moveColor(it, from, to) }

    fun setPrimaryFont(fontId: String) = edit { it.copy(primaryFont = CompositionFonts.resolve(fontId).id) }
    fun setSecondaryFont(fontId: String) = edit { it.copy(secondaryFont = CompositionFonts.resolve(fontId).id) }

    /** Videos stay out: a logo is an image. */
    fun addLogos(chosen: List<ComposerMedia>) =
        edit { BrandKitEdits.addLogos(it, chosen.filterNot(ComposerMedia::isVideo).map(ComposerMedia::toReference)) }

    fun removeLogo(assetId: String) = edit { BrandKitEdits.removeLogo(it, assetId) }
    fun moveLogo(from: Int, to: Int) = edit { BrandKitEdits.moveLogo(it, from, to) }

    private val editing = Mutex()

    private fun edit(change: (BrandKit) -> BrandKit) {
        viewModelScope.launch {
            editing.withLock {
                val current = brandKits.observe().first() ?: Unedited
                val next = change(current)
                // A tap that changes nothing — moving a colour to where it already is — is not an edit,
                // and should not go up to the account as if it were.
                if (next != current) brandKits.save(next.copy(updatedAt = Instant.now().toString()))
            }
        }
    }

    private companion object {
        val Unedited = BrandKit(updatedAt = "")
    }
}

/** The kit's edits, without state: this is where the rules live, and this is what the tests cover. */
internal object BrandKitEdits {
    fun setColor(kit: BrandKit, index: Int, argb: Long): BrandKit =
        if (index !in kit.paletteArgb.indices) kit
        else kit.copy(paletteArgb = kit.paletteArgb.toMutableList().also { it[index] = argb })

    fun addColor(kit: BrandKit, argb: Long): BrandKit = kit.copy(paletteArgb = kit.paletteArgb + argb)

    /** The palette is never empty: the first colour is the main one, and a brand has to have one. */
    fun removeColor(kit: BrandKit, index: Int): BrandKit =
        if (kit.paletteArgb.size <= 1 || index !in kit.paletteArgb.indices) kit
        else kit.copy(paletteArgb = kit.paletteArgb.filterIndexed { i, _ -> i != index })

    fun moveColor(kit: BrandKit, from: Int, to: Int): BrandKit = kit.copy(paletteArgb = kit.paletteArgb.moved(from, to))

    /** They are added at the end, in the order chosen; a logo already there is not repeated. */
    fun addLogos(kit: BrandKit, logos: List<MediaReference>): BrandKit =
        kit.copy(logos = (kit.logos + logos).distinctBy(MediaReference::assetId))

    fun removeLogo(kit: BrandKit, assetId: String): BrandKit = kit.copy(logos = kit.logos.filterNot { it.assetId == assetId })

    fun moveLogo(kit: BrandKit, from: Int, to: Int): BrandKit = kit.copy(logos = kit.logos.moved(from, to))
}

/** The list with the element at [from] taken to [to]; indices outside the list leave it as it is. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
