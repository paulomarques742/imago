package eu.studio742.imago.core.model

/**
 * Whether a library item is a camera RAW, for what the screens say about it — a badge, a word while it
 * is developed. It is a hint from the MIME type and the name, not the decision: whether an original is
 * developed is LibRaw's, from its contents.
 */
fun isRawOriginal(mimeType: String?, fileName: String): Boolean {
    val mime = mimeType?.lowercase()
    if (mime != null && (mime == "image/x-raw" || mime == "image/dng" || RAW_MIME.matches(mime))) return true
    return fileName.substringAfterLast('.', "").lowercase() in RAW_EXTENSIONS
}

/** The `image/x-<maker>-<format>` types that Immich and Android give camera RAWs, and Adobe's DNG. */
private val RAW_MIME = Regex(
    "image/x-(adobe-dng|canon-[a-z0-9]+|nikon-[a-z0-9]+|sony-[a-z0-9]+|fuji-[a-z0-9]+|panasonic-[a-z0-9]+|" +
        "olympus-[a-z0-9]+|pentax-[a-z0-9]+|samsung-[a-z0-9]+|sigma-[a-z0-9]+|hasselblad-[a-z0-9]+|" +
        "phaseone-[a-z0-9]+|leica-[a-z0-9]+|kodak-[a-z0-9]+|minolta-[a-z0-9]+|epson-[a-z0-9]+|mamiya-[a-z0-9]+)",
)

private val RAW_EXTENSIONS = setOf(
    "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl", "pef",
    "srw", "x3f", "3fr", "fff", "iiq", "erf", "kdc", "dcr", "mrw", "mef", "mos",
)
