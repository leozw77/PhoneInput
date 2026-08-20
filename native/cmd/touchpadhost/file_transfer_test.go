package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSanitizeTransferName(t *testing.T) {
	got := sanitizeTransferName(`..\\a:b?c.apk`)
	if got != "a_b_c.apk" {
		t.Fatalf("got %q", got)
	}
}

func TestStageDownloadComplete(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "app-debug.apk")
	want := []byte("fake-apk-for-protocol-test")
	if err := os.WriteFile(path, want, 0o600); err != nil {
		t.Fatal(err)
	}
	s := &server{logger: log.New(io.Discard, "", 0), pendingFiles: map[string]pendingTransfer{}}
	body, _ := json.Marshal(stageFilesRequest{Paths: []string{path}})
	req := httptest.NewRequest("POST", "http://127.0.0.1/api/files/stage", bytes.NewReader(body))
	req.RemoteAddr = "127.0.0.1:12345"
	rec := httptest.NewRecorder()
	s.handleFileStage(rec, req)
	if rec.Code != 200 {
		t.Fatalf("stage=%d %s", rec.Code, rec.Body.String())
	}
	s.pendingMu.Lock()
	var item pendingTransfer
	for _, x := range s.pendingFiles {
		item = x
		break
	}
	s.pendingMu.Unlock()
	if item.ID == "" || item.Token == "" {
		t.Fatal("missing pending file")
	}

	dreq := httptest.NewRequest("GET", "/api/files/download/"+item.ID+"?token="+item.Token, nil)
	dreq.RemoteAddr = "192.168.1.2:5555"
	drec := httptest.NewRecorder()
	s.handleFileDownload(drec, dreq)
	if drec.Code != 200 {
		t.Fatalf("download=%d %s", drec.Code, drec.Body.String())
	}
	if !bytes.Equal(drec.Body.Bytes(), want) {
		t.Fatalf("download mismatch")
	}

	creq := httptest.NewRequest("POST", "/api/files/complete/"+item.ID+"?token="+item.Token, nil)
	crec := httptest.NewRecorder()
	s.handleFileComplete(crec, creq)
	if crec.Code != 200 {
		t.Fatalf("complete=%d %s", crec.Code, crec.Body.String())
	}
	s.pendingMu.Lock()
	remaining := len(s.pendingFiles)
	s.pendingMu.Unlock()
	if remaining != 0 {
		t.Fatalf("remaining=%d", remaining)
	}
}

func TestFileUpload(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	s := &server{logger: log.New(io.Discard, "", 0), pendingFiles: map[string]pendingTransfer{}}
	want := []byte("phone-screenshot-payload")
	req := httptest.NewRequest("POST", "/api/files/upload", bytes.NewReader(want))
	req.RemoteAddr = "192.168.1.33:5566"
	req.Header.Set("X-PhoneInput-File-Name", "Screenshot_test.png")
	req.Header.Set("X-PhoneInput-Category", "image")
	req.Header.Set("Content-Type", "image/png")
	rec := httptest.NewRecorder()
	s.handleFileUpload(rec, req)
	if rec.Code != 200 {
		t.Fatalf("upload=%d %s", rec.Code, rec.Body.String())
	}
	got, err := os.ReadFile(filepath.Join(home, "Downloads", "PhoneInputEnhanced", "Images", "Screenshot_test.png"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("uploaded bytes mismatch")
	}
}
