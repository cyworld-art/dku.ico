package com.dku.malnote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dku.malnote.service.RecorderState
import com.dku.malnote.service.RecordingService

@Composable
fun RecorderScreen(state: RecorderState) {
    val ctx = LocalContext.current
    val barColor = MaterialTheme.colorScheme.primary
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (state.paused) "일시정지됨" else "녹음 중",
                style = MaterialTheme.typography.titleMedium,
                color = if (state.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(8.dp))
            Text(formatDuration(state.elapsedMs), fontSize = 56.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(32.dp))
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val n = 120
                val w = size.width / n
                val levels = state.levels
                val offset = n - levels.size
                levels.forEachIndexed { i, v ->
                    val h = maxOf(4f, v * size.height)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset((offset + i) * w + w * 0.2f, (size.height - h) / 2),
                        size = Size(w * 0.6f, h),
                        cornerRadius = CornerRadius(w * 0.3f),
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { if (state.paused) RecordingService.resume(ctx) else RecordingService.pause(ctx) },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.paused) "계속" else "일시정지")
                }
                FilledIconButton(
                    onClick = { RecordingService.stop(ctx) },
                    modifier = Modifier.size(88.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                ) {
                    Icon(Icons.Filled.Stop, "중지", Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "화면을 꺼도 녹음은 계속됩니다. 중지하면 자동으로 전사를 시작합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
