package main

import (
	"bufio"
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	_ "embed"
	"phoneinput-touchpad/internal/version"
)

//go:embed touchpad.html
var touchpadHTML []byte

//go:embed input_component.js
var inputComponentJS []byte

//go:embed input_component.css
var inputComponentCSS []byte

const (
	websocketGUID       = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
	coreBaseURL         = "http://127.0.0.1:51876"
	maxTextCharacters   = 20_000
	maxTextRequestBytes = 256 << 10
	maxCoreResponse     = 64 << 10
	maxCorePageResponse = 2 << 20
)

type command struct {
	Type   string `json:"type"`
	DX     int32  `json:"dx,omitempty"`
	DY     int32  `json:"dy,omitempty"`
	X      int32  `json:"x,omitempty"`
	Y      int32  `json:"y,omitempty"`
	Button string `json:"button,omitempty"`
	Down   bool   `json:"down,omitempty"`
	Event  string `json:"event,omitempty"`
	Reason string `json:"reason,omitempty"`
}

type textRequest struct {
	Text       string `json:"text"`
	DelayMS    int    `json:"delayMs"`
	EnterAfter bool   `json:"enterAfter"`
}

type server struct {
	logger       *log.Logger
	connMu       sync.Mutex
	connections  int
	inputMu      sync.Mutex
	buttonOwners map[string]map[*controlSession]struct{}
	coreURL      string
	coreClient   *http.Client
	startedAt    time.Time

	pendingMu         sync.Mutex
	pendingFiles      map[string]pendingTransfer
	transferMu        sync.Mutex
	uploadCount       int64
	downloadCount     int64
	uploadBytes       int64
	downloadBytes     int64
	lastTransfer      string
	lastTransferError string
}

func main() {
	logger, closeLog := newLogger()
	defer closeLog()
	logger.Printf("PhoneInput touchpad host %s starting", version.Version)

	s := newServer(logger)
	mux := http.NewServeMux()
	mux.HandleFunc("/", s.handleHome)
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/assets/input-component.js", s.handleInputComponentJS)
	mux.HandleFunc("/assets/input-component.css", s.handleInputComponentCSS)
	mux.HandleFunc("/input/", s.handleCoreInputPage)
	mux.HandleFunc("/core-api/", s.handleCoreAPI)
	mux.HandleFunc("/ws", s.handleWebSocket)
	mux.HandleFunc("/v2/ws", s.handleV2WebSocket)
	mux.HandleFunc("/api/text", s.handleText)
	mux.HandleFunc("/api/key/", s.handleKey)
	mux.HandleFunc("/api/hotkey/", s.handleHotkey)
	mux.HandleFunc("/api/clipboard", s.handleClipboard)
	mux.HandleFunc("/api/screenshot", s.handleScreenshotImage)
	mux.HandleFunc("/api/files/upload", s.handleFileUpload)
	mux.HandleFunc("/api/files/stage", s.handleFileStage)
	mux.HandleFunc("/api/files/pending", s.handlePendingFiles)
	mux.HandleFunc("/api/files/download/", s.handleFileDownload)
	mux.HandleFunc("/api/files/complete/", s.handleFileComplete)
	mux.HandleFunc("/api/foreground-window", s.handleForegroundWindow)
	mux.HandleFunc("/api/diagnostics", s.handleDiagnostics)

	addr := fmt.Sprintf("0.0.0.0:%d", version.TouchpadPort)
	httpServer := &http.Server{
		Addr:              addr,
		Handler:           s.privateLANOnly(mux),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	go mirrorStartupEntry(logger)
	logger.Printf("Listening on %s", addr)
	if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Printf("Host stopped: %v", err)
	}
	s.clearAllSessionInput()
}

func newServer(logger *log.Logger) *server {
	return &server{
		logger:       logger,
		buttonOwners: map[string]map[*controlSession]struct{}{},
		pendingFiles: map[string]pendingTransfer{},
		coreURL:      coreBaseURL,
		startedAt:    time.Now(),
		coreClient: &http.Client{
			Timeout: 180 * time.Second,
		},
	}
}

func newLogger() (*log.Logger, func()) {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		base = os.TempDir()
	}
	dir := filepath.Join(base, "PhoneInputEnhanced", "logs")
	_ = os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, "touchpad.log")
	if info, err := os.Stat(path); err == nil && info.Size() > 2<<20 {
		_ = os.Remove(path + ".1")
		_ = os.Rename(path, path+".1")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return log.New(io.Discard, "", 0), func() {}
	}
	return log.New(f, "", log.LstdFlags|log.Lmicroseconds), func() { _ = f.Close() }
}

func (s *server) privateLANOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || !isPrivateIP(net.ParseIP(host)) {
			http.Error(w, "LAN access only", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func isPrivateIP(ip net.IP) bool {
	if ip == nil {
		return false
	}
	if ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return true
	}
	if v4 := ip.To4(); v4 != nil {
		return v4[0] == 10 || (v4[0] == 172 && v4[1] >= 16 && v4[1] <= 31) || (v4[0] == 192 && v4[1] == 168)
	}
	return len(ip) == net.IPv6len && (ip[0]&0xfe) == 0xfc
}

func (s *server) handleHome(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	setPageHeaders(w)
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(touchpadHTML)
}

func (s *server) handleInputComponentJS(w http.ResponseWriter, r *http.Request) {
	serveEmbeddedAsset(w, r, "application/javascript; charset=utf-8", inputComponentJS)
}

func (s *server) handleInputComponentCSS(w http.ResponseWriter, r *http.Request) {
	serveEmbeddedAsset(w, r, "text/css; charset=utf-8", inputComponentCSS)
}

func serveEmbeddedAsset(w http.ResponseWriter, r *http.Request, contentType string, data []byte) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(data)
}

func setPageHeaders(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
}

func (s *server) handleCoreInputPage(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/input/" {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	request, err := http.NewRequestWithContext(r.Context(), http.MethodGet, strings.TrimRight(s.coreURL, "/")+"/", nil)
	if err != nil {
		http.Error(w, "unable to prepare input page", http.StatusInternalServerError)
		return
	}
	request.Header.Set("Accept", "text/html")
	request.Header.Set("User-Agent", version.Product+"/"+version.Version+" embedded-input")
	response, err := s.coreClient.Do(request)
	if err != nil {
		s.logger.Printf("Original input page failed; Reason=%s; Client=%s", safeLogValue(err.Error(), 160), clientAddress(r.RemoteAddr))
		http.Error(w, "original input page is unavailable", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		http.Error(w, "original input page is unavailable", http.StatusBadGateway)
		return
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxCorePageResponse+1))
	if err != nil || len(body) > maxCorePageResponse {
		http.Error(w, "original input page is too large", http.StatusBadGateway)
		return
	}
	body = transformCoreInputHTML(body)
	setEmbeddedPageHeaders(w)
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(body)
}

func transformCoreInputHTML(body []byte) []byte {
	html := string(body)
	// Keep the core page's own input implementation, but isolate its API namespace
	// so it cannot collide with the touchpad page's stricter send-and-enter wrapper.
	html = strings.ReplaceAll(html, "/api/", "/core-api/")
	html = strings.ReplaceAll(html, `<link rel="manifest" href="/manifest.webmanifest">`, "")
	html = strings.ReplaceAll(html, `navigator.serviceWorker.register('/sw.js').catch(()=>{});`, `void 0;`)
	html = strings.ReplaceAll(html,
		`location.href='http://'+location.hostname+':51877/';/*default-touchpad-home*/  `,
		`$('#touchpad').onclick=()=>location.href='/';`,
	)
	html = strings.ReplaceAll(html,
		`$('#touchpad').onclick=()=>location.href='http://'+location.hostname+':51877/';`,
		`$('#touchpad').onclick=()=>location.href='/';`,
	)
	embedStyle := `<style id="phoneinput-touchpad-compat">#install{display:none!important;}</style>`
	html = strings.Replace(html, "</head>", embedStyle+"</head>", 1)
	return []byte(html)
}

func setEmbeddedPageHeaders(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
}

func (s *server) handleCoreAPI(w http.ResponseWriter, r *http.Request) {
	if !sameOrigin(r.Header.Get("Origin"), r.Host) {
		writeJSONError(w, http.StatusForbidden, "origin not allowed")
		return
	}
	corePath := "/api/" + strings.TrimPrefix(r.URL.Path, "/core-api/")
	if !allowedCoreAPI(corePath, r.Method) {
		writeJSONError(w, http.StatusNotFound, "unsupported core input endpoint")
		return
	}

	var body []byte
	if r.Body != nil {
		limited := io.LimitReader(r.Body, maxTextRequestBytes+1)
		var err error
		body, err = io.ReadAll(limited)
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "unable to read request")
			return
		}
		if len(body) > maxTextRequestBytes {
			writeJSONError(w, http.StatusRequestEntityTooLarge, "request is too large")
			return
		}
	}

	targetURL := strings.TrimRight(s.coreURL, "/") + corePath
	if r.URL.RawQuery != "" {
		targetURL += "?" + r.URL.RawQuery
	}
	request, err := http.NewRequestWithContext(r.Context(), r.Method, targetURL, bytes.NewReader(body))
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "unable to prepare request")
		return
	}
	if contentType := r.Header.Get("Content-Type"); contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", version.Product+"/"+version.Version+" original-input-proxy")

	client := clientAddress(r.RemoteAddr)
	textLength := -1
	if corePath == "/api/text" {
		var payload struct {
			Text string `json:"text"`
		}
		if json.Unmarshal(body, &payload) == nil {
			textLength = utf8.RuneCountInString(payload.Text)
			s.logger.Printf("Original text send requested; Length=%d; Client=%s", textLength, client)
			if textLength > maxTextCharacters {
				s.logger.Printf("Original text send failed; Reason=text too long; Length=%d; Client=%s", textLength, client)
				writeJSONError(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("text exceeds %d characters", maxTextCharacters))
				return
			}
		}
	}
	response, err := s.coreClient.Do(request)
	if err != nil {
		if textLength >= 0 {
			s.logger.Printf("Original text send failed; Reason=%s; Client=%s", safeLogValue(err.Error(), 160), client)
		}
		writeJSONError(w, http.StatusBadGateway, "computer input service is unavailable")
		return
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, maxCoreResponse+1))
	if err != nil || len(responseBody) > maxCoreResponse {
		writeJSONError(w, http.StatusBadGateway, "computer input response is invalid")
		return
	}
	if textLength >= 0 {
		if response.StatusCode >= 200 && response.StatusCode < 300 {
			s.logger.Printf("Original text send succeeded; Length=%d; Client=%s", textLength, client)
		} else {
			s.logger.Printf("Original text send failed; Reason=core status %d; Client=%s", response.StatusCode, client)
		}
	}
	writeCoreResponse(w, response.StatusCode, response.Header.Get("Content-Type"), responseBody)
}

func allowedCoreAPI(path, method string) bool {
	switch {
	case path == "/api/status" || path == "/api/input-state":
		return method == http.MethodGet
	case path == "/api/text" || path == "/api/selection":
		return method == http.MethodPost
	case strings.HasPrefix(path, "/api/key/") || strings.HasPrefix(path, "/api/window-switch/"):
		return method == http.MethodPost
	default:
		return false
	}
}

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"ok":                true,
		"version":           version.Version,
		"port":              version.TouchpadPort,
		"maxTextCharacters": maxTextCharacters,
	})
}

func (s *server) handleText(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !sameOrigin(r.Header.Get("Origin"), r.Host) {
		writeJSONError(w, http.StatusForbidden, "origin not allowed")
		return
	}
	if mediaType := strings.ToLower(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0])); mediaType != "application/json" {
		writeJSONError(w, http.StatusUnsupportedMediaType, "application/json required")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxTextRequestBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var request textRequest
	if err := decoder.Decode(&request); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid text request")
		return
	}
	if err := ensureJSONEOF(decoder); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid text request")
		return
	}

	length := utf8.RuneCountInString(request.Text)
	client := clientAddress(r.RemoteAddr)
	s.logger.Printf("Text send requested; Length=%d; Client=%s", length, client)
	if length == 0 {
		s.logger.Printf("Text send failed; Reason=empty text; Client=%s", client)
		writeJSONError(w, http.StatusBadRequest, "text is empty")
		return
	}
	if length > maxTextCharacters {
		s.logger.Printf("Text send failed; Reason=text too long; Length=%d; Client=%s", length, client)
		writeJSONError(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("text exceeds %d characters", maxTextCharacters))
		return
	}

	request.DelayMS = clampInt(request.DelayMS, 0, 15)
	// Enter is deliberately sent through /api/key/enter only after /api/text succeeds.
	request.EnterAfter = false
	payload, err := json.Marshal(request)
	if err != nil {
		s.logger.Printf("Text send failed; Reason=encode request; Client=%s", client)
		writeJSONError(w, http.StatusInternalServerError, "unable to prepare request")
		return
	}

	status, contentType, responseBody, err := s.forwardCore(r, http.MethodPost, "/api/text", payload, "application/json")
	if err != nil {
		s.logger.Printf("Text send failed; Reason=%s; Client=%s", safeLogValue(err.Error(), 160), client)
		writeJSONError(w, http.StatusBadGateway, "computer input service is unavailable")
		return
	}
	if status < 200 || status >= 300 {
		s.logger.Printf("Text send failed; Reason=core status %d; Client=%s", status, client)
		writeCoreResponse(w, status, contentType, responseBody)
		return
	}

	s.logger.Printf("Text send succeeded; Length=%d; Client=%s", length, client)
	writeCoreResponse(w, status, contentType, responseBody)
}

func (s *server) handleHotkey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !sameOrigin(r.Header.Get("Origin"), r.Host) {
		writeJSONError(w, http.StatusForbidden, "origin not allowed")
		return
	}
	hotkey := strings.TrimPrefix(r.URL.Path, "/api/hotkey/")
	if hotkey != "copy" && hotkey != "paste" && hotkey != "voice" {
		writeJSONError(w, http.StatusNotFound, "unsupported hotkey")
		return
	}
	client := clientAddress(r.RemoteAddr)
	if err := sendHotkey(hotkey); err != nil {
		s.logger.Printf("Hotkey send failed; Hotkey=%s; Reason=%s; Client=%s", hotkey, safeLogValue(err.Error(), 160), client)
		writeJSONError(w, http.StatusInternalServerError, "unable to send hotkey")
		return
	}
	s.logger.Printf("Hotkey sent; Hotkey=%s; Client=%s", hotkey, client)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "hotkey": hotkey})
}

func (s *server) handleKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !sameOrigin(r.Header.Get("Origin"), r.Host) {
		writeJSONError(w, http.StatusForbidden, "origin not allowed")
		return
	}
	key := strings.TrimPrefix(r.URL.Path, "/api/key/")
	allowed := map[string]bool{
		"enter": true, "backspace": true, "tab": true, "escape": true,
		"left": true, "right": true, "up": true, "down": true,
	}
	if !allowed[key] {
		writeJSONError(w, http.StatusNotFound, "unsupported key")
		return
	}

	client := clientAddress(r.RemoteAddr)
	status, contentType, responseBody, err := s.forwardCore(r, http.MethodPost, "/api/key/"+url.PathEscape(key), nil, "")
	if err != nil {
		s.logger.Printf("Key send failed; Key=%s; Reason=%s; Client=%s", key, safeLogValue(err.Error(), 160), client)
		writeJSONError(w, http.StatusBadGateway, "computer input service is unavailable")
		return
	}
	if status < 200 || status >= 300 {
		s.logger.Printf("Key send failed; Key=%s; Reason=core status %d; Client=%s", key, status, client)
		writeCoreResponse(w, status, contentType, responseBody)
		return
	}
	if key == "enter" && r.URL.Query().Get("source") == "send-and-enter" {
		s.logger.Printf("Send-and-enter completed; Client=%s", client)
	}
	writeCoreResponse(w, status, contentType, responseBody)
}

func (s *server) forwardCore(r *http.Request, method, path string, body []byte, contentType string) (int, string, []byte, error) {
	request, err := http.NewRequestWithContext(r.Context(), method, strings.TrimRight(s.coreURL, "/")+path, bytes.NewReader(body))
	if err != nil {
		return 0, "", nil, err
	}
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", version.Product+"/"+version.Version+" touchpad-proxy")

	response, err := s.coreClient.Do(request)
	if err != nil {
		return 0, "", nil, err
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, maxCoreResponse+1))
	if err != nil {
		return 0, "", nil, err
	}
	if len(responseBody) > maxCoreResponse {
		return 0, "", nil, errors.New("core response too large")
	}
	return response.StatusCode, response.Header.Get("Content-Type"), responseBody, nil
}

func writeCoreResponse(w http.ResponseWriter, status int, contentType string, body []byte) {
	w.Header().Set("Cache-Control", "no-store")
	if contentType == "" {
		contentType = "application/json; charset=utf-8"
	}
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func writeJSONError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}

func ensureJSONEOF(decoder *json.Decoder) error {
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("multiple JSON values")
		}
		return err
	}
	return nil
}

func sameOrigin(raw, requestHost string) bool {
	if raw == "" {
		return true
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return false
	}
	return strings.EqualFold(u.Host, requestHost)
}

func (s *server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") || !headerHasToken(r.Header, "Connection", "upgrade") {
		http.Error(w, "WebSocket upgrade required", http.StatusUpgradeRequired)
		return
	}
	if !s.allowedOrigin(r.Header.Get("Origin"), r.Host) {
		http.Error(w, "Origin not allowed", http.StatusForbidden)
		return
	}
	key := strings.TrimSpace(r.Header.Get("Sec-WebSocket-Key"))
	if key == "" || r.Header.Get("Sec-WebSocket-Version") != "13" {
		http.Error(w, "Invalid WebSocket handshake", http.StatusBadRequest)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "WebSocket unavailable", http.StatusInternalServerError)
		return
	}
	conn, rw, err := hj.Hijack()
	if err != nil {
		return
	}
	accept := websocketAccept(key)
	_, _ = fmt.Fprintf(rw, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n", accept)
	if err := rw.Flush(); err != nil {
		_ = conn.Close()
		return
	}

	// Do not evict an existing touchpad connection when a new page connects.
	// Mobile browsers can briefly keep an old page/BFCache socket alive while the
	// redirected/restored page opens a replacement. Closing the old socket here
	// made the two pages repeatedly trigger each other's reconnect loop during
	// startup. Multiple sockets may overlap briefly; release held buttons only
	// after the last socket is gone so an old-page disconnect cannot interrupt a
	// newly active page.
	s.connMu.Lock()
	s.connections++
	connectionCount := s.connections
	s.connMu.Unlock()
	s.logger.Printf("Touchpad connected from %s; ActiveConnections=%d", r.RemoteAddr, connectionCount)
	session := newControlSession()
	defer func() {
		s.releaseSessionInput(session)
		_ = conn.Close()
		s.connMu.Lock()
		s.connections--
		remaining := s.connections
		s.connMu.Unlock()
		if remaining == 0 {
			s.clearAllSessionInput()
		}
		s.logger.Printf("Touchpad disconnected from %s; ActiveConnections=%d", r.RemoteAddr, remaining)
	}()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))
	for {
		opcode, payload, err := readClientFrame(rw.Reader)
		if err != nil {
			s.logger.Printf("Touchpad websocket read ended; Client=%s; Error=%v", clientAddress(r.RemoteAddr), err)
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		switch opcode {
		case 0x1:
			if err := s.execute(payload, r.RemoteAddr, session); err != nil {
				s.logger.Printf("Rejected touchpad command: %v", err)
			}
		case 0x8:
			_ = writeServerFrame(conn, 0x8, nil)
			return
		case 0x9:
			if err := writeServerFrame(conn, 0xA, payload); err != nil {
				return
			}
		case 0xA:
		default:
			return
		}
	}
}

func (s *server) allowedOrigin(raw, requestHost string) bool {
	if raw == "" {
		return true
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return false
	}
	host := u.Hostname()
	requestName := requestHost
	if parsed, _, err := net.SplitHostPort(requestHost); err == nil {
		requestName = parsed
	}
	if strings.EqualFold(host, requestName) || strings.EqualFold(host, "localhost") {
		return true
	}
	return isPrivateIP(net.ParseIP(host))
}

func (s *server) execute(payload []byte, remoteAddr string, session *controlSession) error {
	if len(payload) > 4096 {
		return errors.New("command too large")
	}
	var c command
	if err := json.Unmarshal(payload, &c); err != nil {
		return err
	}
	switch c.Type {
	case "move":
		return moveMouse(clamp32(c.DX, -480, 480), clamp32(c.DY, -480, 480))
	case "scroll":
		return scrollMouse(clamp32(c.X, -1200, 1200), clamp32(c.Y, -1200, 1200))
	case "button":
		return s.sessionSetButton(session, c.Button, c.Down)
	case "click":
		return s.sessionClickButton(session, c.Button)
	case "release":
		s.releaseSessionInput(session)
		return nil
	case "ping":
		return nil
	case "client_event":
		return s.logClientEvent(c.Event, c.Reason, remoteAddr)
	default:
		return fmt.Errorf("unsupported command type %q", c.Type)
	}
}

func (s *server) logClientEvent(event, reason, remoteAddr string) error {
	messages := map[string]string{
		"two_finger_hold": "TwoFingerHold detected",
		"panel_opened":    "Control panel opened",
		"panel_closed":    "Control panel closed",
		"gesture_reset":   "Gesture state reset",
	}
	message, ok := messages[event]
	if !ok {
		return errors.New("unsupported client event")
	}
	client := clientAddress(remoteAddr)
	reason = safeLogValue(reason, 80)
	if reason != "" {
		s.logger.Printf("%s; Reason=%s; Client=%s", message, reason, client)
	} else {
		s.logger.Printf("%s; Client=%s", message, client)
	}
	return nil
}

func clientAddress(remoteAddr string) string {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err == nil {
		return host
	}
	return safeLogValue(remoteAddr, 80)
}

func safeLogValue(value string, max int) string {
	value = strings.Map(func(r rune) rune {
		if r == '\r' || r == '\n' || r == '\t' || r < 0x20 {
			return ' '
		}
		return r
	}, value)
	value = strings.TrimSpace(value)
	if utf8.RuneCountInString(value) <= max {
		return value
	}
	runes := []rune(value)
	return string(runes[:max])
}

func clamp32(v, min, max int32) int32 {
	if v < min {
		return min
	}
	if v > max {
		return max
	}
	return v
}

func clampInt(v, min, max int) int {
	if v < min {
		return min
	}
	if v > max {
		return max
	}
	return v
}

func headerHasToken(h http.Header, name, token string) bool {
	for _, value := range h.Values(name) {
		for _, part := range strings.Split(value, ",") {
			if strings.EqualFold(strings.TrimSpace(part), token) {
				return true
			}
		}
	}
	return false
}

func websocketAccept(key string) string {
	sum := sha1.Sum([]byte(key + websocketGUID))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func readClientFrame(r *bufio.Reader) (byte, []byte, error) {
	h, err := r.Peek(2)
	if err != nil {
		return 0, nil, err
	}
	_, _ = r.Discard(2)
	fin := h[0]&0x80 != 0
	opcode := h[0] & 0x0f
	masked := h[1]&0x80 != 0
	if !fin || !masked {
		return 0, nil, errors.New("unsupported WebSocket frame")
	}
	length := uint64(h[1] & 0x7f)
	if length == 126 {
		var b [2]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return 0, nil, err
		}
		length = uint64(binary.BigEndian.Uint16(b[:]))
	} else if length == 127 {
		var b [8]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return 0, nil, err
		}
		length = binary.BigEndian.Uint64(b[:])
	}
	if length > 64<<10 {
		return 0, nil, errors.New("WebSocket frame too large")
	}
	var mask [4]byte
	if _, err := io.ReadFull(r, mask[:]); err != nil {
		return 0, nil, err
	}
	payload := make([]byte, int(length))
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, err
	}
	for i := range payload {
		payload[i] ^= mask[i%4]
	}
	return opcode, payload, nil
}

var writeMu sync.Mutex

func writeServerFrame(w io.Writer, opcode byte, payload []byte) error {
	writeMu.Lock()
	defer writeMu.Unlock()
	if len(payload) > 125 {
		return errors.New("control frame too large")
	}
	header := []byte{0x80 | opcode, byte(len(payload))}
	if _, err := w.Write(header); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

func mirrorStartupEntry(logger *log.Logger) {
	if runtime.GOOS != "windows" {
		return
	}
	exe, err := os.Executable()
	if err != nil {
		return
	}
	root := filepath.Dir(exe)
	wrapper := filepath.Join(root, "PhoneInputEnhanced.exe")
	coreFragment := filepath.Join("Core", "PhoneInputEnhanced.exe")
	for {
		time.Sleep(15 * time.Second)
		out, err := combinedOutputHidden("reg.exe", "query", `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, "/v", "PhoneInputEnhanced")
		if err != nil {
			continue
		}
		text := string(out)
		if !strings.Contains(strings.ToLower(text), strings.ToLower(coreFragment)) {
			continue
		}
		value := `"` + wrapper + `" --startup`
		result, err := combinedOutputHidden("reg.exe", "add", `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, "/v", "PhoneInputEnhanced", "/t", "REG_SZ", "/d", value, "/f")
		if err != nil {
			logger.Printf("Unable to normalize startup entry: %v (%s)", err, strings.TrimSpace(string(result)))
		} else {
			logger.Printf("Startup entry normalized for touchpad launcher")
		}
	}
}
