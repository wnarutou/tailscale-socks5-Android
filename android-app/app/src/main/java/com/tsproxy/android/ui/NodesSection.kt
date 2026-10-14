package com.tsproxy.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun NodesSection(running: Boolean, vm: NodesViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(running) { vm.setRunning(running) }
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, _ ->
            vm.setVisible(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(observer)
        vm.setVisible(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            vm.setVisible(false)
        }
    }
    val filtered = remember(ui.nodes, ui.query) { ui.nodes.filter { it.matches(ui.query) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("网络节点 (${ui.nodes.size})", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = vm::refresh, enabled = running && !ui.loading) { Text("刷新") }
            }
            Text("显示当前设备可见的节点，受 Tailnet 访问策略限制。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!running) {
                Text("启动并登录 Tailscale 后显示节点。", style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedTextField(value = ui.query, onValueChange = vm::setQuery,
                    label = { Text("搜索设备名、IP 或系统") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (ui.error.isNotEmpty()) {
                    Text("刷新失败：${ui.error}" + if (ui.nodes.isEmpty()) "" else "\n以下为上次获取的数据。",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (filtered.isEmpty() && !ui.loading && ui.error.isEmpty()) {
                    Text(if (ui.query.isBlank()) "暂无可见节点" else "没有匹配的节点")
                }
                if (filtered.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.id }) { node ->
                            NodeRow(node, onPing = { vm.startPing(node) })
                        }
                    }
                }
            }
        }
    }
    ui.pingNode?.let { node ->
        PingDialog(node, ui.pingResults, ui.pingRunning, vm::dismissPing, { vm.startPing(node) })
    }
}

@Composable
private fun NodeRow(node: TailnetNode, onPing: () -> Unit) {
    val ctx = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(node.displayName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(if (node.expired) "密钥过期" else if (node.online) "在线" else "离线",
                    color = if (node.online && !node.expired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
            val details = listOfNotNull(node.os.ifBlank { null }, if (node.self) "本机" else null,
                if (node.exitNode) "出口节点" else null).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
            if (node.dnsName.isNotEmpty()) Text(node.dnsName, style = MaterialTheme.typography.bodySmall)
            node.ips.forEach { ip ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ip, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Tailscale IP", ip))
                        Toast.makeText(ctx, "已复制 IP", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                }
            }
            if (!node.online && node.lastSeen.isNotEmpty()) {
                val lastSeen = remember(node.lastSeen) {
                    runCatching {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                            .format(Instant.parse(node.lastSeen))
                    }.getOrDefault(node.lastSeen)
                }
                Text("最后在线：$lastSeen", style = MaterialTheme.typography.bodySmall)
            }
            if (!node.self) {
                OutlinedButton(onClick = onPing, enabled = node.pingIP != null) { Text("Ping") }
            }
        }
    }
}

@Composable
private fun PingDialog(
    node: TailnetNode, results: List<NodePingResult>, running: Boolean,
    onDismiss: () -> Unit, onRetry: () -> Unit
) {
    val successes = results.filter { it.error.isEmpty() }
    val listState = rememberLazyListState()
    LaunchedEffect(results.size) {
        if (results.isNotEmpty()) listState.animateScrollToItem(results.lastIndex)
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Ping · ${node.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(node.pingIP.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text("${results.size}/10 次 · 成功 ${successes.size} 次")
                if (successes.isNotEmpty()) {
                    Text("平均 ${String.format(Locale.ROOT, "%.1f", successes.map { it.latencyMs }.average())} ms")
                }
                Text("检测 Tailscale 连接路径；成功不代表业务端口可访问。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results.size) { index ->
                        val result = results[index]
                        Column {
                            if (result.error.isNotEmpty()) {
                                Text("${index + 1}. ${result.error}", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("${index + 1}. ${String.format(Locale.ROOT, "%.1f", result.latencyMs)} ms · ${result.pathLabel}")
                                if (result.endpoint.isNotEmpty()) Text(result.endpoint, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (running) "停止并关闭" else "关闭") } },
        dismissButton = { TextButton(onClick = onRetry, enabled = !running) { Text("重新 Ping") } }
    )
}
