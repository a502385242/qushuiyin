package com.qushuiyin.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qushuiyin.app.BuildConfig
import com.qushuiyin.app.model.Aweme
import com.qushuiyin.app.parser.ParseError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }

    // Android 9 及以下要 WRITE_EXTERNAL_STORAGE 才能进相册，10 起走 MediaStore 不需要
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.download() else vm.showToast("没有存储权限，无法保存到相册")
    }

    val startDownload: () -> Unit = {
        val needPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.download()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("去水印")
                        // 版本号必须常显：用户判断「到底有没有装上更新」只靠这一行
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
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

            state.stage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            state.error?.let { error ->
                ErrorCard(
                    error = error,
                    repairing = state.repairing,
                    diagnostic = state.diagnostic,
                    onRepair = vm::repair,
                    onRetry = vm::parse
                )
            }

            state.aweme?.let { aweme ->
                ResultCard(
                    aweme = aweme,
                    downloading = state.downloading,
                    progress = state.downloadProgress,
                    progressText = state.downloadText,
                    savedTo = state.savedTo,
                    onDownload = startDownload
                )
            }

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
    diagnostic: String?,
    onRepair: () -> Unit,
    onRetry: () -> Unit
) {
    var showDiagnostic by remember { mutableStateOf(false) }

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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Spacer(Modifier.weight(1f))
                // 手机上没法抓 logcat，这段记录就是唯一能拿到的现场
                if (!diagnostic.isNullOrBlank()) {
                    TextButton(onClick = { showDiagnostic = true }) { Text("诊断信息") }
                }
            }
        }
    }

    if (showDiagnostic && !diagnostic.isNullOrBlank()) {
        DiagnosticDialog(text = diagnostic, onDismiss = { showDiagnostic = false })
    }
}

@Composable
private fun DiagnosticDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断信息") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun ResultCard(
    aweme: Aweme,
    downloading: Boolean,
    progress: Float?,
    progressText: String?,
    savedTo: String?,
    onDownload: () -> Unit
) {
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
                    "视频 · 最高画质（无水印）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onDownload,
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        downloading -> "保存中"
                        aweme.isImagePost -> "保存 ${aweme.imageUrls.size} 张图到相册"
                        else -> "保存视频到相册"
                    }
                )
            }

            if (downloading) {
                // 总长未知（没有 Content-Length）时不传进度值，画成不确定态，
                // 免得用户看到一根永远停在 0 的进度条以为卡死
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (downloading && !progressText.isNullOrBlank()) {
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            savedTo?.let {
                Text(
                    text = "已保存到相册：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
