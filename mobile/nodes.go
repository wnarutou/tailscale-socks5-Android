package tsproxy

import (
	"context"
	"encoding/json"
	"errors"
	"net/netip"
	"sort"
	"strings"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

type nodeInfo struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	DNSName  string   `json:"dnsName"`
	OS       string   `json:"os"`
	IPs      []string `json:"ips"`
	Online   bool     `json:"online"`
	Self     bool     `json:"self"`
	ExitNode bool     `json:"exitNode"`
	Expired  bool     `json:"expired"`
	LastSeen string   `json:"lastSeen"`
}

type nodesResponse struct {
	Nodes []nodeInfo `json:"nodes"`
	Error string     `json:"error"`
}

type pingResponse struct {
	LatencyMS  float64 `json:"latencyMs"`
	Connection string  `json:"connection"`
	Endpoint   string  `json:"endpoint"`
	DERPRegion string  `json:"derpRegion"`
	Error      string  `json:"error"`
}

type pingSession struct {
	ctx    context.Context
	cancel context.CancelFunc
}

// Protected by mu, alongside the server lifecycle. Sessions are registered
// before dispatching native work, so dismissal can cancel even a queued call.
var pingSessions = make(map[string]pingSession)

func encodeResponse(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"error":"Cannot encode Tailscale response"}`
	}
	return string(b)
}

func nodesFromStatus(st *ipnstate.Status) []nodeInfo {
	nodes := make([]nodeInfo, 0)
	if st == nil {
		return nodes
	}
	appendNode := func(p *ipnstate.PeerStatus, self bool) {
		ips := make([]string, 0, len(p.TailscaleIPs))
		for _, ip := range p.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		id := string(p.ID)
		if id == "" {
			id = p.PublicKey.String()
		}
		n := nodeInfo{ID: id, Name: p.HostName, DNSName: strings.TrimSuffix(p.DNSName, "."), OS: p.OS,
			IPs: ips, Online: p.Online, Self: self, ExitNode: p.ExitNodeOption || p.ExitNode, Expired: p.Expired}
		if !p.LastSeen.IsZero() {
			n.LastSeen = p.LastSeen.UTC().Format(time.RFC3339)
		}
		nodes = append(nodes, n)
	}
	if st.Self != nil {
		appendNode(st.Self, true)
	}
	for _, p := range st.Peer {
		if p != nil && p.InNetworkMap && !p.ShareeNode {
			appendNode(p, false)
		}
	}
	sort.Slice(nodes, func(i, j int) bool {
		a, b := nodes[i], nodes[j]
		if a.Self != b.Self {
			return a.Self
		}
		if a.Online != b.Online {
			return a.Online
		}
		if strings.ToLower(a.Name) != strings.ToLower(b.Name) {
			return strings.ToLower(a.Name) < strings.ToLower(b.Name)
		}
		return a.ID < b.ID
	})
	return nodes
}

// GetNodes returns the current device's visible tailnet, including offline peers.
// This uses the embedded LocalAPI; no management API credential is required.
func GetNodes() string {
	mu.Lock()
	srv, ready := server, running
	mu.Unlock()
	r := nodesResponse{Nodes: make([]nodeInfo, 0)}
	if srv == nil || !ready {
		r.Error = "Tailscale is not running"
		return encodeResponse(r)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	lc, err := srv.LocalClient()
	if err == nil {
		var st *ipnstate.Status
		st, err = lc.Status(ctx)
		if err == nil {
			r.Nodes = nodesFromStatus(st)
		}
	}
	if err != nil {
		r.Error = err.Error()
	}
	return encodeResponse(r)
}

// BeginPing reserves a cancellable session before the UI queues blocking work.
// Returns an empty string on success, otherwise an error message.
func BeginPing(requestID string) string {
	mu.Lock()
	defer mu.Unlock()
	if server == nil || !running {
		return "Tailscale is not running"
	}
	if requestID == "" {
		return "Missing ping session ID"
	}
	if _, exists := pingSessions[requestID]; exists {
		return "Ping session already exists"
	}
	ctx, cancel := context.WithCancel(context.Background())
	pingSessions[requestID] = pingSession{ctx: ctx, cancel: cancel}
	return ""
}

// PingNode sends one DISCO ping with a two-second deadline. requestID must have
// been registered with BeginPing. Network waits never hold the lifecycle lock.
func PingNode(ip, requestID string) string {
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return encodeResponse(pingResponse{Error: "Invalid node IP address"})
	}
	mu.Lock()
	srv := server
	session, exists := pingSessions[requestID]
	ready := running
	mu.Unlock()
	if srv == nil || !ready {
		return encodeResponse(pingResponse{Error: "Tailscale is not running"})
	}
	if !exists {
		return encodeResponse(pingResponse{Error: "Ping session was cancelled or not started"})
	}
	ctx, cancel := context.WithTimeout(session.ctx, 2*time.Second)
	defer cancel()
	lc, err := srv.LocalClient()
	if err != nil {
		return encodeResponse(pingResponse{Error: err.Error()})
	}
	return encodeResponse(pingNode(ctx, lc, addr))
}

func pingNode(ctx context.Context, lc *local.Client, addr netip.Addr) pingResponse {
	p, err := lc.Ping(ctx, addr, tailcfg.PingDisco)
	if err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return pingResponse{Error: "Ping timed out"}
		}
		if errors.Is(ctx.Err(), context.Canceled) {
			return pingResponse{Error: "Ping cancelled"}
		}
		return pingResponse{Error: err.Error()}
	}
	if p == nil {
		return pingResponse{Error: "Empty ping response"}
	}
	if p.Err != "" {
		return pingResponse{Error: p.Err}
	}
	r := pingResponse{LatencyMS: p.LatencySeconds * 1000, DERPRegion: p.DERPRegionCode}
	switch {
	case p.Endpoint != "":
		r.Connection, r.Endpoint = "direct", p.Endpoint
	case p.PeerRelay != "":
		r.Connection, r.Endpoint = "peer-relay", p.PeerRelay
	case p.DERPRegionID != 0:
		r.Connection = "derp"
	default:
		r.Connection = "unknown"
	}
	return r
}

// CancelPing is safe to call repeatedly, including before a queued PingNode runs.
func CancelPing(requestID string) {
	mu.Lock()
	defer mu.Unlock()
	if session, exists := pingSessions[requestID]; exists {
		session.cancel()
		delete(pingSessions, requestID)
	}
}

// cancelPingsLocked is called while mu is held, before server teardown.
func cancelPingsLocked() {
	for id, session := range pingSessions {
		session.cancel()
		delete(pingSessions, id)
	}
}
