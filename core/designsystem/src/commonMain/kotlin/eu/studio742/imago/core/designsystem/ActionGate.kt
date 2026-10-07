package eu.studio742.imago.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What an action that changes or sends a photo goes through. Normally it runs at once; over the
 * lock screen — the camera showing its last photo to whoever holds the phone — it runs only after
 * the phone is unlocked.
 */
fun interface ActionGate {
    fun run(action: () -> Unit)
}

val LocalActionGate = staticCompositionLocalOf { ActionGate { action -> action() } }
