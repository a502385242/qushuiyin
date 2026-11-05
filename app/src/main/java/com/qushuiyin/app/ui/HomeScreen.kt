package com.qushuiyin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qushuiyin.app.model.Aweme
import com.qushuiyin.app.parser.ParseError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showImport by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("去水印") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "把抖音分享链接粘进来",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "支持整段分享文案。解析后按最高码率保存，不压缩画质。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.input,
                onValueChange = vm::onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = { Text("https://v.douyin.com/...") },
                label = { Text("分享链接") },
                minLines = 4
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = {
                    clipboard.getText()?.text?.let(vm::onInputChange)
                }) { Text("粘贴") }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = vm::parse,
                    enabled = !state.parsing && !state.repairing && state.input.isNotBlank()
                ) {
                    if (state.parsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.parsing) "解析中" else "解析")
                }
            }

            state.error?.let { error ->
                ErrorCard(
                    error = error,
                    repairing = state.repairing,
                    onRepair = vm::repair,
                    onRetry = vm::parse
                )
            }

            state.aweme?.let { ResultCard(it) }

            state.toast?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 镜像全部不可达时的最后一条路：用户手动粘贴规则正文 + 签名。
            // 入口常驻，不只挂在错误卡片里——「拉不到规则」本身就是一种要处理的情况。
            TextButton(onClick = { showImport = true }) {
                Text("手动导入规则", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (showImport) {
        ImportRulesDialog(
            onDismiss = { showImport = false },
            onConfirm = { text, sig ->
                showImport = false
                vm.importRules(text, sig)
            }
        )
    }
}

@Composable
private fun ErrorCard(
    error: ParseError,
    repairing: Boolean,
    onRepair: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 只有「页面结构变了」才给修复按钮，避免误导
                if (error.canRepair) {
                    Button(onClick = onRepair, enabled = !repairing) {
                        if (repairing) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (repairing) "修复中" else "在线修复")
                    }
                }
                if (error.canRetry && !error.canRepair) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(aweme: Aweme) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = aweme.desc.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${aweme.authorName.ifBlank { "未知作者" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (aweme.isImagePost) {
                    "图文作品 · ${aweme.imageUrls.size} 张图"
                } else {
                    "视频 · ${aweme.videoUrls.size} 个清晰度可选"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            aweme.bestVideoUrl?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "下载功能在下一阶段接入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ImportRulesDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var sig by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动导入规则") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "粘贴规则正文（manifest.json 内容）和它的签名（.sig 内容）。" +
                        "签名必须与正文逐字节对应，任意字符改动都会验证失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    label = { Text("规则正文 JSON") },
                    minLines = 4
                )
                OutlinedTextField(
                    value = sig,
                    onValueChange = { sig = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("签名（Base64）") },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, sig) }) { Text("导入并验证") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
