package org.ryubing.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ryubing.android.data.EmulatorConfig
import org.ryubing.android.data.GameEntry
import org.ryubing.android.emu.EmulationSession
import org.ryubing.android.input.MotionSensorManager

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
    val showTouchOverlay = remember { mutableStateOf(config.showTouchControls) }
    var showExitMenu by remember { mutableStateOf(false) }

    fun openExitMenu() {
        if (showExitMenu) return
        showExitMenu = true
        session.setPaused(true)
    }

    fun resumeGame() {
        if (!showExitMenu) return
        showExitMenu = false
        session.setPaused(false)
    }

    fun exitToLibrary() {
        showExitMenu = false
        onExit()
    }

    BackHandler {
        if (showExitMenu) resumeGame() else openExitMenu()
    }

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

        session.onShowUiRequested = {
            showTouchOverlay.value = !showTouchOverlay.value
        }

        val motion = if (config.enableMotion) {
            MotionSensorManager(
                context,
                session::setMotion,
                sensitivity = config.motionSensitivity,
            ).also { it.register() }
        } else {
            null
        }

        onDispose {
            motion?.release()
            session.onShowUiRequested = null
            // Stop off the main thread — native Stop joins the GPU loop and ANRs if sync here.
            session.stopAsync()
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
                            // Don't stop emulation here — that blocks the main thread (ANR).
                            // Tear-down runs asynchronously from DisposableEffect.onDispose.
                            session.setSurface(null)
                        }
                    })
                }
            },
        )

        if (config.showPerformanceHud && !showExitMenu) {
            PerformanceHud(
                config = config,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }

        if (showTouchOverlay.value && !showExitMenu) {
            TouchControls(
                modifier = Modifier.fillMaxSize(),
                useSwitchLayout = config.useSwitchLayout,
                scale = config.touchControlsScale,
                stickSensitivity = config.touchStickSensitivity,
                opacity = config.touchControlsOpacity,
                showRightStick = config.showTouchRightStick,
                invertStickY = config.touchInvertStickY,
                onButton = session::setButton,
                onStick = session::setStick,
            )
        }

        if (showExitMenu) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .widthIn(max = 280.dp)
                        .fillMaxWidth(0.4f)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text(game.title, style = MaterialTheme.typography.titleMedium)
                    Text("Paused", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = ::resumeGame,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Resume")
                    }
                    OutlinedButton(
                        onClick = ::exitToLibrary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Exit to library")
                    }
                }
            }
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
