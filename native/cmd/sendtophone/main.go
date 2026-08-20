//go:build windows

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"
)

type request struct {
	Paths []string `json:"paths"`
}

func main() {
	args := os.Args[1:]
	if len(args) == 0 {
		messageBox("PhoneInputEnhanced", "请在 Windows 资源管理器中右键 APK/文件 → 发送到 → PhoneInputEnhanced。\n\n也可以把文件拖到 PhoneInputSendTo.exe 上。", 0x40)
		return
	}
	paths := make([]string, 0, len(args))
	for _, arg := range args {
		abs, err := filepath.Abs(arg)
		if err != nil {
			continue
		}
		info, err := os.Stat(abs)
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		paths = append(paths, abs)
	}
	if len(paths) == 0 {
		messageBox("PhoneInputEnhanced", "没有可发送的有效文件。", 0x10)
		return
	}
	payload, _ := json.Marshal(request{Paths: paths})
	client := &http.Client{Timeout: 5 * time.Second}
	req, err := http.NewRequest(http.MethodPost, "http://127.0.0.1:51877/api/files/stage", bytes.NewReader(payload))
	if err == nil {
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	if err == nil {
		var resp *http.Response
		resp, err = client.Do(req)
		if err == nil {
			body, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
			_ = resp.Body.Close()
			if resp.StatusCode < 200 || resp.StatusCode >= 300 {
				err = fmt.Errorf("电脑端返回 HTTP %d：%s", resp.StatusCode, string(body))
			}
		}
	}
	if err != nil {
		messageBox("PhoneInputEnhanced", "发送失败。请确认 PhoneInputEnhanced Windows 程序正在运行。\n\n"+err.Error(), 0x10)
		return
	}
	messageBox("PhoneInputEnhanced", fmt.Sprintf("已准备发送：%d 个文件。\n请在 15 分钟内保持手机 PhoneInputEnhanced 已连接并处于前台。", len(paths)), 0x40)
}

func messageBox(title, text string, flags uintptr) {
	user32 := syscall.NewLazyDLL("user32.dll")
	proc := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(title)
	m, _ := syscall.UTF16PtrFromString(text)
	_, _, _ = proc.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), flags)
}
