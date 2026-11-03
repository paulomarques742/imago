package eu.studio742.imago.core.data.db

/**
 * The "Type" chip's slice of the catalogue, as SQL. `:mediaType` is the `ordinal` of
 * [eu.studio742.imago.core.model.MediaTypeFilter]: 0 any, 1 photo, 2 video, 3 RAW. A RAW is what
 * `isRawOriginal` says it is (a camera RAW extension or MIME type) and a photo is any other image, so the
 * extension list and MIME makers below have to stay that function's (every RAW extension there is three letters, which is what lets one `IN` on the last four characters do the job). The RAW test is wrapped in COALESCE: an asset with no MIME
 * type must count as "not RAW", not as an unknown that drops it from the photos.
 *
 * There are two strings because Room needs constants, and the grid's queries alias the table in some
 * places and not in others.
 */
private const val RAW_BARE = """
        lower(substr(originalFileName, -4)) IN ('.dng', '.cr2', '.cr3', '.crw', '.nef', '.nrw', '.arw', '.srf', '.sr2', '.raf', '.orf', '.rw2', '.rwl', '.pef', '.srw', '.x3f', '.3fr', '.fff', '.iiq', '.erf', '.kdc', '.dcr', '.mrw', '.mef', '.mos')
        OR lower(mimeType) IN ('image/x-raw', 'image/dng')
        OR lower(mimeType) LIKE 'image/x-adobe-dng%'
        OR lower(mimeType) LIKE 'image/x-canon%'
        OR lower(mimeType) LIKE 'image/x-nikon%'
        OR lower(mimeType) LIKE 'image/x-sony%'
        OR lower(mimeType) LIKE 'image/x-fuji%'
        OR lower(mimeType) LIKE 'image/x-panasonic%'
        OR lower(mimeType) LIKE 'image/x-olympus%'
        OR lower(mimeType) LIKE 'image/x-pentax%'
        OR lower(mimeType) LIKE 'image/x-samsung%'
        OR lower(mimeType) LIKE 'image/x-sigma%'
        OR lower(mimeType) LIKE 'image/x-hasselblad%'
        OR lower(mimeType) LIKE 'image/x-phaseone%'
        OR lower(mimeType) LIKE 'image/x-leica%'
        OR lower(mimeType) LIKE 'image/x-kodak%'
        OR lower(mimeType) LIKE 'image/x-minolta%'
        OR lower(mimeType) LIKE 'image/x-epson%'
        OR lower(mimeType) LIKE 'image/x-mamiya%'
"""

private const val RAW_ALIASED = """
        lower(substr(a.originalFileName, -4)) IN ('.dng', '.cr2', '.cr3', '.crw', '.nef', '.nrw', '.arw', '.srf', '.sr2', '.raf', '.orf', '.rw2', '.rwl', '.pef', '.srw', '.x3f', '.3fr', '.fff', '.iiq', '.erf', '.kdc', '.dcr', '.mrw', '.mef', '.mos')
        OR lower(a.mimeType) IN ('image/x-raw', 'image/dng')
        OR lower(a.mimeType) LIKE 'image/x-adobe-dng%'
        OR lower(a.mimeType) LIKE 'image/x-canon%'
        OR lower(a.mimeType) LIKE 'image/x-nikon%'
        OR lower(a.mimeType) LIKE 'image/x-sony%'
        OR lower(a.mimeType) LIKE 'image/x-fuji%'
        OR lower(a.mimeType) LIKE 'image/x-panasonic%'
        OR lower(a.mimeType) LIKE 'image/x-olympus%'
        OR lower(a.mimeType) LIKE 'image/x-pentax%'
        OR lower(a.mimeType) LIKE 'image/x-samsung%'
        OR lower(a.mimeType) LIKE 'image/x-sigma%'
        OR lower(a.mimeType) LIKE 'image/x-hasselblad%'
        OR lower(a.mimeType) LIKE 'image/x-phaseone%'
        OR lower(a.mimeType) LIKE 'image/x-leica%'
        OR lower(a.mimeType) LIKE 'image/x-kodak%'
        OR lower(a.mimeType) LIKE 'image/x-minolta%'
        OR lower(a.mimeType) LIKE 'image/x-epson%'
        OR lower(a.mimeType) LIKE 'image/x-mamiya%'
"""

internal const val MEDIA_TYPE_SQL = """
        (:mediaType = 0
          OR (:mediaType = 2 AND type = 'VIDEO')
          OR (:mediaType IN (1, 3) AND type = 'IMAGE' AND (:mediaType = 3) = COALESCE(($RAW_BARE), 0)))"""

internal const val MEDIA_TYPE_ALIASED_SQL = """
        (:mediaType = 0
          OR (:mediaType = 2 AND a.type = 'VIDEO')
          OR (:mediaType IN (1, 3) AND a.type = 'IMAGE' AND (:mediaType = 3) = COALESCE(($RAW_ALIASED), 0)))"""
