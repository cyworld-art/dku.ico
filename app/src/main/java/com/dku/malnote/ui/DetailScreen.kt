package com.dku.malnote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dku.malnote.data.Recording
import com.dku.malnote.data.RecordingRepository
import com.dku.malnote.data.Segment
import com.dku.malnote.data.TranscriptStatus
import com.dku.malnote.service.TranscriptionService
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit, showBack: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val items by RecordingRepository.items.collectAsStateWithLifecycle()
    val live by TranscriptionService.live.collectAsStateWithLifecycle()
    val rec = items.firstOrNull { it.id == id }
    if (rec == null) {
        EmptyDetail(modifier)
        return
    }
    val liveForThis = live?.takeIf { it.recordingId == id }
    val segments: List<Segment> = if (rec.status == TranscriptStatus.RUNNING && liveForThis != null) liveForThis.segments else rec.segments

    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val player = rememberPlayer(RecordingRepository.audioFile(rec))

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                title = { Text(rec.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "이름 변경") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "더보기") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val hasText = rec.segments.isNotEmpty()
                        DropdownMenuItem(text = { Text("텍스트 복사") }, enabled = hasText, onClick = {
                            menu = false; copy(ctx, rec.toPlainText(false))
                        })
                        DropdownMenuItem(text = { Text("텍스트 공유") }, enabled = hasText, onClick = {
                            menu = false; shareText(ctx, rec.title, rec.toPlainText(false))
                        })
                        DropdownMenuItem(text = { Text("타임스탬프 포함 공유") }, enabled = hasText, onClick = {
                            menu = false; shareText(ctx, rec.title, rec.toPlainText(true))
                        })
                        DropdownMenuItem(text = { Text("자막 파일(SRT) 내보내기") }, enabled = hasText, onClick = {
                            menu = false; shareSrt(ctx, rec)
                        })
                        DropdownMenuItem(text = { Text("오디오 파일 공유") }, onClick = {
                            menu = false; shareAudio(ctx, rec)
                        })
                        DropdownMenuItem(
                            text = { Text("다시 전사하기") },
                            enabled = rec.status != TranscriptStatus.RUNNING && rec.status != TranscriptStatus.QUEUED,
                            onClick = { menu = false; TranscriptionService.enqueue(ctx, rec.id) },
                        )
                        DropdownMenuItem(
                            text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        bottomBar = { PlayerBar(player) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "${formatDate(rec.createdAt)} · ${formatDuration(rec.durationMs)}" +
                    (rec.modelName?.let { " · $it" } ?: "") +
                    (if (rec.status == TranscriptStatus.DONE && rec.transcribeTimeMs > 0) " · 처리 ${formatDuration(rec.transcribeTimeMs)}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            StatusPanel(rec, liveForThis?.phase, liveForThis?.progress)
            TranscriptList(segments, player.positionMs, onSeek = { player.seekAndPlay(it) }, modifier = Modifier.weight(1f))
        }
    }

    if (rename) {
        var text by remember { mutableStateOf(rec.title) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("이름 변경") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    RecordingRepository.update(rec.id) { it.copy(title = text.ifBlank { it.title }) }
                    rename = false
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("취소") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("삭제할까요?") },
            text = { Text("녹음 파일과 전사 내용이 모두 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (rec.status == TranscriptStatus.RUNNING) TranscriptionService.cancel(ctx, rec.id)
                    player.release()
                    RecordingRepository.delete(rec.id)
                    onBack()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun StatusPanel(rec: Recording, phase: String?, progress: Int?) {
    val ctx = LocalContext.current
    when (rec.status) {
        TranscriptStatus.QUEUED, TranscriptStatus.RUNNING -> Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(phase ?: "전사 대기 중", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (progress != null && progress > 0) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$progress%", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TextButton(onClick = { TranscriptionService.cancel(ctx, rec.id) }) { Text("취소") }
            }
        }
        TranscriptStatus.FAILED -> Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("전사 실패", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                Text(rec.error ?: "", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { TranscriptionService.enqueue(ctx, rec.id) }) { Text("다시 시도") }
            }
        }
        TranscriptStatus.NONE, TranscriptStatus.CANCELLED -> Row(Modifier.padding(16.dp)) {
            Button(onClick = { TranscriptionService.enqueue(ctx, rec.id) }) { Text("전사 시작") }
        }
        TranscriptStatus.DONE -> if (rec.segments.isEmpty()) {
            Text("인식된 음성이 없습니다.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun TranscriptList(segments: List<Segment>, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    val active = segments.indexOfLast { it.startMs <= positionMs && positionMs > 0 }
    LaunchedEffect(segments.size) {
        // 전사 중에는 새 문장을 따라 스크롤
        if (segments.isNotEmpty() && positionMs == 0L) listState.animateScrollToItem(segments.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(segments) { i, s ->
            val isActive = i == active
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSeek(s.startMs) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    formatDuration(s.startMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(64.dp).padding(top = 2.dp),
                )
                Text(s.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/* ---------- 오디오 재생 ---------- */

class PlayerHandle(private val file: File) {
    var mp: MediaPlayer? = null
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var dragging by mutableFloatStateOf(-1f)

    private fun ensure(): MediaPlayer? {
        mp?.let { return it }
        if (!file.exists()) return null
        return runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { this@PlayerHandle.playing = false }
            }
        }.getOrNull()?.also { mp = it; durationMs = it.duration.toLong() }
    }

    fun toggle() {
        val p = ensure() ?: return
        if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
    }

    fun seekTo(ms: Long) {
        val p = ensure() ?: return
        p.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        positionMs = ms
    }

    fun seekAndPlay(ms: Long) {
        seekTo(ms)
        mp?.let { if (!it.isPlaying) { it.start(); playing = true } }
    }

    fun tick() {
        mp?.let { if (dragging < 0) positionMs = it.currentPosition.toLong() }
    }

    fun release() {
        mp?.release(); mp = null; playing = false
    }
}

@Composable
private fun rememberPlayer(file: File): PlayerHandle {
    val handle = remember(file.path) { PlayerHandle(file) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    LaunchedEffect(handle, handle.playing) {
        while (handle.playing) { handle.tick(); delay(100) }
        handle.tick()
    }
    return handle
}

@Composable
private fun PlayerBar(p: PlayerHandle) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { p.toggle() }) {
                Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "일시정지" else "재생")
            }
            Spacer(Modifier.width(8.dp))
            val dur = p.durationMs.coerceAtLeast(1)
            Slider(
                value = if (p.dragging >= 0) p.dragging else (p.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { p.dragging = it },
                onValueChangeFinished = { p.seekTo((p.dragging * dur).toLong()); p.dragging = -1f },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${formatDuration(p.positionMs)} / ${formatDuration(p.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/* ---------- 내보내기 ---------- */

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("전사", text))
    Toast.makeText(ctx, "복사했습니다", Toast.LENGTH_SHORT).show()
}

private fun shareText(ctx: Context, title: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "공유"))
}

private fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "공유"))
}

private fun safeFileName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)

private fun shareSrt(ctx: Context, rec: Recording) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, safeFileName(rec.title) + ".srt").apply { writeText(rec.toSrt()) }
    shareFile(ctx, f, "application/x-subrip")
}

private fun shareAudio(ctx: Context, rec: Recording) {
    val src = RecordingRepository.audioFile(rec)
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, safeFileName(rec.title) + "." + src.extension)
    src.copyTo(f, overwrite = true)
    shareFile(ctx, f, if (src.extension == "wav") "audio/wav" else "audio/*")
}
