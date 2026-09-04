package org.ryubing.android.ui

import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.ryubing.android.RyubingNative
import org.ryubing.android.data.EmulatorConfig

/**
 * Eden-style in-game performance HUD. Each stat is independently toggleable via [EmulatorConfig].
 * Polls [RyubingNative.Core.ryubing_get_performance_stats] at ~4 Hz.
 */
@Composable
fun PerformanceHud(
    config: EmulatorConfig,
    modifier: Modifier = Modifier,
) {
    if (!config.showPerformanceHud || !config.anyHudStatEnabled) return

    var line by remember { mutableStateOf("—") }

    LaunchedEffect(
        config.hudShowFps,
        config.hudShowFrameTime,
        config.hudShowFifo,
        config.hudShowCpuBackend,
        config.hudShowMemory,
        config.hudShowGpu,
    ) {
        val gameFps = DoubleByReference()
        val frameTimeMs = DoubleByReference()
        val fifoPercent = DoubleByReference()
        val usingNce = IntByReference()

        while (isActive) {
            val ok = try {
                RyubingNative.core.ryubing_get_performance_stats(
                    gameFps,
                    frameTimeMs,
                    fifoPercent,
                    usingNce,
                ) != 0
            } catch (_: Throwable) {
                false
            }

            val parts = mutableListOf<String>()
            if (ok) {
                val fps = gameFps.value
                if (config.hudShowFps) {
                    parts += if (fps > 0.0) String.format("%.1f FPS", fps) else "— FPS"
                }
                if (config.hudShowFrameTime) {
                    parts += if (fps > 0.0) {
                        String.format("%.1f ms", frameTimeMs.value)
                    } else {
                        "— ms"
                    }
                }
                if (config.hudShowFifo) {
                    parts += String.format("FIFO %.0f%%", fifoPercent.value)
                }
                if (config.hudShowCpuBackend) {
                    parts += if (usingNce.value != 0) "NCE" else "JIT"
                }
                if (config.hudShowMemory) {
                    parts += "${Debug.getPss() / 1024L} MB"
                }
                if (config.hudShowGpu) {
                    parts += "Vulkan"
                }
            } else if (config.hudShowFps) {
                parts += "— FPS"
            }

            line = parts.joinToString(" • ")
            delay(250)
        }
    }

    if (line.isBlank()) return

    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = line,
            color = Color(0xFFE8D5FF),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private val EmulatorConfig.anyHudStatEnabled: Boolean
    get() = hudShowFps || hudShowFrameTime || hudShowFifo || hudShowCpuBackend ||
        hudShowMemory || hudShowGpu
