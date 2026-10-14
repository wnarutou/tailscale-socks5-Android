package com.tsproxy.android.ui

import org.json.JSONObject

data class TailnetNode(
    val id: String,
    val name: String,
    val dnsName: String = "",
    val os: String = "",
    val ips: List<String> = emptyList(),
    val online: Boolean = false,
    val self: Boolean = false,
    val exitNode: Boolean = false,
    val expired: Boolean = false,
    val lastSeen: String = ""
) {
    val displayName: String get() = name.ifBlank { dnsName.ifBlank { ips.firstOrNull() ?: id } }
    val pingIP: String? get() = ips.firstOrNull { !it.contains(':') } ?: ips.firstOrNull()
    fun matches(query: String): Boolean = query.isBlank() ||
        listOf(name, dnsName, os, *ips.toTypedArray()).any { it.contains(query.trim(), ignoreCase = true) }
}

data class NodePingResult(
    val latencyMs: Double = 0.0,
    val connection: String = "",
    val endpoint: String = "",
    val derpRegion: String = "",
    val error: String = ""
) {
    val pathLabel: String get() = when (connection) {
        "direct" -> "直连"
        "derp" -> "DERP 中继" + if (derpRegion.isBlank()) "" else " · $derpRegion"
        "peer-relay" -> "节点中继"
        else -> "路径未知"
    }
}

fun decodeNodes(raw: String): List<TailnetNode> {
    val response = JSONObject(raw)
    val error = response.optString("error")
    check(error.isEmpty()) { error }
    val nodes = response.getJSONArray("nodes")
    return List(nodes.length()) { index ->
        val node = nodes.getJSONObject(index)
        val ips = node.getJSONArray("ips")
        TailnetNode(
            id = node.getString("id"), name = node.optString("name"),
            dnsName = node.optString("dnsName"), os = node.optString("os"),
            ips = List(ips.length()) { ips.getString(it) },
            online = node.optBoolean("online"), self = node.optBoolean("self"),
            exitNode = node.optBoolean("exitNode"), expired = node.optBoolean("expired"),
            lastSeen = node.optString("lastSeen")
        )
    }
}

fun decodePing(raw: String): NodePingResult {
    val response = JSONObject(raw)
    val error = response.optString("error")
    if (error.isNotEmpty()) return NodePingResult(error = error)
    return NodePingResult(
        latencyMs = response.getDouble("latencyMs"),
        connection = response.getString("connection"),
        endpoint = response.optString("endpoint"), derpRegion = response.optString("derpRegion")
    )
}
