package com.dku.malnote.ui

import android.Manifest
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dku.malnote.data.RecordingRepository
import com.dku.malnote.service.RecordingService
import com.dku.malnote.service.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Route {
    data object Home : Route
    data object Recorder : Route
    data class Detail(val id: String) : Route
    data object Settings : Route
}

@Composable
fun MalNoteRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val top = stack.last()
    val recorder by RecordingService.state.collectAsStateWithLifecycle()

    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // 녹음 중이면 녹음 화면으로, 녹음이 끝나면 해당 녹음 상세로 이동
    LaunchedEffect(recorder.active, recorder.lastFinishedId) {
        if (recorder.active && top != Route.Recorder) push(Route.Recorder)
        if (!recorder.active && top == Route.Recorder) {
            pop()
            recorder.lastFinishedId?.let { push(Route.Detail(it)) }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            RecordingService.start(ctx)
        } else {
            Toast.makeText(ctx, "녹음하려면 마이크 권한이 필요합니다", Toast.LENGTH_LONG).show()
        }
    }
    val startRecording = {
        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val id = withContext(Dispatchers.IO) { runCatching { importAudio(ctx, uri) }.getOrNull() }
            if (id == null) {
                Toast.makeText(ctx, "파일을 가져오지 못했습니다", Toast.LENGTH_LONG).show()
            } else {
                TranscriptionService.enqueue(ctx, id)
                push(Route.Detail(id))
            }
        }
    }
    val importAudio = { importLauncher.launch(arrayOf("audio/*", "video/*")) }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 갤럭시 Z 폴드 펼침 화면(약 840dp 폭)에서는 목록 + 상세 2단 레이아웃
            val wide = maxWidth >= 700.dp
            when {
                top == Route.Recorder -> RecorderScreen(recorder)
                top == Route.Settings -> SettingsScreen(onBack = ::pop)
                wide && (top == Route.Home || top is Route.Detail) -> {
                    val selected = (top as? Route.Detail)?.id
                    Row(Modifier.fillMaxSize()) {
                        HomeScreen(
                            modifier = Modifier.width(maxWidth * 0.4f).fillMaxHeight(),
                            selectedId = selected,
                            onOpen = { id -> if (top is Route.Detail) stack[stack.lastIndex] = Route.Detail(id) else push(Route.Detail(id)) },
                            onRecord = startRecording,
                            onImport = importAudio,
                            onSettings = { push(Route.Settings) },
                        )
                        VerticalDivider()
                        if (selected != null) {
                            DetailScreen(id = selected, onBack = ::pop, showBack = false, modifier = Modifier.weight(1f))
                        } else {
                            EmptyDetail(Modifier.weight(1f))
                        }
                    }
                }
                top is Route.Detail -> DetailScreen(id = top.id, onBack = ::pop, showBack = true)
                else -> HomeScreen(
                    selectedId = null,
                    onOpen = { push(Route.Detail(it)) },
                    onRecord = startRecording,
                    onImport = importAudio,
                    onSettings = { push(Route.Settings) },
                )
            }
        }
    }
}

/** 외부 오디오/동영상 파일을 앱 저장소로 복사해 새 녹음으로 등록 */
private fun importAudio(ctx: Context, uri: Uri): String {
    val cr = ctx.contentResolver
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "가져온 파일"
    val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "audio"
    val rec = RecordingRepository.newRecording(name.substringBeforeLast('.'), "source.$ext")
    val dst = RecordingRepository.audioFile(rec)
    cr.openInputStream(uri)!!.use { inp -> dst.outputStream().use { inp.copyTo(it, 1 shl 20) } }
    val mmr = MediaMetadataRetriever()
    val duration = try {
        mmr.setDataSource(dst.absolutePath)
        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        mmr.release()
    }
    RecordingRepository.save(rec.copy(durationMs = duration))
    return rec.id
}
