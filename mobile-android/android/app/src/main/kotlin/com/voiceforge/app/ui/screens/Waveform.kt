package com.voiceforge.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.voiceforge.app.audio.PcmDecoder
import com.voiceforge.app.util.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class WaveformData(val peaks: FloatArray, val seconds: Double)

/**
 * Canvas waveform: ~200 max-abs peaks decoded from [file] (mono PCM16 at
 * 8 kHz, capped at 60 s), bars in the primary color and a playhead in the
 * secondary color driven by [progress] (0..1 of the whole file).
 *
 * When the audio is longer than the decoded window, the bars occupy the
 * matching left fraction so the playhead stays time-accurate.
 */
@Composable
fun WaveformBar(file: File, progress: Float, totalSeconds: Double) {
    val data by produceState<WaveformData?>(initialValue = null, key1 = file) {
        value = withContext(Dispatchers.IO) {
            val pcm = PcmDecoder.decodePcm(file)
            WaveformData(Waveform.peaks(pcm, 200), pcm.size / PcmDecoder.DEFAULT_HZ.toDouble())
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (data == null) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            val d = data!!
            val window = if (totalSeconds > 0.0) (d.seconds / totalSeconds).coerceIn(0.0, 1.0) else 1.0
            val primary = MaterialTheme.colorScheme.primary
            val secondary = MaterialTheme.colorScheme.secondary
            Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                val bars = d.peaks
                if (bars.isEmpty()) return@Canvas
                val step = size.width / bars.size
                val windowW = size.width * window.toFloat()
                val stroke = (step * 0.7f).coerceAtLeast(1f)
                for (i in bars.indices) {
                    val cx = i * step + step / 2f
                    if (cx > windowW) break
                    val h = (bars[i] * size.height * 0.9f).coerceAtLeast(2f)
                    drawLine(
                        color = primary,
                        start = Offset(cx, (size.height - h) / 2f),
                        end = Offset(cx, (size.height + h) / 2f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                val x = progress.coerceIn(0f, 1f) * size.width
                drawLine(
                    color = secondary,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
    }
}
