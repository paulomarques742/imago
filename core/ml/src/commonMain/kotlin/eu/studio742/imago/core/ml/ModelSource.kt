package eu.studio742.imago.core.ml

/** Where a mask model's weights come from, and where they are kept once they are on the device. */
interface ModelSource {
    /** The model's file if it is already on the device, checked when it arrived; null otherwise. */
    fun localPath(model: MaskModel): String?

    /**
     * Brings [model] to the device if it is not there yet, checks it against the manifest and returns its
     * path. Blocking network and disk work, for a background thread. [progress] receives the bytes so far
     * and the total.
     *
     * @throws eu.studio742.imago.core.model.UserMessageException with `MASK_MODEL_DOWNLOAD_FAILED`.
     */
    fun download(model: MaskModel, progress: (Long, Long) -> Unit = { _, _ -> }): String
}
