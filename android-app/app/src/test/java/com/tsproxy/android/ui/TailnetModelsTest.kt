package com.tsproxy.android.ui

import org.junit.Assert.*
import org.junit.Test

class TailnetModelsTest {
    @Test fun parsesOfflineIPv6NodeAndSearchesAllAddresses() {
        val nodes = decodeNodes("""{"error":"","nodes":[{"id":"nas","name":"NAS","dnsName":"nas.example.ts.net","os":"linux","ips":["fd7a:115c:a1e0::2"],"online":false,"self":false,"exitNode":true,"expired":false,"lastSeen":"2026-10-14T01:00:00Z"}]}""")
        val node = nodes.single()
        assertEquals("fd7a:115c:a1e0::2", node.pingIP)
        assertFalse(node.online)
        assertTrue(node.exitNode)
        assertTrue(node.matches("nAs"))
        assertTrue(node.matches("example.ts.net"))
        assertTrue(node.matches("::2"))
        assertFalse(node.matches("windows"))
    }

    @Test fun prefersIPv4ForPingWhileRetainingIPv6() {
        val node = TailnetNode(id = "dual", name = "dual", ips = listOf("fd7a:115c:a1e0::2", "100.64.0.2"))
        assertEquals("100.64.0.2", node.pingIP)
        assertEquals(2, node.ips.size)
    }

    @Test fun backendErrorIsNotAnEmptySuccessfulList() {
        try {
            decodeNodes("""{"error":"Tailscale is not running","nodes":[]}""")
            fail("backend error was ignored")
        } catch (e: IllegalStateException) {
            assertEquals("Tailscale is not running", e.message)
        }
    }

    @Test fun pingPreservesDecimalLatencyAndRelayDetails() {
        val result = decodePing("""{"latencyMs":12.5,"connection":"derp","endpoint":"","derpRegion":"hkg","error":""}""")
        assertEquals(12.5, result.latencyMs, 0.0001)
        assertEquals("DERP 中继 · hkg", result.pathLabel)
        assertEquals("peer is offline", decodePing("""{"error":"peer is offline"}""").error)
    }

    @Test fun malformedResponseCannotLookLikeZeroLatencySuccess() {
        try {
            decodePing("""{}""")
            fail("missing ping fields were treated as success")
        } catch (_: org.json.JSONException) { }
    }
}
