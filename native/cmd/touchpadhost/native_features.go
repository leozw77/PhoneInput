package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"runtime"
	"strings"
	"time"

	"phoneinput-touchpad/internal/version"
)

const maxClipboardRequestBytes = 512 << 10

func classifyForegroundTarget(process, title string) string {
	lowerProcess := strings.ToLower(process)
	lowerTitle := strings.ToLower(title)
	// Process identity wins over page/window title. A Chrome tab named "ChatGPT"
	// is still Chrome, while the desktop ChatGPT app remains ChatGPT.
	switch {
	case strings.Contains(lowerProcess, "chatgpt"):
		return "chatgpt"
	case strings.Contains(lowerProcess, "chrome"):
		return "chrome"
	case strings.Contains(lowerProcess, "wechat") || strings.Contains(lowerProcess, "weixin"):
		return "wechat"
	case strings.Contains(lowerTitle, "google chrome"):
		return "chrome"
	case strings.Contains(title, "微信"):
		return "wechat"
	case strings.Contains(lowerTitle, "chatgpt"):
		return "chatgpt"
	default:
		return "other"
	}
}

type clipboardWriteRequest struct {
	Text string `json:"text"`
}

type foregroundWindowInfo struct {
	Target  string `json:"target"`
	Title   string `json:"title"`
	Process string `json:"process"`
	PID     uint32 `json:"pid"`
}

func (s *server) handleClipboard(w http.ResponseWriter, r *http.Request) {
	if !sameOrigin(r.Header.Get("Origin"), r.Host) {
		writeJSONError(w, http.StatusForbidden, "origin not allowed")
		return
	}
	switch r.Method {
	case http.MethodGet:
		text, sequence, hasText, err := readSystemClipboardText()
		if err != nil {
			writeJSONError(w, http.StatusServiceUnavailable, "unable to read Windows clipboard")
			return
		}
		sum := sha256.Sum256([]byte(text))
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"ok":       true,
			"hasText":  hasText,
			"text":     text,
			"sequence": sequence,
			"hash":     hex.EncodeToString(sum[:8]),
		})
	case http.MethodPost:
		if mediaType := strings.ToLower(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0])); mediaType != "application/json" {
			writeJSONError(w, http.StatusUnsupportedMediaType, "application/json required")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxClipboardRequestBytes)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		var request clipboardWriteRequest
		if err := decoder.Decode(&request); err != nil || ensureJSONEOF(decoder) != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid clipboard request")
			return
		}
		if len([]byte(request.Text)) > maxClipboardRequestBytes/2 {
			writeJSONError(w, http.StatusRequestEntityTooLarge, "clipboard text is too large")
			return
		}
		if err := writeSystemClipboardText(request.Text); err != nil {
			s.logger.Printf("Clipboard write failed; Reason=%s; Client=%s", safeLogValue(err.Error(), 160), clientAddress(r.RemoteAddr))
			writeJSONError(w, http.StatusServiceUnavailable, "unable to write Windows clipboard")
			return
		}
		_, sequence, _, _ := readSystemClipboardText()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "sequence": sequence})
	default:
		w.Header().Set("Allow", "GET, POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *server) handleScreenshotImage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !sameOrigin(r.Header.Get("Origin"), r.Host) {
		writeJSONError(w, http.StatusForbidden, "origin not allowed")
		return
	}
	data, width, height, err := captureDesktopPNG()
	if err != nil {
		s.logger.Printf("Screenshot capture failed; Reason=%s; Client=%s", safeLogValue(err.Error(), 160), clientAddress(r.RemoteAddr))
		writeJSONError(w, http.StatusServiceUnavailable, "unable to capture desktop")
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Content-Length", formatInt(len(data)))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-PhoneInput-Width", formatInt(width))
	w.Header().Set("X-PhoneInput-Height", formatInt(height))
	_, _ = w.Write(data)
}

func (s *server) handleForegroundWindow(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	info, err := getForegroundWindowInfo()
	if err != nil {
		writeJSONError(w, http.StatusServiceUnavailable, "unable to read foreground window")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "window": info})
}

func (s *server) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	window, _ := getForegroundWindowInfo()
	coreOK, coreDetail := s.quickCoreHealth()
	s.connMu.Lock()
	activeConnections := s.connections
	s.connMu.Unlock()
	uptime := time.Since(s.startedAt).Round(time.Second)
	s.pendingMu.Lock()
	s.prunePendingLocked(time.Now())
	pendingFiles := len(s.pendingFiles)
	s.pendingMu.Unlock()
	s.transferMu.Lock()
	uploadCount := s.uploadCount
	downloadCount := s.downloadCount
	uploadBytes := s.uploadBytes
	downloadBytes := s.downloadBytes
	lastTransfer := s.lastTransfer
	lastTransferError := s.lastTransferError
	s.transferMu.Unlock()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"ok":                true,
		"product":           version.Product,
		"version":           version.Version,
		"protocol":          nativeProtocolVersion,
		"port":              version.TouchpadPort,
		"activeConnections": activeConnections,
		"uptimeSeconds":     int64(uptime / time.Second),
		"goroutines":        runtime.NumGoroutine(),
		"goos":              runtime.GOOS,
		"goarch":            runtime.GOARCH,
		"coreAvailable":     coreOK,
		"coreDetail":        coreDetail,
		"foregroundWindow":  window,
		"pendingFiles":      pendingFiles,
		"uploadCount":       uploadCount,
		"downloadCount":     downloadCount,
		"uploadBytes":       uploadBytes,
		"downloadBytes":     downloadBytes,
		"lastTransfer":      lastTransfer,
		"lastTransferError": lastTransferError,
	})
}

func (s *server) quickCoreHealth() (bool, string) {
	ctx, cancel := context.WithTimeout(context.Background(), 1200*time.Millisecond)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(s.coreURL, "/")+"/api/status", nil)
	if err != nil {
		return false, err.Error()
	}
	response, err := s.coreClient.Do(request)
	if err != nil {
		return false, err.Error()
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1024))
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return false, response.Status
	}
	return true, "ok"
}

func formatInt(v int) string {
	// Avoid pulling strconv into the platform files that are also used by the Windows build.
	const digits = "0123456789"
	if v == 0 {
		return "0"
	}
	if v < 0 {
		return "-" + formatInt(-v)
	}
	var buf [24]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = digits[v%10]
		v /= 10
	}
	return string(buf[i:])
}
