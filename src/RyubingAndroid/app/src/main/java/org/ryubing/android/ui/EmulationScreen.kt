package org.ryubing.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.data.GameEntry
import org.ryubing.android.emu.EmulationSession

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val previousOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var insetsController: WindowInsetsControllerCompat? = null

        activity?.let { host ->
            host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            WindowCompat.setDecorFitsSystemWindows(host.window, false)
            insetsController = WindowInsetsControllerCompat(host.window, host.window.decorView)
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            session.setSurface(null)
            activity?.let { host ->
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(host.window, true)
                host.requestedOrientation = previousOrientation
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            updateSurface(session, context as Activity, holder, width, height)
                            scope.launch(Dispatchers.Default) {
                                session.initialize()
                                session.applyConfig(config)
                                session.start(game)
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            updateSurface(session, context as Activity, holder, width, height)
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
}

private fun updateSurface(
    session: EmulationSession,
    activity: Activity,
    holder: SurfaceHolder,
    fallbackWidth: Int,
    fallbackHeight: Int,
) {
    session.setSurface(holder.surface)

    val width = holder.surfaceFrame.width().takeIf { it > 0 } ?: fallbackWidth
    val height = holder.surfaceFrame.height().takeIf { it > 0 } ?: fallbackHeight
    session.setWindowSize(width, height)

    // Swapchain extent can lag one frame after the activity rotates; kick again once settled.
    if (width > 0 && height > 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            val w = holder.surfaceFrame.width().takeIf { it > 0 } ?: fallbackWidth
            val h = holder.surfaceFrame.height().takeIf { it > 0 } ?: fallbackHeight
            session.setWindowSize(w, h)
        }, 32)
    }
}
