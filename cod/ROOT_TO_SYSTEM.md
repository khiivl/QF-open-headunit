# Root → `android.uid.system` in the `codos` flavor

CoD OS has **no root**. Open Headunit runs there as a system app: `android:sharedUserId="android.uid.system"`, signed with
the CoD platform key, installed in `/system/priv-app/OpenHeadunit/`. Every place where upstream reaches for `su`, libsu
or a self-ADB connection (127.0.0.1:5555 / 9876) needs a different answer in that flavor.

How the flavor is selected: `BuildConfig.CODOS` (`true` only in the `codos` product flavor, `false` in `playstore` and
`github`). Every change below sits behind `if (BuildConfig.CODOS)`, so the other flavors keep the upstream behaviour
exactly. Status per item: **replaced** (a uid-1000 equivalent runs instead), **disabled in codos** (needs uid 0 or a
capability a system-uid app does not have; one log line, nothing else), **unchanged** (never needed root).

What "as system" means here: `sh -c <cmd>` executed by the app process itself (uid 1000, SELinux domain of a
platform-signed priv-app). Binder-backed tools (`pm`, `am`, `cmd`, `svc`) work because the system uid is granted every
signature permission they check (`CHANGE_COMPONENT_ENABLED_STATE`, `FORCE_STOP_PACKAGES`, …). Where an Android API exists
(WifiManager) it is used instead of a shell. 🔬 Reasoning from the AOSP permission model; 📻 verification on a unit is
still to be done on stand 198 (this build has not been installed anywhere).

## Inventory

| # | Where | Command / call | Why upstream does it | codos | Status |
|---|---|---|---|---|---|
| 1 | `utils/SUExecutor.kt` `RootImpl` | libsu `Shell.cmd`, `Shell.getShell()` with `FLAG_MOUNT_MASTER` | root shell for `setprop` | only `SystemUidImpl` is registered: `sh -c` as uid 1000 | replaced |
| 2 | `utils/SUExecutor.kt` `LegacySuImpl` | `su -c id`, `su -c <cmd>` | same, API < 21 | not registered (codos minSdk is 21) | replaced |
| 3 | `utils/SUExecutor.kt` `ShizukuImpl` / `PrivilegedService` | Shizuku user service running `su -c <cmd>` | shell/root through Shizuku | not registered | replaced |
| 4 | `SUExecutor.setProp` ← `CarFYTReceiver` | `setprop sys.carlink.type 0/2` | take/release key focus from the FYT `com.syu.ms` service | `setprop` as system. The path is FYT-only (`ro.fyt.platform` / `syu.fyt.platform`), never taken on QF | replaced |
| 5 | `utils/SUExecutor.execShell(asRootUser=true)` | `su -c` | a root-only variant | runs as system, one log line that root is not available | disabled in codos (root flag ignored) |
| 6 | `utils/OemAppManager.checkAvailableBackend` | libsu `isAppGrantedRoot`, then a probe of 127.0.0.1:5555 / 9876 | choose root or self-ADB | new backend `SYSTEM`, no probe of either | replaced |
| 7 | `utils/OemAppManager.executeCommand` | libsu `Shell.cmd`, then `AdbManager.exec` (self-ADB) | run a shell command with privileges | `sh -c` as system | replaced |
| 8 | `OemAppManager.getDisableCommands` | `pm disable-user --user 0 <pkg>`, `am force-stop <pkg>` | switch off a conflicting OEM projection app (Z-Link, AutoKit, …) | unchanged text, now run as system (`CHANGE_COMPONENT_ENABLED_STATE`, `FORCE_STOP_PACKAGES`) | replaced |
| 9 | `OemAppManager.getDisableCommands` | `pkill -f <daemon>` | kill root-owned vendor daemons | omitted: signalling another uid's process needs `CAP_KILL`, which an app process under uid 1000 does not keep | disabled in codos |
| 10 | `OemAppManager.getRestoreCommands` | `pm enable --user 0 <pkg>` | undo #8 | as system | replaced |
| 11 | `OemAppManager.getRestoreCommands` | `[ -f /system/bin/zlink5.sh ] && setsid … &` | restart the Z-Link root daemon | omitted: starting a root daemon needs uid 0 | disabled in codos |
| 12 | `OemAppManager.detectApps(checkDaemons)` | `pgrep -f <daemon>` | show whether a vendor daemon runs | as system (read-only) | replaced |
| 13 | `main/AppKiller.kt` | `am force-stop <pkg>` (via `OemAppManager.executeCommand`) | "stop app" in the launcher lists | as system | replaced |
| 14 | `connection/zlinkcarplay/ZlinkScreenPolicy.kt` | `pm disable/enable <pkg>/<activity>` (via `executeCommand`) | hide the Z-Link UI while CarPlay-via-Z-Link runs | as system | replaced |
| 15 | `connection/zlinkcarplay/ZlinkRedirect.kt` | `iptables -t nat -A/-C/-D OUTPUT … --uid-owner 0 -j REDIRECT` | send the root Z-Link daemon's loopback traffic (1666/1777/1888/1999) to OHU's in-process proxy | `ensure`/`remove` return `false` with one log line: netfilter needs `CAP_NET_ADMIN`, and the rule matches uid 0 traffic that does not exist when Z-Link is not the CarPlay source. **Final 24.11 (design §13.3):** under the shipping model A Z-Link is disabled in OHU mode, so CarPlay-through-Z-Link does not exist there and this rule is never needed; the init-oneshot alternative (§11.4) belongs to the future Option B only | disabled in codos (interim) |
| 16 | `utils/WifiStackRecovery.requestRoot` | libsu `Shell.getShell()` | ask for root once, in the settings screen | returns `false` (nothing to ask) | disabled in codos |
| 17 | `utils/WifiStackRecovery.verifyAdb` | `AdbManager.exec(echo ok)` over self-ADB | prove the self-ADB route at setup | not called: the settings probe sees the `SYSTEM` route first | disabled in codos |
| 18 | `utils/WifiStackRecovery.restartWifi` | `svc wifi disable && sleep 3 && svc wifi enable` through #7 | free a wedged Wi-Fi Direct stack (Android 10+ refuses `setWifiEnabled` to ordinary apps) | `WifiManager.setWifiEnabled(false)`, 3 s, `setWifiEnabled(true)`: a system-uid app is privileged, so it is allowed. If the API refuses, the same `svc` line runs as system | replaced |
| 19 | `utils/adb/AdbManager.kt` (self-ADB client) | RSA-authenticated ADB over 127.0.0.1 | the unprivileged fallback for #7 / #17 | not reached from the codos paths above | disabled in codos |
| 20 | `connection/wifi/modes/nativeaa/blink/BlinkAutoSerialChannel.open` | `su -c <stock-client guard + copy loop>` on `/dev/auto_serial` | the FYT `blink` bridge, root variant | throws `IOException`; the carrier already treats that as "su denied" and refuses; one log line. FYT-only, off by default | disabled in codos |
| 21 | `BlinkAutoSerialChannel.openDirect` | `sh -c <copy loop>` as the app | same bridge without root | unchanged (it never used root) | unchanged |
| 22 | `utils/LogExporter.kt` (two places) | `logcat -v threadtime …`, `logcat -d …` | save logs | unchanged; as system the app can also read other apps' log lines (`READ_LOGS`) | unchanged |
| 23 | `utils/InterfaceMacReader.fromIpLink` | `ip link show <iface>` | read the P2P interface MAC | unchanged | unchanged |
| 24 | `connection/wifi/direct/WifiDirectManager.kt:3325` | `ip link show <iface>` | same, second copy | unchanged | unchanged |
| 25 | `connection/wifi/scan/WifiScanControl`, `WifiScanUserService`, `FytShizukuStarter` | Shizuku user service (shell uid) and, on FYT Android < 11 only, a self-ADB bootstrap | pause Wi-Fi scanning during wireless Android Auto | not root. Inert on CoD: needs the Shizuku manager and the FYT `com.syu.ms` service, neither exists there. Left untouched. A later task can do the same job with `WifiManager` directly as system uid | unchanged |
| 26 | manifest `android.permission.ACCESS_SUPERUSER` | permission request for Superuser apps | tell a Superuser app that root is wanted | removed from the codos manifest (`tools:node="remove"`) | disabled in codos |

| 27 | `utils/HotspotConfigClient.buildSetCommand` / `buildEnsure5GhzCommand` (from the author's fork, merged 23.11.2026) | `su 1000 sh -c "CLASSPATH=$APK app_process … HotspotConfigTool set/ensure-5ghz"` through #7 | set the unit's hotspot name and password, keep it on 5 GHz: Android's hotspot API wants the system user, which upstream reaches from root with `su 1000` | the `su 1000 sh -c` hop is left out; the same `app_process` line runs as the app's own uid 1000 | replaced |
| 28 | `utils/HotspotConfigClient.ensure5Ghz`, `utils/HostapdConfig.READ_COMMAND` (same merge) | `cat /data/vendor/wifi/hostapd/hostapd_*.conf /data/misc/wifi/hostapd.conf` through #7 | read the hotspot's real name, password and band from the unit's hostapd | unchanged text, run as system. 📻 whether the SELinux domain of a platform-signed priv-app may read those files and exec `app_process` is not verified; a refusal comes back as a non-zero code, which the callers treat as "unknown" | unchanged |
| 29 | `connection/zlinkcarplay/ZlinkService` (`am start-foreground-service … zlink … DaemonService`, `am force-stop com.zjinnova.zlink`) (same merge) | via #7 | restart the Z-Link app's own daemon service so it reconnects through OHU's proxy | as system. Not a root daemon start (that one is #11); the proxy still gets no traffic in codos while #15 is off | unchanged |
| 30 | `input/BridgeVoiceKeyWatcher` (author's fork, merged 24.11.2026) | `AdbManager.streamLines` over self-ADB 127.0.0.1:5555 / 9876: `logcat -T 1 -v brief -s McuKeyEventHandler:D` | follow the unit's key service log to forward wheel and panel buttons to Android Auto / CarPlay | `SystemUidShell.streamLines`: the same `logcat` line run by the app itself as uid 1000 (it holds `READ_LOGS`); no port probe. 📻 not verified: that the platform-signed priv-app may read that log tag | replaced |
| 31 | `utils/OemAppManager` `rootInUse`, `requestRootNow`, `requestRootIfNotGranted`, `runAsRoot`, `streamLinesAsRoot`, `hasShellAccess` (author's QF5/QF6, merged 26.11.2026) | libsu `Shell`, `su -c`, local ADB as the fallback; settings `shellRootPreferred`, `rootOnlyDebug` | choose root or ADB as the way into a shell; log following through root | `checkAvailableBackend` and `executeCommand` return the uid-1000 route first (`SystemUidShell`); `rootInUse()` is false, `requestRoot*` return at once, `BridgeVoiceKeyWatcher` never takes `viaRoot`; the row `rootOnlyDebug` is hidden (`SettingsProfilePolicy.HIDDEN_ON_CODOS`) | disabled in codos |
| 32 | `main/MainActivity.checkShellAccess`, `main/NoShellAccessActivity` (same merge) | a full-screen notice that cannot be closed when neither root nor local ADB answers | tell the person the unit offers no shell | `hasShellAccess()` is true while the app runs as uid 1000 (`SYSTEM` route), so the notice is not raised. 📻 not verified on the stand; on a copy that does not run as uid 1000 the notice would appear | unchanged, not reached |
| 33 | `utils/HardwareComposer` (`service call SurfaceFlinger 1008 i32 0/1`), `utils/TouchPanelOverride` (`cat`/`dd` on `/proc/gt9xx_config`, temp file in `/data/local/tmp`) (same merge) | root or self-ADB through #7 | switch SurfaceFlinger's hardware overlays; raise the Goodix panel's report rate | `HardwareComposer.apply` returns at once in codos (one log line) and its row is hidden: the system switch (CarSettings, `persist.sys.qf001.hwc`, only `HwcTrial` writes it) is the one place. `TouchPanelOverride.set` (and so `apply`) returns at once in codos and its row is hidden: 📻 on stand 198 `/proc/gt9xx_config` is root:root 0664, so uid 1000 cannot write it and the toggle would silently do nothing; the touch rate is a system feature with one writer, the framework's trial manager (stable id `touch_rate`), and OHU will read its state through the same contract as the HWC one. Gate: head lead, 27.11 | both disabled in codos |
| 34 | `utils/QfLauncherSelect`, `utils/CarLauncherManager` (same merge) | broadcast `com.qf.action.SET_LAUNCHER` to CarSettings (no root) | make Open Headunit the unit's launcher, as the Factory screen does | `current()` returns empty in codos, so `isSupported()` is false, `select()` never runs and `CarLauncherManager` takes its old role path. The launcher of CoD OS is the ROM's to choose | disabled in codos |
| 35 | `main/OnboardingActivity` system step (`ZlinkScreenPolicy.applyVisibility` = `pm disable` of Z-Link's activities through #7; root request) and launcher step (same merge) | root or self-ADB | hide the Z-Link app on first start; ask for root | `MainActivity.checkSetupFlow` marks the wizard done in codos, so it is never shown; the switch of the projection owns the Z-Link app (contract section 10) | disabled in codos |
| 36 | `connection/zlinkcarplay/CarPlayHomeIcon` (same merge) | `nsenter -t 1 -m` plus `mount -t tmpfs` and `mount -o bind` over `/dev/kalos.png`, through #7 | replace CarPlay's car-maker tile picture | the call in `App.onCreate` is guarded `!BuildConfig.CODOS`; uid 1000 could not mount anyway | disabled in codos |
| 37 | `utils/HotspotManager` `setHotspotEnabled` / `restart` / `disableAndAwaitDown` (`wm.isWifiEnabled = false`, `setSoftApConfiguration`, `TetheringManager.startTethering`), `EarlyHotspot`, `SoftApCredentialsProvider.enableHotspot`, `StationStandDown.standDown` (`disconnect`, `disableNetwork`), `WifiStackRecovery.restartWifi` (`setWifiEnabled`, `svc wifi`) (author's code, found 27.11) | system-uid calls that really switch the unit's AP and Wi-Fi client; not root, but allowed to a uid-1000 app | raise the hotspot for native Android Auto, take the station off the network | nothing is switched in codos: the access point and the Wi-Fi client are the system's (head lead, owner 27.11). Every function above returns at once with one log line, `EarlyHotspot.start` returns, `autoEnableHotspot` defaults to false and its row is hidden; `SoftApCredentialsProvider` still only reads an AP that is up. Tripwire: `CodosAccessPointGateTest`. 📻 on 27.11 the unit raised CarWifi_2290 with no phone and lost Wi-Fi and Ethernet; the Ethernet drop was not OHU (AX88179 usbnet RX_MEMORY, head lead 📻); the Wi-Fi switch-off at 00:29:49 was OHU's (`CMD_WIFI_TOGGLED 0`, uid 1000) and overwrote the user's `wifi_on` (contract 10.8 R-ETH). The earlier uid-1000 `WifiManager.setWifiEnabled` restart in `WifiStackRecovery` is removed | disabled in codos |

Nothing in `cpp/` or in the `contract` module uses `su`, libsu or ADB.

## Policy flags still visible in the UI

`wifiRestartRootGranted` / `wifiRestartAdbVerified` are kept as they are. In codos the settings probe finds the `SYSTEM`
route and shows its own message (`auto_restart_wifi_system`); the OEM-apps dialog shows `oem_app_backend_system`
("Execution method: system app (uid 1000)"). No UI is redesigned: that is Volodymyr's later work.

## Other `codos` differences (not about root)

- **No HOME filter.** The launcher role lives only in the developer section (owner's rule), so the `codos` manifest
  overlay replaces `CarLauncherAlias` with a copy that has no `HOME` intent filter. The component still exists, so
  `CarLauncherManager` can toggle it by name without an exception; enabling it does not make the app a home app. The
  other flavors keep the filter.
- **ARM only.** `codos` ships `arm64-v8a` and `armeabi-v7a`; x86 / x86_64 are left out of the system image.
- **Native libraries stored, not extracted.** A bundled `/system/priv-app` gets no extraction, so `codos` packs the
  `.so` files uncompressed and page-aligned (`useLegacyPackaging = false`, `extractNativeLibs=false`).

## Not here, on purpose

No licence check and no CoD-firmware binding (contract §0). The package id stays `com.andrerinas.headunitrevived`; the
owner and Volodymyr decide the final id (contract §1). A copy of the same id installed from `/data/app` with another
signer, such as the 4.1.0-beta1 on stand 198, has to go before this one can be installed there.
