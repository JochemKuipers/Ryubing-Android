package org.ryubing.android.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.data.GameEntry
import org.ryubing.android.emu.EmulationSession
import kotlinx.coroutines.CoroutineScope

/**
 * The in-game screen: a SurfaceView the emulator renders into (Vulkan swapchain bound to
 * its ANativeWindow) with the on-screen [TouchControls] overlaid.
 */
@Composable
fun EmulationScreen(
    game: GameEntry,
    session: EmulationSession,
    config: EmulatorConfig,
    onExit: () -> Unit,
) {
    val scope = CoroutineScope(Dispatchers.Default)

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            session.setSurface(holder.surface)
                            scope.launch {
                                session.initialize()
                                session.applyConfig(config)
                                // The session resolves the game's SAF content:// URI to an
                                // openable file descriptor before handing it to the core.
                                session.start(game)
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            session.setSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session.setSurface(null)
                        }
                    })
                }
            },
        )

        TouchControls(
            modifier = Modifier.fillMaxSize(),
            onButton = session::setButton,
            onStick = session::setStick,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            session.setSurface(null)
        }
    }
}
