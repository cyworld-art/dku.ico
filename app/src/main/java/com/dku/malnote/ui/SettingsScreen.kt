package com.dku.malnote.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dku.malnote.data.ModelManager
import com.dku.malnote.data.ModelState
import com.dku.malnote.data.SettingsStore
import com.dku.malnote.data.WhisperModel
import com.dku.malnote.whisper.WhisperLib
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val states by ModelManager.states.collectAsStateWithLifecycle()
    val custom by ModelManager.installedCustom.collectAsStateWithLifecycle()
    val active = remember(settings, states) { ModelManager.activeModel()?.name }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            Toast.makeText(ctx, "모델 파일 복사 중…", Toast.LENGTH_SHORT).show()
            ModelManager.import(uri)
                .onSuccess { name -> SettingsStore.update { it.copy(selectedModel = name) } }
                .onFailure { Toast.makeText(ctx, it.message ?: "가져오기 실패", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("설정") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("전사 모델 (Whisper · 오프라인)") }
            item {
                Text(
                    "모델은 한 번만 내려받으면 인터넷 없이 기기 안에서 전사합니다. Wi-Fi 에서 받는 것을 권장합니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            items(WhisperModel.CATALOG, key = { it.fileName }) { m ->
                ModelCard(
                    title = m.displayName,
                    description = m.description,
                    sizeLabel = "약 ${m.sizeMb} MB",
                    recommended = m.recommended,
                    state = states[m.fileName] ?: ModelState.NotInstalled,
                    selected = active == m.fileName,
                    onSelect = { SettingsStore.update { it.copy(selectedModel = m.fileName) } },
                    onDownload = { ModelManager.download(m) },
                    onCancel = { ModelManager.cancelDownload(m.fileName) },
                    onDelete = { ModelManager.delete(m.fileName) },
                )
            }
            items(custom, key = { it }) { name ->
                ModelCard(
                    title = name, description = "직접 가져온 모델", sizeLabel = "", recommended = false,
                    state = states[name] ?: ModelState.NotInstalled,
                    selected = active == name,
                    onSelect = { SettingsStore.update { it.copy(selectedModel = name) } },
                    onDownload = {}, onCancel = {},
                    onDelete = { ModelManager.delete(name) },
                )
            }
            item {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("모델 파일(.bin) 직접 가져오기")
                }
                Text(
                    "한국어로 파인튜닝된 whisper 모델도 ggml 형식(.bin)으로 변환하면 사용할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionTitle("전사 옵션") }
            item {
                Text("CPU 스레드: ${settings.threads}")
                Slider(
                    value = settings.threads.toFloat(),
                    onValueChange = { v -> SettingsStore.update { it.copy(threads = v.toInt()) } },
                    valueRange = 1f..8f, steps = 6,
                )
                Text(
                    "폴드 6 (Snapdragon 8 Gen 3) 은 4~6 이 가장 빠릅니다. 너무 높이면 발열로 오히려 느려질 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            item {
                SwitchRow(
                    "정확도 우선 (빔 서치 5)",
                    "조금 더 정확하지만 전사 시간이 약 2배 걸립니다",
                    settings.beamSize > 1,
                ) { on -> SettingsStore.update { it.copy(beamSize = if (on) 5 else 1) } }
            }
            item {
                SwitchRow("녹음 후 자동 전사", "녹음을 마치면 바로 전사를 시작합니다", settings.autoTranscribe) { on ->
                    SettingsStore.update { it.copy(autoTranscribe = on) }
                }
            }
            item {
                var prompt by remember { mutableStateOf(settings.prompt) }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; SettingsStore.update { s -> s.copy(prompt = it) } },
                    label = { Text("초기 프롬프트 (전문 용어·이름 등)") },
                    supportingText = { Text("자주 나오는 고유명사를 적어두면 인식률이 올라갑니다") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("시스템 정보")
                val info = remember { runCatching { WhisperLib.nativeSystemInfo() }.getOrDefault("-") }
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ModelCard(
    title: String,
    description: String,
    sizeLabel: String,
    recommended: Boolean,
    state: ModelState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, enabled = state is ModelState.Installed)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (recommended) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = {}, label = { Text("추천") })
                    }
                }
                Text(description, style = MaterialTheme.typography.bodySmall)
                when (state) {
                    is ModelState.Downloading -> {
                        Spacer(Modifier.height(4.dp))
                        if (state.total > 0) LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "${formatBytes(state.downloaded)} / ${if (state.total > 0) formatBytes(state.total) else "?"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    is ModelState.Installed -> Text("설치됨 · ${formatBytes(state.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    is ModelState.Failed -> Text(state.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    ModelState.NotInstalled -> if (sizeLabel.isNotEmpty()) Text(sizeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            when (state) {
                is ModelState.Downloading -> TextButton(onClick = onCancel) { Text("취소") }
                is ModelState.Installed -> TextButton(onClick = onDelete) { Text("삭제") }
                else -> TextButton(onClick = onDownload) { Text("받기") }
            }
        }
    }
}
