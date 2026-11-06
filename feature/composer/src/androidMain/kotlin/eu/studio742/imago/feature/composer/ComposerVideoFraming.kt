package eu.studio742.imago.feature.composer

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import eu.studio742.imago.core.composition.PlacementCrop
import eu.studio742.imago.core.composition.placementImageRect
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics

/** Keeps PlayerView's controls while applying the same cover, pan and zoom as still media. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ComposerVideoFraming(private val player: Player, private val onFailed: () -> Unit, private val onReady: () -> Unit) : Player.Listener {
    private var view: PlayerView? = null
    private var crop = PlacementCrop()
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCrop() }

    fun attach(next: PlayerView) {
        if (view === next) return
        release()
        view = next
        next.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        next.useController = true
        next.player = player
        next.videoSurfaceView?.addOnLayoutChangeListener(layout)
        player.addListener(this)
        applyCrop()
    }

    fun update(value: PlacementCrop) { crop = value; applyCrop() }

    private fun applyCrop() {
        val texture = view?.videoSurfaceView as? TextureView ?: return
        val size = player.videoSize
        if (texture.width == 0 || texture.height == 0 || size.width == 0 || size.height == 0) return
        val image = placementImageRect(size.width * size.pixelWidthHeightRatio, size.height.toFloat(), texture.width.toFloat(), texture.height.toFloat(), crop)
        texture.setTransform(Matrix().apply {
            setScale(image.width / texture.width, image.height / texture.height)
            postTranslate(image.left, image.top)
        })
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) { view?.post { applyCrop() } }
    override fun onRenderedFirstFrame() { applyCrop(); onReady() }
    override fun onPlayerError(error: PlaybackException) {
        LocalDiagnostics.failure(DiagnosticArea.VIDEO, error, DiagnosticOperation.VIDEO)
        onFailed()
    }

    fun release() {
        player.removeListener(this)
        view?.videoSurfaceView?.removeOnLayoutChangeListener(layout)
        view?.let { if (it.player === player) it.player = null }
        view = null
    }
}
