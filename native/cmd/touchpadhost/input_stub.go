//go:build !windows

package main

func moveMouse(dx, dy int32) error                 { return nil }
func scrollMouse(horizontal, vertical int32) error { return nil }
func setButton(button string, down bool) error     { return nil }
func clickButton(button string) error              { return nil }
func releaseAllButtons()                           {}
func sendHotkey(name string) error                 { return nil }
