//go:build !windows

package main

import "errors"

func readSystemClipboardText() (string, uint32, bool, error) {
	return "", 0, false, errors.New("Windows clipboard unavailable")
}
func writeSystemClipboardText(string) error { return errors.New("Windows clipboard unavailable") }
func captureDesktopPNG() ([]byte, int, int, error) {
	return nil, 0, 0, errors.New("Windows screenshot unavailable")
}
func getForegroundWindowInfo() (foregroundWindowInfo, error) {
	return foregroundWindowInfo{Target: "other"}, errors.New("Windows foreground window unavailable")
}
