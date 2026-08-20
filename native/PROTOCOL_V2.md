# PhoneInputEnhanced Native Protocol v2

Transport: WebSocket `ws://<PC>:51877/v2/ws`

The legacy browser `/ws` endpoint remains supported separately.

## Handshake

Client first sends a JSON `hello` command with `protocol: 2` and a unique `requestId`. The Host replies with an ACK containing server version and capabilities. All later commands require a prior successful hello.

Every command has a `requestId`. Every accepted/rejected command returns an ACK with the same `requestId`; failures contain a stable error code/message.

## High-frequency commands

- `move {dx,dy}`
- `scroll {x,y}`
- `button {button,down}`
- `click {button}`
- `release`
- `ping`

## Low-frequency v2 commands

- `hotkey {action: copy|paste}`
- `key {key}`
- `window_switch {target: chatgpt|chrome|wechat}`

## Preview.5 HTTP companion APIs

These use the same LAN Host on port 51877 and are advertised in hello capabilities.

- `GET /api/clipboard` — text clipboard state (`hasText`, `text`, Windows sequence number)
- `POST /api/clipboard` — replace Windows text clipboard
- `GET /api/screenshot` — full Windows virtual-desktop PNG
- `GET /api/foreground-window` — foreground title/process and normalized target
- `GET /api/diagnostics` — Host version/protocol, active connections, uptime, Core health, foreground window

The existing `/core-api/*` compatibility bridge remains the path for text input/readback/selection APIs.

## Input ownership and disconnect safety

Held mouse-button ownership is tracked per WebSocket control session. A session disconnect releases only buttons owned by that session. The physical Windows button is released only when the final owner releases/disconnects. When the final control connection disappears, the Host also performs a complete safety release.

This prevents a stale browser/native session from releasing a button intentionally held by another still-live client.

## Timeouts / heartbeat

Native Android sends heartbeat traffic every 15 seconds while connected. The Host v2 socket read deadline is 45 seconds and is refreshed for every received frame. This bounds stale-session lifetime after abrupt network loss while allowing normal LAN jitter.


## preview.8 voice hotkey

Native clients may send:

```json
{"protocol":2,"type":"hotkey","requestId":"hotkey-1","action":"voice"}
```

The Windows Host maps `voice` to `Win+H`. The hello capability list advertises `hotkey.voice`.

## preview.9 voice behavior

No new v2 command is required. The preview.9 Android `🎤` button opens a local phone-IME relay and mirrors committed text through the existing HTTP input bridge. The existing `hotkey.voice` command is retained for backward compatibility with preview.8 clients, but preview.9 Android no longer sends it.
