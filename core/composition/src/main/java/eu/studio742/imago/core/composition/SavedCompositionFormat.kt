package eu.studio742.imago.core.composition

import kotlinx.serialization.Serializable

/** A named local catalogue entry. Projects retain dimensions by value, independently of this entry. */
@Serializable
data class SavedCompositionFormat(val id: String, val name: String, val pixelWidth: Int, val pixelHeight: Int) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "A saved format needs an ID and a name." }
        require(pixelWidth in MIN_PAGE_SIDE..MAX_PAGE_SIDE && pixelHeight in MIN_PAGE_SIDE..MAX_PAGE_SIDE) {
            "Saved format dimensions must fit the page limits."
        }
    }
    val format: PageFormat get() = PageFormat.custom(pixelWidth, pixelHeight)
}
