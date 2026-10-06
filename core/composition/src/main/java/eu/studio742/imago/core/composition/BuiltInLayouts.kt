package eu.studio742.imago.core.composition

enum class BuiltInLayout(val label: String, val minimumPages: Int = 1) {
    BLANK("Blank"),
    FULL_BLEED("Full page"),
    VERTICAL_SPLIT("Vertical split"),
    HORIZONTAL_SPLIT("Horizontal split"),
    GRID_2X2("Grid 2 × 2"),
    HERO_STACK("Hero + two"),
    EDITORIAL("Editorial"),
    OVERLAP("Overlapping"),
    PANORAMA_2("Panorama · 2 pages", 2),
    PANORAMA_3("Panorama · 3 pages", 3),
}

data class LayoutSlot(val bounds: NormalizedRect, val kind: SlotKind = SlotKind.MEDIA)
enum class SlotKind { MEDIA, TEXT }

fun BuiltInLayout.slots(): List<LayoutSlot> = when (this) {
    BuiltInLayout.BLANK -> emptyList()
    BuiltInLayout.FULL_BLEED -> listOf(LayoutSlot(NormalizedRect(0f, 0f, 1f, 1f)))
    BuiltInLayout.VERTICAL_SPLIT -> listOf(
        LayoutSlot(NormalizedRect(0f, 0f, .5f, 1f)),
        LayoutSlot(NormalizedRect(.5f, 0f, .5f, 1f)),
    )
    BuiltInLayout.HORIZONTAL_SPLIT -> listOf(
        LayoutSlot(NormalizedRect(0f, 0f, 1f, .5f)),
        LayoutSlot(NormalizedRect(0f, .5f, 1f, .5f)),
    )
    BuiltInLayout.GRID_2X2 -> listOf(
        LayoutSlot(NormalizedRect(0f, 0f, .5f, .5f)), LayoutSlot(NormalizedRect(.5f, 0f, .5f, .5f)),
        LayoutSlot(NormalizedRect(0f, .5f, .5f, .5f)), LayoutSlot(NormalizedRect(.5f, .5f, .5f, .5f)),
    )
    BuiltInLayout.HERO_STACK -> listOf(
        LayoutSlot(NormalizedRect(0f, 0f, .64f, 1f)),
        LayoutSlot(NormalizedRect(.64f, 0f, .36f, .5f)),
        LayoutSlot(NormalizedRect(.64f, .5f, .36f, .5f)),
    )
    BuiltInLayout.EDITORIAL -> listOf(
        LayoutSlot(NormalizedRect(.08f, .08f, .84f, .65f)),
        LayoutSlot(NormalizedRect(.08f, .77f, .84f, .15f), SlotKind.TEXT),
    )
    BuiltInLayout.OVERLAP -> listOf(
        LayoutSlot(NormalizedRect(.08f, .1f, .68f, .62f)),
        LayoutSlot(NormalizedRect(.38f, .42f, .54f, .48f)),
    )
    BuiltInLayout.PANORAMA_2 -> listOf(LayoutSlot(NormalizedRect(0f, 0f, 2f, 1f)))
    BuiltInLayout.PANORAMA_3 -> listOf(LayoutSlot(NormalizedRect(0f, 0f, 3f, 1f)))
}
