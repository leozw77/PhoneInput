package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

const (
	maxTransferFileBytes = int64(1 << 30) // 1 GiB; intentionally simple for occasional small-file transfer.
	pendingFileTTL       = 15 * time.Minute
	maxStageFiles        = 20
)

type pendingTransfer struct {
	ID        string
	Token     string
	Path      string
	Name      string
	Size      int64
	MIME      string
	CreatedAt time.Time
	ExpiresAt time.Time
}

type stageFilesRequest struct {
	Paths []string `json:"paths"`
}

type pendingTransferDTO struct {
	ID        string `json:"id"`
	Token     string `json:"token"`
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	MIME      string `json:"mime"`
	ExpiresAt int64  `json:"expiresAt"`
}

func (s *server) handleFileUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	rawName := r.Header.Get("X-PhoneInput-File-Name")
	if decoded, err := url.QueryUnescape(rawName); err == nil {
		rawName = decoded
	}
	name := sanitizeTransferName(rawName)
	if name == "" {
		writeJSONError(w, http.StatusBadRequest, "file name required")
		return
	}
	if r.ContentLength > maxTransferFileBytes {
		writeJSONError(w, http.StatusRequestEntityTooLarge, "file too large")
		return
	}
	category := strings.ToLower(strings.TrimSpace(r.Header.Get("X-PhoneInput-Category")))
	folder := "Files"
	if category == "image" || category == "screenshot" {
		folder = "Images"
	}
	targetDir, err := receivedFilesDir(folder)
	if err != nil {
		s.recordTransferError("upload", err)
		writeJSONError(w, http.StatusServiceUnavailable, "unable to prepare receive folder")
		return
	}
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		s.recordTransferError("upload", err)
		writeJSONError(w, http.StatusServiceUnavailable, "unable to prepare receive folder")
		return
	}
	target := uniquePath(targetDir, name)
	part := target + ".part"
	file, err := os.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		s.recordTransferError("upload", err)
		writeJSONError(w, http.StatusServiceUnavailable, "unable to create destination file")
		return
	}
	limited := http.MaxBytesReader(w, r.Body, maxTransferFileBytes+1)
	written, copyErr := io.Copy(file, limited)
	closeErr := file.Close()
	if copyErr != nil || written > maxTransferFileBytes || closeErr != nil {
		_ = os.Remove(part)
		if copyErr == nil && written > maxTransferFileBytes {
			copyErr = fmt.Errorf("file exceeds %d bytes", maxTransferFileBytes)
		}
		if copyErr == nil {
			copyErr = closeErr
		}
		s.recordTransferError("upload", copyErr)
		writeJSONError(w, http.StatusRequestEntityTooLarge, "file upload failed")
		return
	}
	if err := os.Rename(part, target); err != nil {
		_ = os.Remove(part)
		s.recordTransferError("upload", err)
		writeJSONError(w, http.StatusServiceUnavailable, "unable to finalize destination file")
		return
	}
	s.recordTransferSuccess("upload", written, filepath.Base(target))
	s.logger.Printf("File received from phone; Name=%s; Bytes=%d; Client=%s", safeLogValue(filepath.Base(target), 160), written, clientAddress(r.RemoteAddr))
	if folder == "Images" {
		go s.notifyImageTray(target)
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"ok":     true,
		"name":   filepath.Base(target),
		"bytes":  written,
		"folder": targetDir,
	})
}

func (s *server) handleFileStage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !remoteIsLoopback(r.RemoteAddr) {
		writeJSONError(w, http.StatusForbidden, "local computer only")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 256<<10)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var request stageFilesRequest
	if err := decoder.Decode(&request); err != nil || ensureJSONEOF(decoder) != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid stage request")
		return
	}
	if len(request.Paths) == 0 || len(request.Paths) > maxStageFiles {
		writeJSONError(w, http.StatusBadRequest, "choose between 1 and 20 files")
		return
	}

	now := time.Now()
	staged := make([]pendingTransferDTO, 0, len(request.Paths))
	s.pendingMu.Lock()
	defer s.pendingMu.Unlock()
	s.prunePendingLocked(now)
	for _, raw := range request.Paths {
		path := filepath.Clean(strings.TrimSpace(raw))
		info, err := os.Stat(path)
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		if info.Size() < 0 || info.Size() > maxTransferFileBytes {
			continue
		}
		id := randomHex(12)
		token := randomHex(24)
		if id == "" || token == "" {
			continue
		}
		mimeType := mime.TypeByExtension(strings.ToLower(filepath.Ext(info.Name())))
		if mimeType == "" {
			mimeType = "application/octet-stream"
		}
		item := pendingTransfer{
			ID: id, Token: token, Path: path, Name: sanitizeTransferName(info.Name()),
			Size: info.Size(), MIME: mimeType, CreatedAt: now, ExpiresAt: now.Add(pendingFileTTL),
		}
		s.pendingFiles[id] = item
		staged = append(staged, pendingTransferDTO{ID: id, Token: token, Name: item.Name, Size: item.Size, MIME: item.MIME, ExpiresAt: item.ExpiresAt.UnixMilli()})
	}
	if len(staged) == 0 {
		writeJSONError(w, http.StatusBadRequest, "no valid files were staged")
		return
	}
	s.logger.Printf("Files staged for phone; Count=%d", len(staged))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "files": staged})
}

func (s *server) handlePendingFiles(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	now := time.Now()
	s.pendingMu.Lock()
	s.prunePendingLocked(now)
	files := make([]pendingTransferDTO, 0, len(s.pendingFiles))
	for _, item := range s.pendingFiles {
		files = append(files, pendingTransferDTO{ID: item.ID, Token: item.Token, Name: item.Name, Size: item.Size, MIME: item.MIME, ExpiresAt: item.ExpiresAt.UnixMilli()})
	}
	s.pendingMu.Unlock()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "files": files})
}

func (s *server) handleFileDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/api/files/download/")
	if id == "" || strings.Contains(id, "/") {
		http.NotFound(w, r)
		return
	}
	token := r.URL.Query().Get("token")
	now := time.Now()
	s.pendingMu.Lock()
	s.prunePendingLocked(now)
	item, ok := s.pendingFiles[id]
	s.pendingMu.Unlock()
	if !ok || token == "" || token != item.Token {
		http.NotFound(w, r)
		return
	}
	file, err := os.Open(item.Path)
	if err != nil {
		s.recordTransferError("download", err)
		writeJSONError(w, http.StatusGone, "source file is no longer available")
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() != item.Size {
		s.recordTransferError("download", fmt.Errorf("source file changed"))
		writeJSONError(w, http.StatusGone, "source file changed")
		return
	}
	w.Header().Set("Content-Type", item.MIME)
	w.Header().Set("Content-Length", formatInt64(item.Size))
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", strings.ReplaceAll(item.Name, "\"", "")))
	w.Header().Set("Cache-Control", "no-store")
	written, err := io.Copy(w, file)
	if err != nil {
		s.recordTransferError("download", err)
		return
	}
	s.recordTransferSuccess("download", written, item.Name)
	s.logger.Printf("File sent to phone; Name=%s; Bytes=%d; Client=%s", safeLogValue(item.Name, 160), written, clientAddress(r.RemoteAddr))
}

func (s *server) handleFileComplete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/api/files/complete/")
	token := r.URL.Query().Get("token")
	s.pendingMu.Lock()
	item, ok := s.pendingFiles[id]
	if ok && token != "" && token == item.Token {
		delete(s.pendingFiles, id)
	}
	s.pendingMu.Unlock()
	if !ok || token == "" || token != item.Token {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
}

func (s *server) prunePendingLocked(now time.Time) {
	for id, item := range s.pendingFiles {
		if now.After(item.ExpiresAt) {
			delete(s.pendingFiles, id)
		}
	}
}

func (s *server) recordTransferSuccess(direction string, bytes int64, name string) {
	s.transferMu.Lock()
	defer s.transferMu.Unlock()
	if direction == "upload" {
		s.uploadCount++
		s.uploadBytes += bytes
	} else {
		s.downloadCount++
		s.downloadBytes += bytes
	}
	s.lastTransfer = fmt.Sprintf("%s %s (%d bytes)", direction, name, bytes)
	s.lastTransferError = ""
}

func (s *server) recordTransferError(direction string, err error) {
	if err == nil {
		return
	}
	s.transferMu.Lock()
	s.lastTransferError = direction + ": " + err.Error()
	s.transferMu.Unlock()
}

func receivedFilesDir(folder string) (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Downloads", "PhoneInputEnhanced", folder), nil
}

func uniquePath(dir, name string) string {
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	candidate := filepath.Join(dir, name)
	if _, err := os.Stat(candidate); os.IsNotExist(err) {
		return candidate
	}
	stamp := time.Now().Format("20060102_150405")
	for i := 1; i < 1000; i++ {
		candidate = filepath.Join(dir, fmt.Sprintf("%s_%s_%d%s", stem, stamp, i, ext))
		if _, err := os.Stat(candidate); os.IsNotExist(err) {
			return candidate
		}
	}
	return filepath.Join(dir, fmt.Sprintf("%s_%d%s", stem, time.Now().UnixNano(), ext))
}

func sanitizeTransferName(raw string) string {
	normalized := strings.ReplaceAll(strings.TrimSpace(raw), "\\", "/")
	name := path.Base(normalized)
	if name == "." || name == string(filepath.Separator) {
		return ""
	}
	name = strings.Map(func(r rune) rune {
		if r < 32 || r == '<' || r == '>' || r == ':' || r == '"' || r == '/' || r == '\\' || r == '|' || r == '?' || r == '*' {
			return '_'
		}
		return r
	}, name)
	name = strings.Trim(name, " .")
	if len([]rune(name)) > 180 {
		name = string([]rune(name)[:180])
	}
	return name
}

func randomHex(bytesCount int) string {
	data := make([]byte, bytesCount)
	if _, err := rand.Read(data); err != nil {
		return ""
	}
	return hex.EncodeToString(data)
}

func remoteIsLoopback(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	return err == nil && net.ParseIP(host) != nil && net.ParseIP(host).IsLoopback()
}

func formatInt64(v int64) string { return fmt.Sprintf("%d", v) }
