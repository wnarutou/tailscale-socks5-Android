package tsproxy

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
)

func TestNodesIncludeOfflinePeersButHideStaleAndShareeNodes(t *testing.T) {
	st := &ipnstate.Status{
		Self: &ipnstate.PeerStatus{ID: "self", HostName: "phone", TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")}},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): {ID: "offline", HostName: "NAS", DNSName: "nas.example.ts.net.", OS: "linux", InNetworkMap: true, TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2"), netip.MustParseAddr("fd7a:115c:a1e0::2")}, LastSeen: time.Date(2026, 10, 14, 1, 0, 0, 0, time.UTC)},
			key.NewNode().Public(): {ID: "online", HostName: "desktop", Online: true, InNetworkMap: true, ExitNodeOption: true},
			key.NewNode().Public(): {ID: "stale", HostName: "old", InNetworkMap: false},
			key.NewNode().Public(): {ID: "sharee", HostName: "hidden", InNetworkMap: true, ShareeNode: true},
			key.NewNode().Public(): nil,
		},
	}
	nodes := nodesFromStatus(st)
	if len(nodes) != 3 || nodes[0].ID != "self" || nodes[1].ID != "online" || nodes[2].ID != "offline" {
		t.Fatalf("unexpected visible node ordering: %+v", nodes)
	}
	if !nodes[0].Self || !nodes[1].ExitNode || nodes[2].DNSName != "nas.example.ts.net" || nodes[2].Online || len(nodes[2].IPs) != 2 || nodes[2].LastSeen != "2026-10-14T01:00:00Z" {
		t.Fatalf("lost node metadata: %+v", nodes)
	}
}

func TestNodesWithoutStatusEncodeAsEmptyArray(t *testing.T) {
	data, err := json.Marshal(nodesFromStatus(nil))
	if err != nil || string(data) != "[]" {
		t.Fatalf("empty nodes = %s, %v; want []", data, err)
	}
}

func localTestClient(t *testing.T, handler http.HandlerFunc) *local.Client {
	t.Helper()
	s := httptest.NewServer(handler)
	t.Cleanup(s.Close)
	return &local.Client{OmitAuth: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "tcp", s.Listener.Addr().String())
	}}
}

func TestPingUsesDiscoAndReturnsMeasuredPath(t *testing.T) {
	lc := localTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/localapi/v0/ping" || r.URL.Query().Get("type") != "disco" || r.URL.Query().Get("ip") != "100.64.0.2" {
			t.Errorf("unexpected ping request: %s %s", r.Method, r.URL)
		}
		json.NewEncoder(w).Encode(&ipnstate.PingResult{LatencySeconds: 0.0125, Endpoint: "192.0.2.1:41641"})
	})
	r := pingNode(context.Background(), lc, netip.MustParseAddr("100.64.0.2"))
	if r.Error != "" || r.LatencyMS != 12.5 || r.Connection != "direct" || r.Endpoint != "192.0.2.1:41641" {
		t.Fatalf("unexpected ping result: %+v", r)
	}
}

func TestPingReportsRelaysAndBackendErrors(t *testing.T) {
	for _, tc := range []struct {
		name                  string
		result                ipnstate.PingResult
		connection, errorText string
	}{
		{"derp", ipnstate.PingResult{DERPRegionID: 1, DERPRegionCode: "nyc"}, "derp", ""},
		{"peer relay", ipnstate.PingResult{PeerRelay: "192.0.2.2:1234:vni:1"}, "peer-relay", ""},
		{"backend error", ipnstate.PingResult{Err: "peer is offline"}, "", "peer is offline"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			lc := localTestClient(t, func(w http.ResponseWriter, r *http.Request) { json.NewEncoder(w).Encode(tc.result) })
			r := pingNode(context.Background(), lc, netip.MustParseAddr("100.64.0.2"))
			if r.Connection != tc.connection || r.Error != tc.errorText {
				t.Fatalf("unexpected result: %+v", r)
			}
		})
	}
}

func TestPingCanBeCancelledWhileWaitingForPeer(t *testing.T) {
	started := make(chan struct{})
	lc := localTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan pingResponse, 1)
	go func() { done <- pingNode(ctx, lc, netip.MustParseAddr("100.64.0.2")) }()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("ping did not start")
	}
	cancel()
	select {
	case r := <-done:
		if r.Error == "" {
			t.Fatal("cancelled ping reported success")
		}
	case <-time.After(time.Second):
		t.Fatal("ping ignored cancellation")
	}
}

func TestPingReportsDeadlineAsTimeout(t *testing.T) {
	lc := localTestClient(t, func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() })
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	r := pingNode(ctx, lc, netip.MustParseAddr("100.64.0.2"))
	if r.Error != "Ping timed out" {
		t.Fatalf("timeout result = %+v", r)
	}
}

func TestMobileNodeAPIsReportStoppedAndInvalidInput(t *testing.T) {
	for _, data := range []string{GetNodes(), PingNode("100.64.0.2", "stopped"), PingNode("bad-ip", "invalid"), PingNode("100.64.0.2", "")} {
		var response struct {
			Error string `json:"error"`
		}
		if err := json.Unmarshal([]byte(data), &response); err != nil || response.Error == "" {
			t.Fatalf("expected JSON error, got %s (%v)", data, err)
		}
	}
	CancelPing("missing") // Cancellation is idempotent.
}

func TestCancelledSessionCannotStartQueuedPing(t *testing.T) {
	mu.Lock()
	server, running = &tsnet.Server{}, true
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		cancelPingsLocked()
		server, running = nil, false
		mu.Unlock()
	})
	if err := BeginPing(""); err == "" {
		t.Fatal("empty session ID accepted")
	}
	if err := BeginPing("queued"); err != "" {
		t.Fatal(err)
	}
	if err := BeginPing("queued"); err == "" {
		t.Fatal("duplicate session accepted")
	}
	mu.Lock()
	ctx := pingSessions["queued"].ctx
	mu.Unlock()
	CancelPing("queued")
	if ctx.Err() != context.Canceled {
		t.Fatal("native session was not cancelled")
	}
	var r pingResponse
	json.Unmarshal([]byte(PingNode("100.64.0.2", "queued")), &r)
	if r.Error != "Ping session was cancelled or not started" {
		t.Fatalf("queued ping was not rejected: %+v", r)
	}
}

func TestStopCancelsAllPingSessions(t *testing.T) {
	mu.Lock()
	server, running = &tsnet.Server{}, true
	mu.Unlock()
	t.Cleanup(Stop)
	if err := BeginPing("one"); err != "" {
		t.Fatal(err)
	}
	if err := BeginPing("two"); err != "" {
		t.Fatal(err)
	}
	mu.Lock()
	one, two := pingSessions["one"].ctx, pingSessions["two"].ctx
	// No real tsnet transport was started by this fixture; isolate Stop's
	// session cancellation from tsnet's initialized-server teardown.
	server = nil
	mu.Unlock()
	Stop()
	if one.Err() != context.Canceled || two.Err() != context.Canceled {
		t.Fatal("Stop left ping requests running")
	}
	if IsRunning() {
		t.Fatal("proxy still running after Stop")
	}
	if err := BeginPing("three"); err == "" {
		t.Fatal("stopped proxy accepted ping session")
	}
}
