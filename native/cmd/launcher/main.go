//go:build windows

package main

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"

	"phoneinput-touchpad/internal/version"
)

func main() {
	exe, err := os.Executable()
	if err != nil {
		showError("无法确定程序目录", err)
		return
	}
	root := filepath.Dir(exe)
	corePath := filepath.Join(root, "Core", "PhoneInputEnhanced.exe")
	hostPath := filepath.Join(root, "PhoneInputTouchpadHost.exe")
	if !fileExists(corePath) {
		showError("缺少核心程序", fmt.Errorf("未找到 %s", corePath))
		return
	}
	if !fileExists(hostPath) {
		showError("缺少触控板主机", fmt.Errorf("未找到 %s", hostPath))
		return
	}
	// Best-effort convenience integration for occasional APK/small-file transfer.
	// Windows Explorer's "Send to" menu will invoke PhoneInputSendTo.exe with the selected paths.
	_ = ensureSendToMenu(root)

	host := exec.Command(hostPath)
	host.Dir = root
	host.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	if err := host.Start(); err != nil {
		showError("无法启动触控板服务", err)
		return
	}
	// The core page immediately redirects mobile users to the touchpad port.
	// Wait until 51877 is actually listening instead of relying on a fixed 120 ms
	// sleep, which can be too short on cold start / antivirus scanning.
	if err := waitForTouchpadHost(3 * time.Second); err != nil {
		if host.Process != nil {
			_ = host.Process.Kill()
		}
		showError("触控板服务未就绪", err)
		return
	}

	core := exec.Command(corePath, os.Args[1:]...)
	core.Dir = filepath.Dir(corePath)
	core.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	if err := core.Start(); err != nil {
		if host.Process != nil {
			_ = host.Process.Kill()
		}
		showError("无法启动 PhoneInput 核心程序", err)
		return
	}
	_ = core.Wait()
	if host.Process != nil {
		_ = host.Process.Kill()
		_, _ = host.Process.Wait()
	}
}

func ensureSendToMenu(root string) error {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		return nil
	}
	helper := filepath.Join(root, "PhoneInputSendTo.exe")
	if !fileExists(helper) {
		return nil
	}
	dir := filepath.Join(appData, "Microsoft", "Windows", "SendTo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	cmdPath := filepath.Join(dir, "PhoneInputEnhanced.cmd")
	content := "@echo off\r\n\"" + helper + "\" %*\r\n"
	return os.WriteFile(cmdPath, []byte(content), 0o600)
}

func waitForTouchpadHost(timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	address := fmt.Sprintf("127.0.0.1:%d", version.TouchpadPort)
	var lastErr error
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", address, 120*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil
		}
		lastErr = err
		time.Sleep(50 * time.Millisecond)
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("连接 %s 超时", address)
	}
	return lastErr
}

func fileExists(path string) bool { info, err := os.Stat(path); return err == nil && !info.IsDir() }

func showError(title string, err error) {
	text := title
	if err != nil {
		text += "\n\n" + err.Error()
	}
	user32 := syscall.NewLazyDLL("user32.dll")
	messageBox := user32.NewProc("MessageBoxW")
	caption, _ := syscall.UTF16PtrFromString("PhoneInputEnhanced " + version.Version)
	message, _ := syscall.UTF16PtrFromString(text)
	_, _, _ = messageBox.Call(0, uintptr(unsafe.Pointer(message)), uintptr(unsafe.Pointer(caption)), 0x10)
}
