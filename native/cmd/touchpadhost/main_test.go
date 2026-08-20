package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestWebSocketAcceptRFCExample(t *testing.T) {
	got := websocketAccept("dGhlIHNhbXBsZSBub25jZQ==")
	want := "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
	if got != want {
		t.Fatalf("accept=%q want %q", got, want)
	}
}

func TestPrivateIP(t *testing.T) {
	cases := map[string]bool{
		"127.0.0.1":            true,
		"10.0.0.7":             true,
		"172.16.0.1":           true,
		"172.31.255.254":       true,
		"172.32.0.1":           false,
		"192.168.1.2":          true,
		"8.8.8.8":              false,
		"::1":                  true,
		"fe80::1":              true,
		"fd00::1":              true,
		"2001:4860:4860::8888": false,
	}
	for raw, want := range cases {
		if got := isPrivateIP(net.ParseIP(raw)); got != want {
			t.Errorf("isPrivateIP(%s)=%v want %v", raw, got, want)
		}
	}
}

func TestClamp32(t *testing.T) {
	if got := clamp32(999, -10, 10); got != 10 {
		t.Fatalf("got %d", got)
	}
	if got := clamp32(-999, -10, 10); got != -10 {
		t.Fatalf("got %d", got)
	}
	if got := clamp32(3, -10, 10); got != 3 {
		t.Fatalf("got %d", got)
	}
}

func TestAllowedOrigin(t *testing.T) {
	s := &server{}
	cases := []struct {
		origin, host string
		want         bool
	}{
		{"http://192.168.1.20:51876", "192.168.1.20:51877", true},
		{"http://phoneinput.local:51876", "phoneinput.local:51877", true},
		{"https://evil.example", "192.168.1.20:51877", false},
		{"file://local", "192.168.1.20:51877", false},
	}
	for _, c := range cases {
		if got := s.allowedOrigin(c.origin, c.host); got != c.want {
			t.Errorf("allowedOrigin(%q,%q)=%v want %v", c.origin, c.host, got, c.want)
		}
	}
}

func TestSameOrigin(t *testing.T) {
	cases := []struct {
		origin, host string
		want         bool
	}{
		{"", "192.168.1.20:51877", true},
		{"http://192.168.1.20:51877", "192.168.1.20:51877", true},
		{"http://192.168.1.20:51876", "192.168.1.20:51877", false},
		{"https://192.168.1.20:51877", "192.168.1.20:51877", true},
		{"https://evil.example", "192.168.1.20:51877", false},
	}
	for _, c := range cases {
		if got := sameOrigin(c.origin, c.host); got != c.want {
			t.Errorf("sameOrigin(%q,%q)=%v want %v", c.origin, c.host, got, c.want)
		}
	}
}

func TestEmbeddedTouchpadPagePreview9(t *testing.T) {
	page := string(touchpadHTML)
	markers := []string{
		"v1.3.0-preview.9",
		"grid-template-rows:auto minmax(0,1fr)",
		"value=\"2.3\"",
		"id=\"acceleration\"",
		"touchpadSettingsRevision','3'",
		"TwoFingerPending",
		"TwoFingerHold",
		"two_finger_hold",
		"id=\"keyboardButton\"",
		"id=\"legacyInputButton\"",
		"href=\"/input/\"",
		"id=\"topWindows\"",
		"setInterval(()=>{if(ws&&ws.readyState===WebSocket.OPEN)send({type:'ping'})},25000)",
		"ensureConnected",
		"dragArmTimer",
		"PressDrag",
		"delay=gesture.secondTap?150:220",
		"pointer.maxMove<=7",
		"total<=12",
		"data-pad-shortcut=\"screenshot\"",
		"data-pad-shortcut=\"copy\"",
		"data-pad-shortcut=\"paste\"",
		"/api/hotkey/",
		"data-inline-window=\"chatgpt\"",
		"data-inline-window=\"chrome\"",
		"data-inline-window=\"wechat\"",
		"id=\"inertiaEnabled\"",
		"/assets/input-component.js",
		"/assets/input-component.css",
	}
	for _, marker := range markers {
		if !strings.Contains(page, marker) {
			t.Fatalf("embedded touchpad page missing %q", marker)
		}
	}
	for _, forbidden := range []string{"<iframe", "ThreeFingerPending", "beginTouchThree", "coreInputFrame", `id="windowButton"`, `id="padWindowButton"`, `id="padTools"`, "任务切换"} {
		if strings.Contains(page, forbidden) {
			t.Fatalf("touchpad page must not contain legacy iframe/three-finger implementation %q", forbidden)
		}
	}
	component := string(inputComponentJS)
	for _, marker := range []string{"embeddedText", "/core-api/input-state", "/core-api/selection", "/core-api/window-switch/", "compositionstart", "compositionend", "send-and-enter", "screenshot", "keyboard-dismissed", "embeddedBottomDismiss"} {
		if !strings.Contains(component, marker) {
			t.Fatalf("embedded input component missing %q", marker)
		}
	}
}

func TestTransformCoreInputHTML(t *testing.T) {
	raw := []byte(`<!doctype html><html><head><link rel="manifest" href="/manifest.webmanifest"></head><body><button id="touchpad"></button><script>fetch('/api/status');navigator.serviceWorker.register('/sw.js').catch(()=>{});location.href='http://'+location.hostname+':51877/';/*default-touchpad-home*/  </script></body></html>`)
	page := string(transformCoreInputHTML(raw))
	for _, marker := range []string{"/core-api/status", "phoneinput-touchpad-compat", "location.href='/'"} {
		if !strings.Contains(page, marker) {
			t.Fatalf("transformed compatibility page missing %q: %s", marker, page)
		}
	}
	if strings.Contains(page, "/manifest.webmanifest") || strings.Contains(page, "serviceWorker.register") || strings.Contains(page, "postMessage") {
		t.Fatalf("compatibility page must stay standalone and must not install a service worker: %s", page)
	}
}

func TestAllowedCoreAPI(t *testing.T) {
	cases := []struct {
		path, method string
		want         bool
	}{
		{"/api/status", http.MethodGet, true},
		{"/api/input-state", http.MethodGet, true},
		{"/api/text", http.MethodPost, true},
		{"/api/selection", http.MethodPost, true},
		{"/api/key/shift-enter", http.MethodPost, true},
		{"/api/window-switch/chatgpt", http.MethodPost, true},
		{"/api/status", http.MethodPost, false},
		{"/api/unknown", http.MethodGet, false},
	}
	for _, tc := range cases {
		if got := allowedCoreAPI(tc.path, tc.method); got != tc.want {
			t.Errorf("allowedCoreAPI(%q,%q)=%v want %v", tc.path, tc.method, got, tc.want)
		}
	}
}

func TestHandleTextProxiesValidatedRequest(t *testing.T) {
	var calls atomic.Int32
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/api/text" || r.Method != http.MethodPost {
			t.Fatalf("unexpected core request %s %s", r.Method, r.URL.Path)
		}
		var payload textRequest
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatalf("decode core request: %v", err)
		}
		if payload.Text != "中文🙂\nline two" {
			t.Fatalf("text changed: %q", payload.Text)
		}
		if payload.DelayMS != 15 {
			t.Fatalf("delay not clamped: %d", payload.DelayMS)
		}
		if payload.EnterAfter {
			t.Fatal("touchpad proxy must force enterAfter=false")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"ok":true}`)
	}))
	defer core.Close()

	s := newTestServer(core.URL)
	req := httptest.NewRequest(http.MethodPost, "http://192.168.1.20:51877/api/text", strings.NewReader(`{"text":"中文🙂\nline two","delayMs":99,"enterAfter":true}`))
	req.Host = "192.168.1.20:51877"
	req.RemoteAddr = "192.168.1.8:45678"
	req.Header.Set("Origin", "http://192.168.1.20:51877")
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	recorder := httptest.NewRecorder()
	s.handleText(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	if calls.Load() != 1 {
		t.Fatalf("core calls=%d", calls.Load())
	}
}

func TestHandleTextRejectsTooLongWithoutCoreCall(t *testing.T) {
	var calls atomic.Int32
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }))
	defer core.Close()
	s := newTestServer(core.URL)

	body := `{"text":"` + strings.Repeat("字", maxTextCharacters+1) + `","delayMs":6,"enterAfter":false}`
	req := httptest.NewRequest(http.MethodPost, "http://192.168.1.20:51877/api/text", strings.NewReader(body))
	req.Host = "192.168.1.20:51877"
	req.RemoteAddr = "192.168.1.8:45678"
	req.Header.Set("Origin", "http://192.168.1.20:51877")
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	s.handleText(recorder, req)

	if recorder.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	if calls.Load() != 0 {
		t.Fatalf("core should not be called, got %d", calls.Load())
	}
}

func TestHandleTextRejectsCrossOrigin(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	req := httptest.NewRequest(http.MethodPost, "http://192.168.1.20:51877/api/text", strings.NewReader(`{"text":"x","delayMs":6,"enterAfter":false}`))
	req.Host = "192.168.1.20:51877"
	req.RemoteAddr = "192.168.1.8:45678"
	req.Header.Set("Origin", "http://192.168.1.99:9999")
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	s.handleText(recorder, req)
	if recorder.Code != http.StatusForbidden {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestHandleHotkeyCopyPasteVoice(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	for _, hotkey := range []string{"copy", "paste", "voice"} {
		req := httptest.NewRequest(http.MethodPost, "http://192.168.1.20:51877/api/hotkey/"+hotkey, nil)
		req.Host = "192.168.1.20:51877"
		req.RemoteAddr = "192.168.1.8:45678"
		req.Header.Set("Origin", "http://192.168.1.20:51877")
		recorder := httptest.NewRecorder()
		s.handleHotkey(recorder, req)
		if recorder.Code != http.StatusOK {
			t.Fatalf("hotkey=%s status=%d body=%s", hotkey, recorder.Code, recorder.Body.String())
		}
	}
}

func TestHandleHotkeyRejectsUnknown(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	req := httptest.NewRequest(http.MethodPost, "http://192.168.1.20:51877/api/hotkey/cut", nil)
	req.Host = "192.168.1.20:51877"
	req.RemoteAddr = "192.168.1.8:45678"
	req.Header.Set("Origin", "http://192.168.1.20:51877")
	recorder := httptest.NewRecorder()
	s.handleHotkey(recorder, req)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestHandleKeyEnterProxy(t *testing.T) {
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/key/enter" || r.Method != http.MethodPost {
			t.Fatalf("unexpected core request %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer core.Close()
	s := newTestServer(core.URL)

	req := httptest.NewRequest(http.MethodPost, "http://192.168.1.20:51877/api/key/enter?source=send-and-enter", nil)
	req.Host = "192.168.1.20:51877"
	req.RemoteAddr = "192.168.1.8:45678"
	req.Header.Set("Origin", "http://192.168.1.20:51877")
	recorder := httptest.NewRecorder()
	s.handleKey(recorder, req)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestHandleCoreInputPageKeepsStandaloneCompatibility(t *testing.T) {
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			t.Fatalf("unexpected core page path %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, `<!doctype html><html><head><link rel="manifest" href="/manifest.webmanifest"></head><body><textarea id="text"></textarea><script>fetch('/api/status');navigator.serviceWorker.register('/sw.js').catch(()=>{});</script></body></html>`)
	}))
	defer core.Close()
	s := newTestServer(core.URL)

	req := httptest.NewRequest(http.MethodGet, "http://192.168.1.20:51877/input/", nil)
	req.Host = "192.168.1.20:51877"
	req.RemoteAddr = "192.168.1.8:45678"
	recorder := httptest.NewRecorder()
	s.handleCoreInputPage(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	body := recorder.Body.String()
	if !strings.Contains(body, "/core-api/status") || !strings.Contains(body, `id="text"`) {
		t.Fatalf("original compatibility page was not proxied correctly: %s", body)
	}
	if !strings.Contains(recorder.Header().Get("Content-Security-Policy"), "frame-ancestors 'none'") {
		t.Fatalf("compatibility CSP must reject framing: %s", recorder.Header().Get("Content-Security-Policy"))
	}
}

func TestHandleCoreAPIForwardsOriginalTextUnchanged(t *testing.T) {
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/text" || r.Method != http.MethodPost {
			t.Fatalf("unexpected core request %s %s", r.Method, r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"enterAfter":true`) {
			t.Fatalf("original input request was altered: %s", body)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"ok":true}`)
	}))
	defer core.Close()
	s := newTestServer(core.URL)

	req := httptest.NewRequest(http.MethodPost, "http://192.168.1.20:51877/core-api/text", strings.NewReader(`{"text":"中文🙂","delayMs":6,"enterAfter":true}`))
	req.Host = "192.168.1.20:51877"
	req.RemoteAddr = "192.168.1.8:45678"
	req.Header.Set("Origin", "http://192.168.1.20:51877")
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	s.handleCoreAPI(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestSafeLogValueRemovesLineBreaks(t *testing.T) {
	got := safeLogValue("a\r\nb\tc", 80)
	if strings.ContainsAny(got, "\r\n\t") {
		t.Fatalf("unsafe log value %q", got)
	}
}

func TestWebSocketConnectionsCanOverlapDuringPageHandoff(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	ts := httptest.NewServer(http.HandlerFunc(s.handleWebSocket))
	defer ts.Close()

	first := dialTestWebSocket(t, ts.URL)
	defer first.Close()
	second := dialTestWebSocket(t, ts.URL)
	defer second.Close()

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.connMu.Lock()
		count := s.connections
		s.connMu.Unlock()
		if count == 2 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	s.connMu.Lock()
	count := s.connections
	s.connMu.Unlock()
	if count != 2 {
		t.Fatalf("active websocket connections=%d want 2", count)
	}

	// The preview.7 server closed first as soon as second connected.  A mobile
	// navigation/BFCache handoff can briefly produce exactly this overlap.
	// The original connection must remain alive long enough to complete a ping.
	if err := writeMaskedTestFrame(first, 0x9, []byte("first-still-alive")); err != nil {
		t.Fatalf("write ping on first connection after second connected: %v", err)
	}
	_ = first.SetReadDeadline(time.Now().Add(time.Second))
	opcode, payload, err := readTestServerFrame(bufio.NewReader(first))
	if err != nil {
		t.Fatalf("first connection was evicted by second connection: %v", err)
	}
	if opcode != 0xA || string(payload) != "first-still-alive" {
		t.Fatalf("unexpected pong opcode=%d payload=%q", opcode, payload)
	}
}

func dialTestWebSocket(t *testing.T, serverURL string) net.Conn {
	t.Helper()
	address := strings.TrimPrefix(serverURL, "http://")
	conn, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		t.Fatalf("dial websocket test server: %v", err)
	}
	request := fmt.Sprintf("GET /ws HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nOrigin: http://%s\r\n\r\n", address, address)
	if _, err := io.WriteString(conn, request); err != nil {
		_ = conn.Close()
		t.Fatalf("write websocket handshake: %v", err)
	}
	reader := bufio.NewReader(conn)
	status, err := reader.ReadString('\n')
	if err != nil || !strings.Contains(status, "101 Switching Protocols") {
		_ = conn.Close()
		t.Fatalf("websocket handshake failed status=%q err=%v", status, err)
	}
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			_ = conn.Close()
			t.Fatalf("read websocket handshake headers: %v", err)
		}
		if line == "\r\n" {
			break
		}
	}
	return conn
}

func writeMaskedTestFrame(w io.Writer, opcode byte, payload []byte) error {
	if len(payload) > 125 {
		return fmt.Errorf("test payload too large")
	}
	mask := [4]byte{0x12, 0x34, 0x56, 0x78}
	frame := make([]byte, 0, 2+4+len(payload))
	frame = append(frame, 0x80|opcode, 0x80|byte(len(payload)))
	frame = append(frame, mask[:]...)
	for i, b := range payload {
		frame = append(frame, b^mask[i%4])
	}
	_, err := w.Write(frame)
	return err
}

func readTestServerFrame(r *bufio.Reader) (byte, []byte, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(r, header); err != nil {
		return 0, nil, err
	}
	if header[1]&0x80 != 0 {
		return 0, nil, fmt.Errorf("server frame must not be masked")
	}
	length := int(header[1] & 0x7f)
	if length > 125 {
		return 0, nil, fmt.Errorf("unexpected extended frame")
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, err
	}
	return header[0] & 0x0f, payload, nil
}

func newTestServer(coreURL string) *server {
	return &server{
		logger:       log.New(io.Discard, "", 0),
		buttonOwners: map[string]map[*controlSession]struct{}{},
		coreURL:      coreURL,
		coreClient:   &http.Client{Timeout: 2 * time.Second},
		startedAt:    time.Now(),
	}
}

func TestProtocolV2HelloAck(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	r := httptest.NewRequest(http.MethodGet, "http://192.168.1.20:51877/v2/ws", nil)
	r.RemoteAddr = "192.168.1.8:45678"
	ack, hello := s.processV2Payload(r, []byte(`{"protocol":2,"type":"hello","requestId":"hello-1","client":"android-native","version":"test"}`), false, newControlSession())
	if !hello || !ack.OK || ack.Protocol != 2 || ack.ServerVersion == "" {
		t.Fatalf("unexpected hello ack: %#v hello=%v", ack, hello)
	}
	required := map[string]bool{
		"move":                   false,
		"http.clipboard.text":    false,
		"http.screenshot.png":    false,
		"http.foreground_window": false,
		"http.diagnostics":       false,
		"hotkey.voice":           false,
	}
	for _, capability := range ack.Capabilities {
		if _, ok := required[capability]; ok {
			required[capability] = true
		}
	}
	for capability, found := range required {
		if !found {
			t.Fatalf("hello capabilities missing %s: %#v", capability, ack.Capabilities)
		}
	}
}

func TestProtocolV2VoiceHotkey(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	r := httptest.NewRequest(http.MethodGet, "http://192.168.1.20:51877/v2/ws", nil)
	r.RemoteAddr = "192.168.1.8:45678"
	ack, _ := s.processV2Payload(r, []byte(`{"protocol":2,"type":"hotkey","requestId":"voice-1","action":"voice"}`), true, newControlSession())
	if !ack.OK {
		t.Fatalf("voice hotkey rejected: %#v", ack)
	}
}

func TestProtocolV2RequiresHello(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	r := httptest.NewRequest(http.MethodGet, "http://192.168.1.20:51877/v2/ws", nil)
	r.RemoteAddr = "192.168.1.8:45678"
	ack, hello := s.processV2Payload(r, []byte(`{"protocol":2,"type":"move","requestId":"move-1","dx":4,"dy":8}`), false, newControlSession())
	if hello || ack.OK || ack.Error == nil || ack.Error.Code != "hello_required" {
		t.Fatalf("unexpected ack: %#v hello=%v", ack, hello)
	}
}

func TestProtocolV2WindowSwitchForwardsToCore(t *testing.T) {
	var calls atomic.Int32
	core := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Method != http.MethodPost || r.URL.Path != "/api/window-switch/chrome" {
			t.Fatalf("unexpected core request %s %s", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"ok":true}`)
	}))
	defer core.Close()

	s := newTestServer(core.URL)
	r := httptest.NewRequest(http.MethodGet, "http://192.168.1.20:51877/v2/ws", nil)
	r.RemoteAddr = "192.168.1.8:45678"
	ack, _ := s.processV2Payload(r, []byte(`{"protocol":2,"type":"window_switch","requestId":"win-1","target":"chrome"}`), true, newControlSession())
	if !ack.OK || calls.Load() != 1 {
		t.Fatalf("ack=%#v coreCalls=%d", ack, calls.Load())
	}
}

func TestProtocolV2RejectsInvalidButton(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	r := httptest.NewRequest(http.MethodGet, "http://192.168.1.20:51877/v2/ws", nil)
	r.RemoteAddr = "192.168.1.8:45678"
	ack, _ := s.processV2Payload(r, []byte(`{"protocol":2,"type":"click","requestId":"click-1","button":"side"}`), true, newControlSession())
	if ack.OK || ack.Error == nil || ack.Error.Code != "invalid_argument" {
		t.Fatalf("unexpected ack: %#v", ack)
	}
}

func TestWriteServerTextFrameSupportsExtendedPayload(t *testing.T) {
	var buffer strings.Builder
	payload := strings.Repeat("x", 200)
	if err := writeServerTextFrame(&buffer, []byte(payload)); err != nil {
		t.Fatal(err)
	}
	data := []byte(buffer.String())
	if len(data) != 204 || data[0] != 0x81 || data[1] != 126 || data[2] != 0 || data[3] != 200 {
		t.Fatalf("unexpected frame header/length: %v len=%d", data[:4], len(data))
	}
}

func TestClassifyForegroundTarget(t *testing.T) {
	cases := []struct {
		process string
		title   string
		want    string
	}{
		{"ChatGPT.exe", "", "chatgpt"},
		{"chrome.exe", "Example - Google Chrome", "chrome"},
		{"chrome.exe", "ChatGPT - Google Chrome", "chrome"},
		{"WeChat.exe", "微信", "wechat"},
		{"Weixin.exe", "聊天", "wechat"},
		{"notepad.exe", "Untitled - Notepad", "other"},
	}
	for _, tc := range cases {
		if got := classifyForegroundTarget(tc.process, tc.title); got != tc.want {
			t.Fatalf("classifyForegroundTarget(%q,%q)=%q want %q", tc.process, tc.title, got, tc.want)
		}
	}
}

func TestDiagnosticsEndpoint(t *testing.T) {
	s := newServer(log.New(io.Discard, "", 0))
	req := httptest.NewRequest(http.MethodGet, "http://192.168.1.2/api/diagnostics", nil)
	req.RemoteAddr = "192.168.1.50:12345"
	rr := httptest.NewRecorder()
	s.handleDiagnostics(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("diagnostics status=%d body=%s", rr.Code, rr.Body.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["version"] == "" || payload["protocol"] == nil {
		t.Fatalf("diagnostics missing version/protocol: %v", payload)
	}
}

func TestSessionButtonOwnershipSurvivesOtherDisconnect(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	a := newControlSession()
	b := newControlSession()
	if err := s.sessionSetButton(a, "left", true); err != nil {
		t.Fatal(err)
	}
	if err := s.sessionSetButton(b, "left", true); err != nil {
		t.Fatal(err)
	}
	if got := len(s.buttonOwners["left"]); got != 2 {
		t.Fatalf("owners=%d want 2", got)
	}
	s.releaseSessionInput(a)
	if got := len(s.buttonOwners["left"]); got != 1 {
		t.Fatalf("owners after A disconnect=%d want 1", got)
	}
	if !b.held["left"] {
		t.Fatal("remaining session lost left-button ownership")
	}
	s.releaseSessionInput(b)
	if got := len(s.buttonOwners["left"]); got != 0 {
		t.Fatalf("owners after all disconnect=%d want 0", got)
	}
}

func TestSessionReleaseOnlyClearsOwnButtons(t *testing.T) {
	s := newTestServer("http://127.0.0.1:1")
	a := newControlSession()
	b := newControlSession()
	_ = s.sessionSetButton(a, "left", true)
	_ = s.sessionSetButton(b, "right", true)
	s.releaseSessionInput(a)
	if len(s.buttonOwners["left"]) != 0 {
		t.Fatal("A left ownership not released")
	}
	if len(s.buttonOwners["right"]) != 1 || !b.held["right"] {
		t.Fatal("B right ownership was disturbed")
	}
}
