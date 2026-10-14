package com.tsproxy.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tsproxy.Tsproxy

data class NodesUiState(
    val nodes: List<TailnetNode> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val error: String = "",
    val pingNode: TailnetNode? = null,
    val pingResults: List<NodePingResult> = emptyList(),
    val pingRunning: Boolean = false
)

class NodesViewModel : ViewModel() {
    private val _ui = MutableStateFlow(NodesUiState())
    val ui = _ui.asStateFlow()
    private var running = false
    private var visible = false
    private var refreshJob: Job? = null
    private var pingJob: Job? = null
    private var pingSession: String? = null

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (!value) {
            dismissPing()
            _ui.update { it.copy(nodes = emptyList(), error = "", loading = false) }
        }
        restartPolling()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) dismissPing()
        restartPolling()
    }

    fun setQuery(query: String) { _ui.update { it.copy(query = query) } }

    private fun restartPolling() {
        refreshJob?.cancel()
        if (running && visible) {
            refreshJob = viewModelScope.launch {
                while (isActive) {
                    loadNodes()
                    delay(5000)
                }
            }
        }
    }

    fun refresh() {
        if (running && visible) restartPolling()
    }

    private suspend fun loadNodes() {
        _ui.update { it.copy(loading = true) }
        try {
            val nodes = withContext(Dispatchers.IO) { decodeNodes(Tsproxy.getNodes()) }
            _ui.update { it.copy(nodes = nodes, loading = false, error = "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the previous snapshot, but mark it explicitly as stale in the UI.
            _ui.update { it.copy(loading = false, error = e.message ?: "无法获取节点") }
        }
    }

    fun startPing(node: TailnetNode) {
        val ip = node.pingIP ?: return
        if (!running || !visible || node.self) return
        dismissPing()
        val session = UUID.randomUUID().toString()
        val error = Tsproxy.beginPing(session)
        _ui.update { it.copy(pingNode = node, pingResults = emptyList(), pingRunning = error.isEmpty()) }
        if (error.isNotEmpty()) {
            _ui.update { it.copy(pingResults = listOf(NodePingResult(error = error))) }
            return
        }
        pingSession = session
        pingJob = viewModelScope.launch {
            try {
                repeat(10) { index ->
                    val result = try {
                        withContext(Dispatchers.IO) { decodePing(Tsproxy.pingNode(ip, session)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        NodePingResult(error = e.message ?: "Ping 失败")
                    }
                    _ui.update { it.copy(pingResults = it.pingResults + result) }
                    if (index < 9) delay(1000)
                }
            } finally {
                Tsproxy.cancelPing(session)
                if (pingSession == session) {
                    pingSession = null
                    _ui.update { it.copy(pingRunning = false) }
                }
            }
        }
    }

    fun dismissPing() {
        // Cancel native work before the coroutine, which may be in a blocking JNI call.
        pingSession?.let { Tsproxy.cancelPing(it) }
        pingSession = null
        pingJob?.cancel()
        pingJob = null
        _ui.update { it.copy(pingNode = null, pingResults = emptyList(), pingRunning = false) }
    }

    override fun onCleared() {
        dismissPing()
        super.onCleared()
    }
}
