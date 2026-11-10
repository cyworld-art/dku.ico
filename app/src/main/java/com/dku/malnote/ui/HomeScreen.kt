package com.dku.malnote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dku.malnote.data.ModelManager
import com.dku.malnote.data.Recording
import com.dku.malnote.data.RecordingRepository
import com.dku.malnote.data.TranscriptStatus
import com.dku.malnote.service.LiveTranscription
import com.dku.malnote.service.TranscriptionService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedId: String?,
    onOpen: (String) -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by RecordingRepository.items.collectAsStateWithLifecycle()
    val live by TranscriptionService.live.collectAsStateWithLifecycle()
    val modelStates by ModelManager.states.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val hasModel = remember(modelStates) { ModelManager.activeModel() != null }

    val filtered = remember(items, query) {
        if (query.isBlank()) items
        else items.filter { it.title.contains(query, true) || it.segments.any { s -> s.text.contains(query, true) } }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("말노트") },
                actions = {
                    IconButton(onClick = onImport) { Icon(Icons.Filled.FileUpload, "파일 가져오기") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Filled.Mic, null) },
                text = { Text("녹음") },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!hasModel) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("전사 모델이 없습니다", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "처음 한 번 Whisper 모델을 내려받아야 기기 안에서(오프라인) 전사할 수 있습니다. " +
                                "폴드 6 에는 Large-v3 Turbo 를 추천합니다.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onSettings) { Text("모델 내려받기") }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("제목·내용 검색") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.GraphicEq, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (items.isEmpty()) "녹음 버튼을 눌러 시작하세요" else "검색 결과가 없습니다",
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { rec ->
                        RecordingCard(rec, live?.takeIf { it.recordingId == rec.id }, rec.id == selectedId) { onOpen(rec.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(rec: Recording, live: LiveTranscription?, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(rec.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatDate(rec.createdAt)} · ${formatDuration(rec.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(8.dp))
                StatusLabel(rec.status)
            }
            if (live != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { live.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            val preview = rec.segments.take(3).joinToString(" ") { it.text.trim() }
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun StatusLabel(status: TranscriptStatus) {
    val (text, color) = when (status) {
        TranscriptStatus.NONE -> "전사 전" to MaterialTheme.colorScheme.outline
        TranscriptStatus.QUEUED -> "대기 중" to MaterialTheme.colorScheme.secondary
        TranscriptStatus.RUNNING -> "전사 중" to MaterialTheme.colorScheme.primary
        TranscriptStatus.DONE -> "완료" to MaterialTheme.colorScheme.secondary
        TranscriptStatus.FAILED -> "실패" to MaterialTheme.colorScheme.error
        TranscriptStatus.CANCELLED -> "취소됨" to MaterialTheme.colorScheme.outline
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
fun EmptyDetail(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("왼쪽에서 녹음을 선택하세요", color = MaterialTheme.colorScheme.outline)
    }
}
