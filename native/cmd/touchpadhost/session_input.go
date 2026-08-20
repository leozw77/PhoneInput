package main

import (
	"errors"
	"sync/atomic"
)

type controlSession struct {
	id   uint64
	held map[string]bool
}

var controlSessionCounter atomic.Uint64

func newControlSession() *controlSession {
	return &controlSession{id: controlSessionCounter.Add(1), held: map[string]bool{}}
}

func (s *server) sessionSetButton(session *controlSession, button string, down bool) error {
	if session == nil {
		return errors.New("control session unavailable")
	}
	if !validMouseButton(button) {
		return errors.New("invalid mouse button")
	}

	s.inputMu.Lock()
	defer s.inputMu.Unlock()

	owners := s.buttonOwners[button]
	if owners == nil {
		owners = map[*controlSession]struct{}{}
		s.buttonOwners[button] = owners
	}
	_, owned := owners[session]
	if down {
		if owned {
			return nil
		}
		if len(owners) == 0 {
			if err := setButton(button, true); err != nil {
				return err
			}
		}
		owners[session] = struct{}{}
		session.held[button] = true
		return nil
	}

	if !owned {
		return nil
	}
	delete(owners, session)
	delete(session.held, button)
	if len(owners) == 0 {
		if err := setButton(button, false); err != nil {
			// Restore ownership so a later disconnect/release can retry.
			owners[session] = struct{}{}
			session.held[button] = true
			return err
		}
	}
	return nil
}

func (s *server) sessionClickButton(session *controlSession, button string) error {
	if session == nil {
		return errors.New("control session unavailable")
	}
	if !validMouseButton(button) {
		return errors.New("invalid mouse button")
	}

	s.inputMu.Lock()
	defer s.inputMu.Unlock()
	if owners := s.buttonOwners[button]; len(owners) != 0 {
		// Never synthesize an up while any session intentionally holds this button.
		// A tap during drag-lock is ignored rather than breaking another session.
		return nil
	}
	return clickButton(button)
}

func (s *server) releaseSessionInput(session *controlSession) {
	if session == nil {
		return
	}
	s.inputMu.Lock()
	defer s.inputMu.Unlock()
	for button := range session.held {
		owners := s.buttonOwners[button]
		delete(owners, session)
		delete(session.held, button)
		if len(owners) == 0 {
			_ = setButton(button, false)
		}
	}
}

func (s *server) clearAllSessionInput() {
	s.inputMu.Lock()
	defer s.inputMu.Unlock()
	for button, owners := range s.buttonOwners {
		for session := range owners {
			delete(session.held, button)
		}
		delete(s.buttonOwners, button)
	}
	releaseAllButtons()
}
