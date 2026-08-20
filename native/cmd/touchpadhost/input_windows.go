//go:build windows

package main

import (
	"fmt"
	"sync"
	"syscall"
	"unsafe"
)

const (
	inputMouse         = 0
	inputKeyboard      = 1
	mouseEventMove     = 0x0001
	mouseEventLeftDn   = 0x0002
	mouseEventLeftUp   = 0x0004
	mouseEventRightDn  = 0x0008
	mouseEventRightUp  = 0x0010
	mouseEventMiddleDn = 0x0020
	mouseEventMiddleUp = 0x0040
	mouseEventWheel    = 0x0800
	mouseEventHWheel   = 0x1000
	keyEventKeyUp      = 0x0002
	vkControl          = 0x11
	vkLWin             = 0x5B
	vkC                = 0x43
	vkH                = 0x48
	vkV                = 0x56
)

type mouseInput struct {
	dx          int32
	dy          int32
	mouseData   uint32
	dwFlags     uint32
	time        uint32
	dwExtraInfo uintptr
}

type input struct {
	typeID uint32
	_      uint32
	mi     mouseInput
}

const windowsInputSize = unsafe.Sizeof(input{})

var _ [40 - windowsInputSize]byte
var _ [windowsInputSize - 40]byte

type keyboardInput struct {
	wVk         uint16
	wScan       uint16
	dwFlags     uint32
	time        uint32
	dwExtraInfo uintptr
}

type keyboardPacket struct {
	typeID uint32
	_      uint32
	ki     keyboardInput
	_      [8]byte
}

const windowsKeyboardInputSize = unsafe.Sizeof(keyboardPacket{})

var _ [40 - windowsKeyboardInputSize]byte
var _ [windowsKeyboardInputSize - 40]byte

var (
	user32        = syscall.NewLazyDLL("user32.dll")
	procSendInput = user32.NewProc("SendInput")
	mouseStateMu  sync.Mutex
	keyboardMu    sync.Mutex
	leftHeld      bool
	rightHeld     bool
	middleHeld    bool
)

func sendMouse(flags uint32, dx, dy int32, data int32) error {
	in := input{typeID: inputMouse, mi: mouseInput{dx: dx, dy: dy, mouseData: uint32(data), dwFlags: flags}}
	ret, _, callErr := procSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
	if ret != 1 {
		if callErr != syscall.Errno(0) {
			return callErr
		}
		return fmt.Errorf("SendInput returned %d", ret)
	}
	return nil
}

func moveMouse(dx, dy int32) error {
	if dx == 0 && dy == 0 {
		return nil
	}
	return sendMouse(mouseEventMove, dx, dy, 0)
}

func scrollMouse(horizontal, vertical int32) error {
	if vertical != 0 {
		if err := sendMouse(mouseEventWheel, 0, 0, vertical); err != nil {
			return err
		}
	}
	if horizontal != 0 {
		if err := sendMouse(mouseEventHWheel, 0, 0, horizontal); err != nil {
			return err
		}
	}
	return nil
}

func setButton(button string, down bool) error {
	mouseStateMu.Lock()
	defer mouseStateMu.Unlock()

	var flag uint32
	switch button {
	case "left":
		if leftHeld == down {
			return nil
		}
		if down {
			flag = mouseEventLeftDn
		} else {
			flag = mouseEventLeftUp
		}
		leftHeld = down
	case "right":
		if rightHeld == down {
			return nil
		}
		if down {
			flag = mouseEventRightDn
		} else {
			flag = mouseEventRightUp
		}
		rightHeld = down
	case "middle":
		if middleHeld == down {
			return nil
		}
		if down {
			flag = mouseEventMiddleDn
		} else {
			flag = mouseEventMiddleUp
		}
		middleHeld = down
	default:
		return fmt.Errorf("unsupported mouse button %q", button)
	}
	if err := sendMouse(flag, 0, 0, 0); err != nil {
		// Roll back local state if Windows rejected the event.
		switch button {
		case "left":
			leftHeld = !down
		case "right":
			rightHeld = !down
		case "middle":
			middleHeld = !down
		}
		return err
	}
	return nil
}

func clickButton(button string) error {
	if err := setButton(button, true); err != nil {
		return err
	}
	return setButton(button, false)
}

func releaseAllButtons() {
	mouseStateMu.Lock()
	l, r, m := leftHeld, rightHeld, middleHeld
	mouseStateMu.Unlock()
	if l {
		_ = setButton("left", false)
	}
	if r {
		_ = setButton("right", false)
	}
	if m {
		_ = setButton("middle", false)
	}
}

func sendKeyboard(vk uint16, down bool) error {
	flags := uint32(0)
	if !down {
		flags = keyEventKeyUp
	}
	in := keyboardPacket{typeID: inputKeyboard, ki: keyboardInput{wVk: vk, dwFlags: flags}}
	ret, _, callErr := procSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
	if ret != 1 {
		if callErr != syscall.Errno(0) {
			return callErr
		}
		return fmt.Errorf("SendInput keyboard returned %d", ret)
	}
	return nil
}

func sendHotkey(name string) error {
	keyboardMu.Lock()
	defer keyboardMu.Unlock()

	var modifier uint16
	var key uint16
	switch name {
	case "copy":
		modifier, key = vkControl, vkC
	case "paste":
		modifier, key = vkControl, vkV
	case "voice":
		// Windows voice typing toggle. Keeping this in the Host means Android does not
		// need microphone permission or an on-device speech-recognition dependency.
		modifier, key = vkLWin, vkH
	default:
		return fmt.Errorf("unsupported hotkey %q", name)
	}

	if err := sendKeyboard(modifier, true); err != nil {
		return err
	}
	modifierDown := true
	defer func() {
		if modifierDown {
			_ = sendKeyboard(modifier, false)
		}
	}()

	if err := sendKeyboard(key, true); err != nil {
		return err
	}
	keyDown := true
	defer func() {
		if keyDown {
			_ = sendKeyboard(key, false)
		}
	}()

	if err := sendKeyboard(key, false); err != nil {
		return err
	}
	keyDown = false
	if err := sendKeyboard(modifier, false); err != nil {
		return err
	}
	modifierDown = false
	return nil
}
