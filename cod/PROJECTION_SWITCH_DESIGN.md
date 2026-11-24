# Projection switch: Z-Link ⇄ Open Headunit — design draft 1

Author: Open Headunit session (lead of this design), 24.11.2026. For the head lead's gate. **Design only: nothing is
installed, enabled or changed on stand 198 or on any unit.** Brief: `D:\HeadUnitRomBuilder\.agents\briefs\PROJECTION_SWITCH_BRIEF.md`.
Owner (24.11): «перемикач Z-Link, хур і обратно» — «Так, починати». Marks: 🔬 read in code or firmware, 📻 measured,
🧩 inferred, ❓ not verified. Every ❓ is a named item in section 9 (research or proof) — nothing ❓ is relied on silently.

**Scan coverage of this draft (be honest about it):** pass 1 is the Android 10 decompiles in `D:\De-compiled`
(QF_Bluetooth, QF_AiLit_Bridge, QF_CarSettings, QF_CommonFunc, QF_Canbus, launchers, SystemUI, framework, services,
wifi-service) plus the knowledge canon (`qf-platform` 52, 51, 32) and `ZLINK_MAP.md`. The **A14 graft** (the system the
owner actually runs) was not re-scanned: the QF apps there are ports of the same code, so the list below is the
*expected* set; pass 2 over the real A14 image is task R5 (section 9). Where a name differs on A14 (`AssistRouter`), the
A10 class is named and the A14 one is "to confirm".

## 1. The answer in ten lines

1. **One setting**: `Settings.Global cod_projection_app` = `openheadunit` | `zlink`. Absent = `openheadunit` (default).
   It is the only input. The only writer is the person, through one CarSettings row. (The name was proposed in contract §3.)
2. **One reconciler** (`ProjectionSwitch.apply()`, uid system) turns the setting into the whole system state. It is
   idempotent. It runs at every boot after `boot_completed` and after every change, so a half-switched state heals itself.
3. **Disabling the app is not enough.** Z-Link has an init daemon (`z-link`, user shell) that lives in
   `/data/data/com.zjinnova.zlink/lib` and survives `pm disable`; it makes the USB gadget, raises the soft-AP, runs
   `zbt-main`. The reconciler therefore stops/starts the daemon too, and the image stops starting it by itself (R2).
4. **One resolver** answers «which projection app is it?» for everyone who today hard-codes `com.zjinnova.zlink`
   (about 25 call sites, section 4). The projection app's own entry points are the same two things both ways: a launch
   component and a state broadcast.
5. **OHU emits Z-Link's state contract** (the `com.zjinnova.zlink` broadcast with `status` / `phoneMode`), so QF Bluetooth,
   Canbus and the voice router keep working unchanged. This is the cheapest correct way and needs no patch in four
   system apps. Details in section 6.
6. **On CoD OS the OHU build is stand-alone** (Android Auto on its own radio, own hotspot). Volodymyr's «zLink unit»
   branches (OHU riding on Z-Link's daemon for CarPlay) are switched off in `codos`. **This costs CarPlay in OHU mode —
   owner decision D1.**
7. **Closing the radios:** soft-AP and Wi-Fi Direct group are stopped by the reconciler in both directions (the
   process dying does not do it, 📻 Z-Link Exit leaves the AP up). Bluetooth: OHU's records die with its process 🧩;
   Z-Link's Android Auto record may live in the car BT **module** ❓ — R1 is the one unknown that can break the design.
8. **Failure path:** the setting is written last, never first; apply() is re-run until the observed state equals the
   setting; both-active is impossible because «stop old» always precedes «start new», and a crash between leaves
   «neither», which the next apply() fixes. Never both.
9. **Migration:** the new setting ignores the stock `carplay_app` flag (zj/carletter). Everyone gets OHU by default
   — D2 asks the owner whether a unit that had Z-Link selected should stay on it.
10. **Nothing here needs root.** Everything runs as uid system (CarSettings) or in init (a one-time image change).

## 2. State model

| thing | role | writer | readers |
|---|---|---|---|
| `Settings.Global cod_projection_app` | the choice; the only input | CarSettings row (the person) | reconciler, OHU self-check, resolver, any app (public read) |
| package enabled state of `com.zjinnova.zlink` and of the OHU package | *derived* | reconciler only | PackageManager |
| init service `z-link` running or not | *derived* | reconciler (`ctl.start` / `ctl.stop`) | init |
| `sys.qf.carplay.connect`, `sys.qf.bt.aa.connect`, `sys.carplay.bt.call` | *runtime link state*, not the choice | QF BT `BTReceiver`, on the projection app's broadcast | AiBridge, Canbus, framework `MediaFocusControl` |

Nothing derived is ever read back as the choice. There is no persisted mirror property (the brief's «no prop crutch»);
the runtime link props above already exist today and are not a second copy of the choice.

The stock firmware already has a one-writer switch for «which carplay app»: `QFCarplayConfig` (framework): flag file
`/great/protect_dir/carplay_app` = `zj` | `carletter`, copied by SystemServer into the **read-only** `ro.qf.carplay.type`
at boot, changed by the FactorySettings row «phone link app» **with a reboot**. 🔬 `android/qf/os/QFCarplayConfig.java`,
`SystemServer.java:238`, `FactorySettingsActivity.java:2453-2480`. We do not extend it: it needs a reboot (the brief
wants none) and its `ro.` copy would go stale after a live switch. Readers of `ro.qf.carplay.type` (QF BT
`Function.onEasyconnect`, `Function.java:334`) move to the resolver (H12). The factory row stays for `carletter`
units and is hidden on the CoD image (repair).

## 3. Mechanism: layers, what each closes, what it leaves

L1 **Package state** — `PackageManager.setApplicationEnabledSetting(pkg, COMPONENT_ENABLED_STATE_DISABLED, 0)` from
uid system (CarSettings holds `CHANGE_COMPONENT_ENABLED_STATE`; use DISABLED, not DISABLED_USER, so the user cannot
undo it in Settings). Kills the process, removes launcher entry, stops every manifest receiver, service and activity of
that package (`StartupBroadcastReceiver`, `UsbAttachedActivity`, `BootCompleteReceiver`, `AutoStartReceiver` …).
*Leaves:* the data directory and the libraries (so Z-Link's daemon keeps working), native daemons, radios, properties,
registered Bluetooth records, a running session.

L2 **Z-Link init daemon** (`service z-link /system/bin/zlink5.sh`, class main, oneshot, user shell; 🔬 init.rc:1478,
canon 52). `zlink5.sh` writes `rmem_max`, creates the USB gadget functions `iap.gs0` and `ncm.gs1`, then every second
starts `/system/bin/z-link -c qianfeng` if absent. The engine launches `z-mdnsd`, `hostapd_cli`, `z-usbmuxd`,
`libzbt-main`, `iptables` rules (`add_iptables_protect`). Closing:
- **Image (one line, R2 + ROM builder):** `service z-link … disabled` — it no longer starts at boot by itself.
  🧩 The stock gate «ZJlink broadcast boot not completed → return» (`ActivityManagerShellCommand.java:1052`) shows the
  vendor already delays Z-Link until `boot_completed`, so starting it from the reconciler after `boot_completed` is
  consistent with today's behaviour.
- **Runtime:** reconciler does `ctl.stop z-link` / `ctl.start z-link`. Precedent: QF Bluetooth does
  `ctl.stop gocsdk_zj` (`BluetoothModeManager.needToChangeToInternalBT` / `needToChangeToExtaBT`). Needs sepolicy for the caller's domain to set
  `ctl.start$z-link` / `ctl.stop$z-link` (repair, proof P7).
- 🧩 `ctl.stop` kills the service's whole process group, which takes `z-mdnsd`, `zbt-main` and the helpers with it
  (they are children of the engine). ❓ proof P4: `ps -A` shows no leftover `z-*` process after stop.
- *Leaves:* USB gadget functions created earlier in configfs (R3): with the image change they are never created while
  OHU is selected; on a live switch from Z-Link they may stay until the next boot ❓.

L3 **Soft-AP and Wi-Fi Direct.** Z-Link raises the AP through the framework (`startTetheredHotspot`) and does not drop
it on Exit (📻 owner 23.11 00:45). OHU's hotspot and P2P group (`EarlyHotspot`, `WifiDirectManager`) are the same kind
of object. The reconciler, as uid system, calls `TetheringManager.stopTethering(TETHERING_WIFI)` and removes any P2P
group **in both directions, after the old app is stopped and before the new one starts** (otherwise the old daemon
re-raises it). ❓ P5: AP down, wlan0 client back, with no reboot.

L4 **Bluetooth.** Two radios exist (🔬 canon 52; 📻 stand 198): the SoC radio (Android `BluetoothAdapter`) and the car
Bluetooth **module** (Suding `sd`/`gocsdk_zj`, `rw.zlink.bt.type=extra`). OHU registers its RFCOMM server (Android Auto
UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`) and a stand-in HFP record with `listenUsingRfcommWithServiceRecord` on the SoC
radio (`NativeAaHandshakeManager.kt:236`, `launchHfpServer`). Z-Link serves the same UUID on the module.
- OHU side: records belong to the process; they disappear when it dies 🧩 (proof P6: `sdptool`/btsnoop shows none after
  `disable`). Belt: OHU's own entry points check the setting and register nothing unless selected (section 6).
- Z-Link side: where its AA service record lives is **not known** ❓ (R1). If it is created by the daemon/app at start
  and removed by it at stop — nothing to do beyond L2. If it is static in the module firmware, a Z-Link-selected unit has
  an AA record on the module **while OHU is selected**, which is the original failure with roles swapped. Closing then
  needs a module command or `ctl.restart gocsdk_zj` in the switch flow, with the HFP cost that implies.
- The phone's pairing must not change across a switch (P8). A switch never un-pairs or re-pairs anything.

L5 **USB.** Z-Link needs the gadget for wired CarPlay (`iap`+`ncm`); OHU needs the host for wired Android Auto.
OHU's `UsbAttachedActivity` dies with L1. The gadget part is R3 (state of configfs, UDC, hub) and proof P9. 🧩 With the
image change the gadget functions are created only by an explicit Z-Link start.

L6 **Whitelists that keep an app alive across ACC cycles** name Z-Link today and must name both (a disabled package in
a list is inert): `NotKillAppsBeforeSleep.ini` (AiBridge module, line 38), `BackgroundCleanHelper.qfACCOFFBlackList`
(`BackgroundCleanHelper.java:304`), Unisoc autolaunch (`appPowerSaveConfig.xml`, canon `qf-app-survival`). OHU is in
none of them today ❓ (R5) — without it OHU is killed at sleep or never autostarts. `QFSleepWakeup.java:347`
`forceStopPackage(zlink)` at sleep is harmless on a disabled package.

## 4. Register of hooks (pass 1, A10 decompiles; 🔬 unless marked)

Columns: **who/where** · what it does with the projection app · thread / lifecycle · **when the app is disabled** · action (owner).

### A. Z-Link's own start paths
| id | who/where | what | thread / lifecycle | failure with Z-Link disabled | action |
|---|---|---|---|---|---|
| A1 | init `service z-link`, `zlink5.sh` | starts the daemon; gadget; watchdog loop | init, class main, at boot | **keeps running** (libs stay in `/data/data`); cycles every ~8 s when the app is gone (📻 AA failure signature canon 52) | L2: `disabled` + `ctl.*` (R2, ROM builder) |
| A2 | Z-Link `StartupBroadcastReceiver` (`LOCKED_BOOT_COMPLETED`, `START_DAEMON_SERVICE`, `START_ZLINK_SERVICE`), `DaemonService` | starts the app side | main thread, boot / `am` | inert | none (L1) |
| A3 | AMS shell command gate `ZJ_ACTION` (`ActivityManagerShellCommand.java:1052,1084`) | refuses the Z-Link start broadcast before `boot_completed`, rate-limits it (`ClickHelper`) | binder thread of `am` | no receiver, returns | leave; it is also our proof that a late start is normal |
| A4 | `BroadcastQueue.java:54` | constant `ZJ_PKG` declared; no other use found in the decompile | — | — | none ❓ A14 re-check |
| A5 | Unisoc autolaunch list, `NotKillAppsBeforeSleep.ini:38`, `qfACCOFFBlackList` (`BackgroundCleanHelper.java:304`), `QFSleepWakeup.java:347` | keeps Z-Link alive over ACC-off / force-stops it at sleep | system_server, ACC events | inert | L6: add OHU (R5) |

### B. System code that calls the projection app (hard-coded `com.zjinnova.zlink`)
| id | who/where | what | thread / lifecycle | failure with Z-Link disabled | action |
|---|---|---|---|---|---|
| B1 | QF BT `McuKeyEventManager` `acceptEventInCarplay` → `RunApp(zlink)` (`:132`) | accept call key during CarPlay: open Z-Link | MCU key callback | `RunApp` finds no launchable app → nothing opens ❓ (check `Function.RunApp` handling) | resolver (QFBT) |
| B2 | same `broadcastToZLINK` (`:107-118`) `com.qf.action.cp.call`, `setPackage(zlink)`, extra `data` | accept / reject key into the app | same | broadcast to a disabled package is **silently dropped**: the call key does nothing in the projection | resolver; OHU needs a receiver for this action from uid 1000 only (section 6) |
| B3 | same `jumpToPhoneCallUI` (`:176-178`) | when AA connected, call UI = Z-Link | same | nothing opens | resolver |
| B4 | QF BT `Function.onEasyconnect` (`Function.java:334`) | launcher «easy connect» key; picks by `ro.qf.carplay.type` (boot-time) | main | stale after a live switch | resolver |
| B5 | AiBridge `McuKeyEventHandler`: `startUpZjAndVoiceKey` (`:63-70`, `RunApp` + inject key 1500 after 200 ms), `handleStudyVoiceKey` (`:73-110`), `onZlinkVoice` (`:122`) | steering voice key → projection (1500 = `CARPLAY_KEYCODE_SPEECH_ON`) | AiBridge key thread + handler | broadcast dropped; `RunApp` fails; voice key dead in projection | resolver (repair/AiBridge); contract §6 entry `com.communityofdevs.projection.VOICE` |
| B6 | AiBridge `Constants.isCarplayConnected()` (`:55`) = `/data/data/com.zjinnova.zlink` exists **and** link props | «is a projection up» | any thread | the directory **stays** after disable → true while a stale prop says connected; and false for OHU (no such dir) | replace by the link props alone + resolver |
| B7 | Canbus `KeyDistributor` (`:164,359,493`) | MCU key `Zlink=79` → `startOutApp(zlink)`; receives `com.zjinnova.zlink` | main / receiver | nothing opens | resolver |
| B8 | Canbus `SourceDistributor` (`:70-75`), `TeslaDataController` (`:108,205`), `BtDistributor:147` | listen to the `com.zjinnova.zlink` broadcast; read `sys.qf.carplay.connect` | receiver, main | no event → wrong source indication | OHU emits the broadcast (section 6) |
| B9 | CarSettings `CarSettingService.java:688` | on AP-state 14 (failed) with top package Z-Link → turn Wi-Fi on (7862) | system receiver | inert unless Z-Link is on top | none |
| B10 | CarSettings `Tools.java:267` + `DEFAULT_VOICE_APPS` | voice-app chooser lists «ZLINK carplay/auto voice» | settings UI | entry stays visible → user may pick a dead voice target | hide the entry for the unselected app |
| B11 | CarSettings `FactorySettingsActivity.java:2453-2480` (`QFCarplayConfig`) | stock «phone link app» zlink / carletter + reboot | UI | flips a flag the CoD image no longer uses | hide on the CoD image |
| B12 | Launchers (BlueMan / Undercurrent / dreamMountain): `Function.onZLink`, `CustomAppWidget` default app 0 = zlink, `Utils.APP_ICON_WHITE_LIST`, `icons.config`, `default_workspace_qf.xml:112`, `BubbleTextView` label «ZLINK» | the launcher card / app grid entry | launcher main thread; layout read at first boot | tile of a disabled package is greyed or removed (framework behaviour ❓ per launcher); tap on a stale tile does nothing | tile target = resolver; default workspace names OHU (repair/launcher) |
| B13 | SystemUI `CustomAppsView` `btn_app_zlink` (`:64,115-118`) | status-bar shortcut | main | tap does nothing | resolver |

### C. State readers and writers around the link
| id | who/where | what | thread / lifecycle | failure | action |
|---|---|---|---|---|---|
| C1 | QF BT `BlueToothApp:180` registers, `BTReceiver` case 5 (`:241,290-372`) handles action `com.zjinnova.zlink` (`status` = `CONNECTED`/`DISCONNECT`, `phoneMode` = `carplay_wireless`, `hicar`, `auto_wireless`, `auto_wired`, mirror modes) | **writes** `sys.qf.carplay.connect`, `sys.carplay.bt.call`, `sys.qf.bt.aa.connect`; calls `disableA2dpIfNeed` on AA, `oneKeyDisconnectBt` on HiCar | main thread of QF BT | if the app dies without `DISCONNECT` the props stay `true` forever | reconciler asks QF BT to reset (action `com.qf.action.projection.reset`, new, repair/QFBT) before stopping the old app; OHU emits the broadcast when selected |
| C2 | QF BT `PhoneCallDialog:196-215,339` | on `PHONE_CALL_ON/OFF` hides/shows the in-call dialog | main | if OHU sends nothing the QF dialog opens on top of an AA call | OHU emits the broadcast |
| C3 | QF BT `BluetoothUtils:124`, `TechBTSettingManager` flags `zlinkCall`, `zlinkAutoConnectedStatus`, `zlinkUIShow` | call and UI state | main | stale flags | via C1 reset |
| C4 | framework `MediaFocusControl.java:974` | while `sys.carplay.bt.call` is true, **every** audio-focus request except from package `com.zjinnova.zlink` returns FAILED | system_server, binder | **OHU's own audio fails during a call** the moment OHU sets the call state | replace the string compare by the resolver (framework patch; or OHU never sets `phoneMode=PHONE_CALL_ON` — loses the call-in-projection routing) — **blocking for call parity** |

### D. Special treatment Z-Link gets in the firmware (parity, not switch-critical)
| id | who/where | what | OHU gets it? | action |
|---|---|---|---|---|
| D1 | framework `AudioRecord.java:547,581` | mutes the amp ~100 ms when Z-Link starts recording (no pop) | no | resolver; ask owner if the pop is audible with OHU |
| D2 | framework `Display.java:415,428`, `View.java:12113` | Z-Link (and a camera app) get a different display / density path | no | read what it changes ❓ R5; "no worse than Z-Link" |
| D3 | `PermissionManagerService.java:1027` | skips the permission review for Z-Link | n/a (system app, grants by privapp XML) | none |
| D4 | `WifiPermissionsUtil.java:43,48` | `com.zjinnova*` may override Wi-Fi config | OHU is uid system: allowed by the system uid ✅ 🧩 | none |
| D5 | `TaskSnapshotSurface.java:178` | no screenshot snapshot for Z-Link windows | no | resolver; avoids a stale frame on resume |
| D6 | `persist.zlink.land.navi_bar_size`, `persist.zlink.land.screen_type` (`ProjectCfgAutoUpgradeManager`) | per-project geometry for Z-Link | no | decide if OHU reads them (R5) |

### E. OHU's own hooks — all must be silent when OHU is not selected
| id | hook | starts on | silent how |
|---|---|---|---|
| E1 | `UsbAttachedActivity` (`USB_DEVICE_ATTACHED`, exported) | USB attach / hub / Ethernet (📻 it came up at boot) | L1 |
| E2 | `BootCompleteReceiver` (`BOOT_COMPLETED` …) → `AapService` | boot | L1 |
| E3 | `AutoStartReceiver` (`ACL_CONNECTED`), `WifiAutoStartReceiver` (`STATE_CHANGE`) | BT / Wi-Fi events | L1 |
| E4 | `AutomationReceiver`, `AutomationActivity`, media-button receivers (`CarKeyBroadcastReceiver`, `RemoteControlReceiver`, `MediaButtonReceiver`) | broadcasts, media keys (a live receiver competes for the media key) | L1 |
| E5 | `AapService` foreground service | E2 / E3 / UI | L1 |
| E6 | `NativeAaHandshakeManager`: AA RFCOMM server, stand-in HFP record | service start | L1 + self-check |
| E7 | `EarlyHotspot`, `WifiDirectManager`: soft-AP, P2P group | service start | L1 + L3 |
| E8 | `BridgeVoiceKeyWatcher` (logcat of `McuKeyEventHandler`) | `AapService` sync | L1; and it **duplicates** B5 once the router forwards: disable it when the router entry exists |
| E9 | `ZlinkService`, `ZlinkRedirect`, `ZlinkProxy`, `ProjectionSwitcher` (Volodymyr: CarPlay through Z-Link's daemon) | projection mode CarPlay | **off in `codos`** (D1) |
| E10 | `CarLauncherAlias` | — | already disabled, no HOME filter (this repo) |

## 5. The switch flow

Actors: CarSettings row (UI) → `ProjectionSwitch.apply(target)` in CarSettings' service (uid system; repair decides the
host: CarSettingService has the Wi-Fi/AP receivers already). `apply` is the **only** code that touches the derived state.

**Precondition check:** if a projection session is up (`sys.qf.carplay.connect` or `sys.qf.bt.aa.connect` true, or the
projection activity on top), the row asks the person «Disconnect the phone and switch?» — no silent kill of a call.

**Order (to `zlink` from `openheadunit`; the reverse is the mirror image):**
1. Read the target and the observed state. If equal: end (idempotent).
2. Tell QF BT the link is over (`projection.reset` → props false, A2DP restored, dialog off). Not a write by us to its props.
3. **Stop the old app:** OHU: `setApplicationEnabledSetting(DISABLED)` (kills it; its RFCOMM records go with the process).
4. Stop radios: `stopTethering(WIFI)`, remove any P2P group; wait for AP down (timeout 5 s; on timeout log and continue — step 7 re-checks).
5. Start the new app side: `ctl.start z-link` (R2 image makes this the only starter), wait for the `z-link` process.
6. `setApplicationEnabledSetting(ENABLED)` for Z-Link; start its `DaemonService` the way the vendor does after boot (`START_DAEMON_SERVICE`).
7. **Verify** (poll ≤ 10 s): OHU disabled, Z-Link enabled, `z-link` running, no AP, props false. Mismatch → one retry of 3–6, then raise «projection switch failed» and leave the **old** pair's records as they are (see failure path).
8. Show the person: «Z-Link ready — connect your phone» (the same two actions as today).

To `openheadunit`: reset → `ctl.stop z-link`, wait until gone → Z-Link disabled → radios down → OHU enabled →
verify (OHU enabled, `z-link` absent, no gadget functions ❓R3).

**The setting is written LAST** (after step 7 succeeded), except at boot where it is read, never written. So at any
crash point the setting says the *old* choice and the next `apply` reverts the half-done work to it — the person simply
sees that the switch did not happen.

**Failure path and the invariant «never both, never neither for long»:**
- Crash/power loss after 3 and before 6: neither app is up. Setting = old choice → boot reconcile starts the old one. Neither ≈ seconds.
- A step times out: the order guarantees the old app is already stopped before the new one starts, so both can never run.
- `ctl.stop` refused by sepolicy (R2 not done): the flow refuses to start (precheck `ctl` probe) rather than leave a
  running Z-Link daemon with OHU enabled — that combination is exactly today's failure.
- OHU or Z-Link package missing from the image: the row shows only the present one; apply refuses the absent target.
- Boot: `apply(read(setting))` after `boot_completed` and again on `PACKAGE_CHANGED` for either package (an OTA or
  a Play update re-enables a package; the reconciler undoes it).

## 6. What OHU must do when it is the selected app (codos build; Volodymyr's code, this session specifies)

1. **Self-check:** read `cod_projection_app` at `AapService` start and in `UsbAttachedActivity`; if not `openheadunit`,
   register nothing (no RFCOMM, no HFP record, no AP) and stop. Belt for L1.
2. **Z-Link-unit branches off.** `ZlinkScreenPolicy.getInstalledPackages()` is the single seam of 14 call sites
   (`App`, `AapService`, `EarlyHotspot`, `NativeAaHandshakeManager`, `BridgeVoiceKeyWatcher`, `MainActivity`,
   `SettingsFragment`…). In `codos` it returns an empty list: OHU behaves as on a plain unit. (A disabled package is still
   «installed» for `getPackageInfo`, so without this OHU would believe it is on a Z-Link unit.)
3. **Emit the link-state contract:** broadcast action `com.zjinnova.zlink`, extras `status` (`CONNECTED` / `DISCONNECT`) and
   `phoneMode` (`auto_wireless` / `auto_wired`; `PHONE_CALL_ON/OFF` only after C4 is closed). Sent on connect, disconnect,
   and from `onDestroy`. Cheapest correct: QF BT (C1, C2), Canbus (B8) already listen. Open ❓: are those receivers
   registered without a sender permission on A14 (R5). If a neutral action is wanted later, the resolver adds it; the
   legacy action stays as an alias.
4. **Entry points** (permission-protected, caller uid 1000 only): `com.qf.action.cp.call` (accept/reject) and the voice
   action from contract §6. Replace `BridgeVoiceKeyWatcher` (E8) when the router exists.
5. **Whitelists** (L6) and the two-button UI are separate work; nothing here depends on the UI.

## 7. The setting

- **Name / store:** `Settings.Global cod_projection_app` (proposed in contract §3, still ❓ agreed with repair). Global, so
  every app and `system_server` can read it without a permission; written only by uid system.
- **Owner / writer:** CarSettings row «Phone projection: Open Headunit / Z-Link» (repair builds it). No other writer, no property mirror.
- **Default:** `openheadunit`, **as the reader's fallback when the key is absent** — the first boot writes nothing, so no
  software writes the choice and a wiped Settings DB returns to the default.
- **Migration of a unit that used Z-Link** (the stock flag `/great/protect_dir/carplay_app` = `zj`): ignored by default.
  Reason: contract §0 (default OHU; many Z-Link licences are lost) and the A14 image is a fresh install. A unit whose person
  chose Z-Link under the new row keeps it forever (the key is present). **D2** asks the owner if a unit that comes from
  stock with `zj` should be migrated once to `zlink`; cost: one read of one file in the reconciler.
  Z-Link's licence files in `/great/protect_dir/.zjinnova/` are never touched.
- **OTA / package update:** `PACKAGE_CHANGED` retriggers `apply` (section 5).

## 8. Regressions and proofs (stand 198, after the gate; none run now)

| id | proof | pass |
|---|---|---|
| P1 | Z-Link selected: wireless AA, wired AA, wireless CarPlay, wired CarPlay each connect and show video | same as today |
| P2 | OHU selected: wireless AA, wired AA connect (CarPlay: D1) | video, touch, sound |
| P3 | switch OHU→Z-Link and back, **no reboot**, three times each way | each end state verified by step 7's checks |
| P4 | after OHU→… selected: `ps -A` has no `z-link`, `z-mdnsd`, `zbt-main`; with Z-Link: no OHU process | none |
| P5 | after any switch: soft-AP down, wlan0 has a client IPv4 again, **no reboot** | owner's 23.11 finding closed |
| P6 | OHU selected, then Z-Link selected: `dumpsys bluetooth_manager` / btsnoop show no AA UUID and no «Hands-Free Unit» on the SoC radio; the module's records per R1 | none from the unselected app |
| P7 | `setprop ctl.start/stop z-link` as the CarSettings uid: allowed; `ctl` from an ordinary app: denied | sepolicy as intended |
| P8 | the phone's pairing with the unit unchanged through a switch (no re-pair) — the 23.11 failure never recurs | wireless AA of Z-Link connects at once after OHU→Z-Link |
| P9 | USB: with OHU selected a wired AA phone attaches as host; with Z-Link selected a CarPlay phone sees the gadget; configfs state recorded both ways | R3 answered |
| P10 | HFP call through QF BT, with and without a projection session, in both modes: audio, hang-up, MCU call keys | unchanged; with OHU: accept/reject keys reach the app (B2) |
| P11 | steering voice key: Z-Link → Siri/AA voice; OHU → AA voice via the router; no double forwarding (E8 off) | one request per press |
| P12 | boot-time: cold boot and ACC cycle in each selection: right app up within the same time as today; Z-Link daemon not running with OHU selected | no `z-link` at boot |
| P13 | crash test: kill `system_server`-side switch host between step 3 and 6 (adb `kill`): next boot restores the setting's choice | never both |
| P14 | audio-focus: OHU call audio with `sys.carplay.bt.call` true (after C4) | plays |

## 9. Open items

**Owner decisions**
- **D1 CarPlay.** OHU as built by Volodymyr does CarPlay only by riding on Z-Link's daemon (the MFi chip and iAP are
  Z-Link's). Exclusive switch = no Z-Link daemon in OHU mode = **no CarPlay in OHU mode**. Recommended: OHU mode is
  Android Auto only; an iPhone user selects Z-Link (the switch exists for exactly that). The alternative — OHU on Z-Link's
  daemon — keeps both running and contradicts «не можуть працювати разом».
- **D2** migration of stock-`zj` units (section 7). Recommended: no migration.

**Research for the helper (decompile / read; English both ways)** — R1 is the critical one.
- **R1** where Z-Link's Android Auto Bluetooth record lives: `libzbt_core` (`com.zjinnova.jni.Zbt`), `libzbt-main*.so`,
  `/dev/zj_bt_serial`, the `sd`/`gocsdk_zj` module: does the app/daemon register `4de17a00-…` at start and unregister at
  stop, or is it static in the module? Also what `zbt error message id(0x109)` / `(0x115)` mean.
- **R2** the exact `z-link` service stanza on A14 (`disabled`, `class`, `seclabel`), what `zlink5.sh` does on re-run, whether
  `ctl.stop` takes all children, and whether the engine re-raises anything on its own.
- **R3** USB gadget: configfs state with Z-Link up and down, UDC bound or not, who flips host/device on K706 (hub, MUSB),
  whether a live switch needs an explicit gadget teardown.
- **R4** how the soft-AP is raised (`startTetheredHotspot` caller and uid) and whether `stopTethering` from uid system is
  enough; the same for OHU's `EarlyHotspot` and P2P group.
- **R5** pass 2 over the real A14 image: `grep -r` for `zjinnova|zlink|carplay|carletter` across all APKs (apktool/jadx),
  `/system/etc/init`, sepolicy and `property_contexts`, `/system/etc/*.ini|xml`, Magisk modules (`NotKillAppsBeforeSleep.ini`,
  autolaunch `appPowerSaveConfig.xml`), plus: the `Display`/`View` special case (D2), BTReceiver registration permission, `RunApp`
  failure handling, the A14 `AssistRouter`, which launcher the CoD image uses.

**For repair** (named as the brief asks): the CarSettings row and the reconciler host; `ctl.start/stop z-link` sepolicy;
QF BT `projection.reset` and the resolver in `McuKeyEventManager`/`Function`; AiBridge `Constants.isCarplayConnected` and
`onZlinkVoice`; Canbus `KeyDistributor`; launcher tile and default workspace; SystemUI `CustomAppsView`; framework
`MediaFocusControl` (C4), `AudioRecord`, `TaskSnapshotSurface`; the whitelists (L6).
**For ROM builder:** init `service z-link … disabled`; OHU in the image with the privapp XML; the default of the setting
is code, not data.
**For OHU / Volodymyr:** section 6 (self-check, `getInstalledPackages()` empty in codos, the broadcast, entries, E8).

## 10. What could make this draft wrong

1. R1 negative (the AA record is static in the module): then L4 needs a module action and the switch costs a BT module restart.
2. `ctl.stop` cannot be allowed to CarSettings' domain: the reconciler moves into a system service that already holds the permission.
3. A14 ports differ from the A10 decompiles: the register (section 4) is expected, not proven, until R5.
4. The reconciler host: CarSettings can be killed; placing `apply()` in the framework (system_server) is stronger and more invasive. Repair decides.
