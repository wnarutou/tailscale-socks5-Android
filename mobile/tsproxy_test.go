package tsproxy

import "testing"

func TestMobileSocksTimeoutsAreDisabled(t *testing.T) {
	if mobileTCPTimeout != 0 {
		t.Fatalf("mobile TCP timeout = %d, want 0 (disabled)", mobileTCPTimeout)
	}
	if mobileUDPTimeout != 0 {
		t.Fatalf("mobile UDP timeout = %d, want 0 (disabled)", mobileUDPTimeout)
	}
}

func TestMobileSocksDebugLoggingRemainsEnabled(t *testing.T) {
	if !mobileSocksDebug {
		t.Fatal("mobile SOCKS debug logging must remain enabled")
	}
}
