package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"phoneinput-touchpad/internal/version"
)

const nativeProtocolVersion = 2

type v2Command struct {
	Protocol  int    `json:"protocol"`
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Client    string `json:"client,omitempty"`
	Version   string `json:"version,omitempty"`
	DX        int32  `json:"dx,omitempty"`
	DY        int32  `json:"dy,omitempty"`
	X         int32  `json:"x,omitempty"`
	Y         int32  `json:"y,omitempty"`
	Button    string `json:"button,omitempty"`
	Down      bool   `json:"down,omitempty"`
	Key       string `json:"key,omitempty"`
	Action    string `json:"action,omitempty"`
	Target    string `json:"target,omitempty"`
}

type v2Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type v2Ack struct {
	Protocol      int      `json:"protocol"`
	Type          string   `json:"type"`
	RequestID     string   `json:"requestId"`
	OK            bool     `json:"ok"`
	Server        string   `json:"server,omitempty"`
	ServerVersion string   `json:"serverVersion,omitempty"`
	Capabilities  []string `json:"capabilities,omitempty"`
	Error         *v2Error `json:"error,omitempty"`
}

type v2CommandError struct {
	code    string
	message string
}

func (e *v2CommandError) Error() string { return e.message }

func newV2CommandError(code, message string) error {
	return &v2CommandError{code: code, message: message}
}

func (s *server) handleV2WebSocket(w http.ResponseWriter, r *http.Request) {
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

	s.connMu.Lock()
	s.connections++
	connectionCount := s.connections
	s.connMu.Unlock()
	client := clientAddress(r.RemoteAddr)
	s.logger.Printf("Native v2 connected from %s; ActiveConnections=%d", client, connectionCount)
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
		s.logger.Printf("Native v2 disconnected from %s; ActiveConnections=%d", client, remaining)
	}()

	_ = conn.SetReadDeadline(time.Now().Add(45 * time.Second))
	handshaken := false
	for {
		opcode, payload, err := readClientFrame(rw.Reader)
		if err != nil {
			s.logger.Printf("Native v2 websocket read ended; Client=%s; Error=%v", client, err)
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(45 * time.Second))
		switch opcode {
		case 0x1:
			ack, helloOK := s.processV2Payload(r, payload, handshaken, session)
			if helloOK {
				handshaken = true
			}
			if err := writeServerJSONFrame(conn, ack); err != nil {
				return
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

func (s *server) processV2Payload(r *http.Request, payload []byte, handshaken bool, session *controlSession) (v2Ack, bool) {
	if len(payload) > 16<<10 {
		return v2Failure("", "command_too_large", "command exceeds 16 KiB"), false
	}
	var c v2Command
	if err := json.Unmarshal(payload, &c); err != nil {
		return v2Failure("", "bad_json", "invalid JSON command"), false
	}
	if len(c.RequestID) == 0 || len(c.RequestID) > 80 {
		return v2Failure(c.RequestID, "invalid_request_id", "requestId is required and must be at most 80 characters"), false
	}
	if c.Protocol != nativeProtocolVersion {
		return v2Failure(c.RequestID, "protocol_mismatch", "PhoneInputEnhanced Protocol v2 is required"), false
	}
	if c.Type == "hello" {
		return v2HelloAck(c.RequestID), true
	}
	if !handshaken {
		return v2Failure(c.RequestID, "hello_required", "send hello before control commands"), false
	}
	if err := s.executeV2(r, c, session); err != nil {
		var commandErr *v2CommandError
		if errors.As(err, &commandErr) {
			return v2Failure(c.RequestID, commandErr.code, commandErr.message), false
		}
		s.logger.Printf("Native v2 command failed; Type=%s; Client=%s; Error=%s", safeLogValue(c.Type, 40), clientAddress(r.RemoteAddr), safeLogValue(err.Error(), 160))
		return v2Failure(c.RequestID, "internal_error", "command failed"), false
	}
	return v2Ack{Protocol: nativeProtocolVersion, Type: "ack", RequestID: c.RequestID, OK: true}, false
}

func (s *server) executeV2(r *http.Request, c v2Command, session *controlSession) error {
	switch c.Type {
	case "move":
		return moveMouse(clamp32(c.DX, -480, 480), clamp32(c.DY, -480, 480))
	case "scroll":
		return scrollMouse(clamp32(c.X, -1200, 1200), clamp32(c.Y, -1200, 1200))
	case "button":
		if !validMouseButton(c.Button) {
			return newV2CommandError("invalid_argument", "button must be left, middle, or right")
		}
		return s.sessionSetButton(session, c.Button, c.Down)
	case "click":
		if !validMouseButton(c.Button) {
			return newV2CommandError("invalid_argument", "button must be left, middle, or right")
		}
		return s.sessionClickButton(session, c.Button)
	case "release":
		s.releaseSessionInput(session)
		return nil
	case "ping":
		return nil
	case "hotkey":
		if c.Action != "copy" && c.Action != "paste" && c.Action != "voice" {
			return newV2CommandError("invalid_argument", "hotkey action must be copy, paste, or voice")
		}
		return sendHotkey(c.Action)
	case "key":
		allowed := map[string]bool{
			"enter": true, "backspace": true, "tab": true, "escape": true,
			"left": true, "right": true, "up": true, "down": true,
			"screenshot": true,
		}
		if !allowed[c.Key] {
			return newV2CommandError("invalid_argument", "unsupported key")
		}
		return s.forwardCoreV2(r, "/api/key/"+url.PathEscape(c.Key))
	case "window_switch":
		if c.Target != "chatgpt" && c.Target != "chrome" && c.Target != "wechat" {
			return newV2CommandError("invalid_argument", "window target must be chatgpt, chrome, or wechat")
		}
		return s.forwardCoreV2(r, "/api/window-switch/"+url.PathEscape(c.Target))
	default:
		return newV2CommandError("unsupported_command", "unsupported command type")
	}
}

func (s *server) forwardCoreV2(r *http.Request, path string) error {
	status, _, body, err := s.forwardCore(r, http.MethodPost, path, nil, "")
	if err != nil {
		return newV2CommandError("core_unavailable", "computer input service is unavailable")
	}
	if status < 200 || status >= 300 {
		message := strings.TrimSpace(string(body))
		if len(message) > 120 {
			message = message[:120]
		}
		if message == "" {
			message = fmt.Sprintf("computer input service returned HTTP %d", status)
		}
		return newV2CommandError("core_rejected", message)
	}
	return nil
}

func validMouseButton(button string) bool {
	return button == "left" || button == "middle" || button == "right"
}

func v2HelloAck(requestID string) v2Ack {
	return v2Ack{
		Protocol:      nativeProtocolVersion,
		Type:          "ack",
		RequestID:     requestID,
		OK:            true,
		Server:        version.Product,
		ServerVersion: version.Version,
		Capabilities: []string{
			"move", "scroll", "button", "click", "release", "ping",
			"hotkey.copy", "hotkey.paste", "hotkey.voice", "key", "key.screenshot",
			"window_switch.chatgpt", "window_switch.chrome", "window_switch.wechat",
			"http.clipboard.text", "http.screenshot.png", "http.foreground_window", "http.diagnostics",
			"http.files.upload", "http.files.pending", "http.files.download",
		},
	}
}

func v2Failure(requestID, code, message string) v2Ack {
	return v2Ack{
		Protocol:  nativeProtocolVersion,
		Type:      "ack",
		RequestID: requestID,
		OK:        false,
		Error:     &v2Error{Code: code, Message: message},
	}
}

func writeServerJSONFrame(w io.Writer, value any) error {
	payload, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return writeServerTextFrame(w, payload)
}

func writeServerTextFrame(w io.Writer, payload []byte) error {
	writeMu.Lock()
	defer writeMu.Unlock()
	if len(payload) > 64<<10 {
		return errors.New("server WebSocket frame too large")
	}
	header := []byte{0x81}
	switch {
	case len(payload) <= 125:
		header = append(header, byte(len(payload)))
	case len(payload) <= 65535:
		header = append(header, 126, byte(len(payload)>>8), byte(len(payload)))
	default:
		n := uint64(len(payload))
		header = append(header, 127,
			byte(n>>56), byte(n>>48), byte(n>>40), byte(n>>32),
			byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
	if _, err := w.Write(header); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}
