package org.ryubing.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.Surface
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
            session.stop()
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
                            session.stop()
                            session.setSurface(null)
                        }
                    })
                }
            },
        )

        if (config.showTouchControls) {
            TouchControls(
                modifier = Modifier.fillMaxSize(),
                onButton = session::setButton,
                onStick = session::setStick,
            )
        }
    }
}

private fun updateSurface(
    session: EmulationSession,
    activity: Activity,
    holder: SurfaceHolder,
    fallbackWidth: Int,
    fallbackHeight: Int,
) {
    @Suppress("DEPRECATION")
    val rotation = activity.windowManager.defaultDisplay.rotation
    session.setSurfaceRotation(rotation)
    session.setSurface(holder.surface)
    startStabilizedResize(session, holder, fallbackWidth, fallbackHeight, rotation)
}

private fun startStabilizedResize(
    session: EmulationSession,
    holder: SurfaceHolder,
    fallbackWidth: Int,
    fallbackHeight: Int,
    expectedRotation: Int,
) {
    val handler = Handler(Looper.getMainLooper())
    var attempts = 0
    var stableCount = 0
    var lastW = -1
    var lastH = -1

    val task = object : Runnable {
        override fun run() {
            var width = holder.surfaceFrame.width()
            var height = holder.surfaceFrame.height()
            if (width <= 0 || height <= 0) {
                width = fallbackWidth
                height = fallbackHeight
            }

            // Portrait-native panels forced to landscape can briefly report swapped dims.
            val landscape = expectedRotation == Surface.ROTATION_90 ||
                expectedRotation == Surface.ROTATION_270
            if (landscape && height > width) {
                val swap = width
                width = height
                height = swap
            } else if (!landscape && width > height) {
                val swap = width
                width = height
                height = swap
            }

            if (width == lastW && height == lastH && width > 0 && height > 0) {
                stableCount++
            } else {
                stableCount = 0
                lastW = width
                lastH = height
            }

            attempts++
            if ((stableCount >= 1 || attempts >= 12) && width > 0 && height > 0) {
                session.setWindowSize(width, height)
                return
            }

            if (attempts < 12) {
                handler.postDelayed(this, 16)
            }
        }
    }

    handler.post(task)
}
