# zLink CarPlay Feed Integration (tools/zlink-tee)

> **Note:** The standalone `Proxy.java` and separate port 17000 distribution process are now **obsolete**.
> All proxying, frame caching, offer holding, and touch injection logic has been brought directly into Open Headunit (OHU) in `com.andrerinas.openheadunit.connection.zlinkcarplay.*`. `Proxy.java` is retained solely as historical reference.

## Architecture

On QF K706 head units running zLink (`com.zjinnova.zlink`) with its native daemon (`z-link`, running as root):
- **Daemon <-> App channels** (loopback TCP, the app originally listened on ports 1666, 1777, 1888, 1999):
  - 1666: Audio PCM
  - 1777: Control (heartbeat 0x118, touch 0x111, session mode 0x102)
  - 1888: H.264 video feed
  - 1999: Extra / unused
- **Transparent Redirection**:
  - `iptables -t nat` redirects TCP connections destined for ports 1666/1777/1888/1999 to in-app proxy ports 11666/11777/11888/11999.
  - Sockets are forwarded directly to upstream app ports 1666/1777/1888/1999.
  - The real zLink app stays in the path, ensuring zLink's license, activation, and cryptographic handshakes remain 100% untouched.
  - Redirect rules are installed on early-boot via `zlink-redirect.rc` or ensured idempotently on startup by `ZlinkRedirect` via `OemAppManager` (libsu / Self-ADB).

## In-App Components

- **`ZlinkProxy`**: In-process TCP server listening on loopback ports 11666, 11777, 11888, 11999, bridging to 1666, 1777, 1888, 1999.
- **`ZlinkHub`**: In-process distribution hub providing lock-free frame distribution, GOP replay caching (IDR + SPS/PPS), hold-mode rewriting (keeping wireless CarPlay on offer), and touch event injection.
- **`ZlinkService`**: Foreground service managing `ZlinkProxy`, power listeners, and raising `ZlinkCarPlayActivity` on session start.
- **`ZlinkCarPlayActivity`**: Renders CarPlay video on `SurfaceView`, handles touch events, back button dialog ("Disconnect CarPlay" / "Go home"), and connection loss retry overlays.
- **`ZlinkSessionMonitor`**: State machine tracking connection health (CONNECTED, LOST after 7s silence, GAVE_UP after 15s).
- **`ZlinkPowerReceiver` & `ZlinkPowerPolicy`**: Handles vehicle ignition, shutdown, and screen events (3s screen-off grace period; ends session and records power state on vehicle ACC off).

## Helper Scripts

- `hide-zlink.sh`: Hides the zLink app's UI activities (`pm disable`) so the native daemon keeps its peer and wireless CarPlay projection is rendered exclusively inside Open Headunit.
- `zlink-redirect.rc`: `init.rc` snippet for early-boot iptables redirection.
- `Proxy.java`: Historical standalone reference implementation.
