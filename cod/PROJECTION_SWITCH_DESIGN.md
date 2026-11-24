# Projection switch: Z-Link ⇄ Open Headunit — design draft 1

> **Note (24.11, head lead): the voice-key resolver of §4 B5 lives in AssistRouter, the one assistant function (its projection branch picks Z-Link or OHU; AiBridge `onZlinkVoice` defers to it). Owner: repair.**
>
> **Note (24.11, contract §10): OHU does NOT emit Z-Link's `com.zjinnova.zlink` broadcast (§6.3, §11.3 row 16 withdrawn): it triggers QF BT's stock A2DP disconnect and a toast. OHU sends **exactly Z-Link's own broadcast** (`com.zjinnova.zlink`, `status`, `phoneMode`) and writes no property: QF BT's receiver does the rest, as for Z-Link (owner's ruling of 24.11, contract §10; the earlier drafts, `sys.qf.ohu.*` and a direct write of the flag, are withdrawn). §6.3 stands again, with the A2DP drop right for OHU.**
>
> **Note (24.11, §15): the earlier instruction «`getInstalledPackages()` empty in codos» (§6.2 revision 1, §13.2) is WITHDRAWN — it would switch off the QF-unit features of Volodymyr's code. See §15.1.**
>
> **Revision 4 (24.11.2026): D3 closed — the firmware's Z-Link daemon is ours, the Z-Link app is not used. Target model C
> (section 14): OHU active, firmware daemon alive, Z-Link app disabled, OHU serves the daemon's loopback ports itself —
> no iptables, no root. Model A (section 13) stays the safe default until the open items of section 14 are proven on a unit.**
>
> **Revision 3 (24.11.2026): D1 = Option A is the shipping model (owner: disable Z-Link entirely, enable it back; no root
> anywhere). Section 13 is the current design; sections 11 and 12 (Z-Link «headless», Option B) are kept as the record
> of a future option and are NOT part of the first image. Where section 13 and an earlier section differ, section 13 wins.**
>
> **Revision 2 (24.11.2026, owner: «Деякі потрібні хору.»):** some of Z-Link's services are needed BY Open Headunit, so
> the whole-package disable of Z-Link is **dropped**. Section 11 supersedes section 1 items 3 and 6, section 3 L1/L2
> for the OHU-selected state, section 5, section 6 item 2 and D1 wherever they conflict. Read section 11 first.

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
3. *(superseded by §11 for the OHU-selected state; still true for Z-Link selected → OHU silent)* **Disabling the app is not enough.** Z-Link has an init daemon (`z-link`, user shell) that lives in
   `/data/data/com.zjinnova.zlink/lib` and survives `pm disable`; it makes the USB gadget, raises the soft-AP, runs
   `zbt-main`. The reconciler therefore stops/starts the daemon too, and the image stops starting it by itself (R2).
4. **One resolver** answers «which projection app is it?» for everyone who today hard-codes `com.zjinnova.zlink`
   (about 25 call sites, section 4). The projection app's own entry points are the same two things both ways: a launch
   component and a state broadcast.
5. **OHU emits Z-Link's state contract** (the `com.zjinnova.zlink` broadcast with `status` / `phoneMode`), so QF Bluetooth,
   Canbus and the voice router keep working unchanged. This is the cheapest correct way and needs no patch in four
   system apps. Details in section 6.
6. *(superseded by §11)* Owner: OHU **keeps** Z-Link's daemon and the app's background services for CarPlay (OHU taps
   their feed). So OHU-selected = OHU active + **Z-Link headless** (daemon and services alive, UI hidden, Android Auto
   silenced inside Z-Link); Z-Link-selected = Z-Link full + OHU silent (disabled).
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
2. **Z-Link-unit branches stay ON** (revised, §11): `ZlinkScreenPolicy.getInstalledPackages()` keeps returning Z-Link;
   in OHU mode Z-Link is present and headless, and OHU's CarPlay feed, hotspot policy and key forwarding depend on it.
   The earlier idea of returning an empty list is withdrawn.
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
- **D1 CarPlay — decided by the owner (24.11): «Деякі потрібні хору.»** CarPlay in OHU mode stays, carried by Z-Link's
  daemon and services; see §11. Consequence to acknowledge (D3): CarPlay in OHU mode still needs a valid **Z-Link
  licence**; a person who lost it gets Android Auto only.
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

## 11. Revision 2 — Z-Link components needed by Open Headunit (owner: «Деякі потрібні хору.»)

### 11.1 What OHU actually uses of Z-Link (🔬 both sides read)

1. **The CarPlay feed.** OHU is a transparent TCP proxy between two Z-Link halves: the **daemon** is the client, the
   **Z-Link app** is the server on `127.0.0.1:1666 / 1777 / 1888 / 1999`. OHU listens on `+10000` (11666…11999), accepts
   the daemon's connection, dials the app's port, and copies both ways while decoding video, audio, control and injecting
   touch/keys/keyframe requests (`ZlinkProxy.kt:72-100` listen/pair; `ZlinkWire.kt:12-30` frame format, channels
   video/audio/control, `TYPE_TOUCH 0x111`, `TYPE_KEY 0x113`, `TYPE_FORCE_KEYFRAME 0xd0b`; `ZlinkHub.kt` distribution and
   replay; `ZlinkVideoSink.kt`, `ZlinkSurfaceBridge.kt`, `ZlinkAudioSink.kt` render and play). Both halves must be alive:
   no daemon → no phone; no app → the proxy's upstream dial fails (`ZlinkProxy.kt:94-100`) and the daemon retries.
2. **The redirect.** The daemon connects to the original ports, so OHU needs a netfilter REDIRECT of the daemon's
   loopback traffic to its own ports (`ZlinkRedirect.kt:12-32` rules, `:39-60` command builders). It needs uid 0 /
   `CAP_NET_ADMIN`; the CoD build currently **disables** it (`ZlinkRedirect.kt:57-63`, my codos change), which means the
   CarPlay feed gets no traffic on CoD OS today. Answer in §11.4.
3. **The app's DaemonService.** OHU starts Z-Link's own background service after it changed the app's screens
   (`ZlinkService.kt:51-56`, `:111`, command `am start-foreground-service -n com.zjinnova.zlink/
   com.zjinnova.android.zlink.features.service.DaemonService` at `:169-170`). 📻 Changing the app's components stops its
   process and the daemon restarts in a loop until the app is back (comment `ZlinkService.kt:52-55`).
4. **Hiding Z-Link's UI.** OHU disables Z-Link's activities one by one (`ZlinkScreenPolicy.kt:24-25` target packages,
   `:29-40` the 12 activities, `:64` `isUiHidden`, `:86-110` `pm disable-user` / `pm default-state`), driven by OHU's
   `hideZlinkUi` setting (`ZlinkService.kt:51-56`). It does **not** touch the package, the services or the daemon.
5. **The unit's access point.** OHU reuses the AP the unit already has: `EarlyHotspot.kt:33-50` (asks for the hotspot at
   process start on a Z-Link unit), `HotspotConfigClient.kt` / `tools/HotspotConfigTool.kt` (name, password, 5 GHz),
   `HostapdConfig.kt` + `HotspotConfigReader.kt` (read the hostapd file Z-Link's AP uses), `ProjectionSwitcher.kt:46-62`
   («CarPlay leaves zLink's hotspot running… `svc wifi enable` makes the hotspot give way»).
6. **Z-Link's properties, read only.** `persist.zj.BTmac`, `persist.zlink.carplay.mac` (`BluetoothHelper.kt:493-494`);
   `rw.zlink.bt.type`, `rw.zj.bt.type`, `persist.zj.BTmac` (`ExternalBtPolicy.kt:29-32`).
7. **A Bluetooth module carrier that is NOT Z-Link's:** OHU's wireless Android Auto on this unit goes to the external
   Bluetooth module through the vendor daemon **`gocsdk_zj`, 127.0.0.1:3152** (`zbt/ZbtByteChannel.kt:92` `PORT = 3152`;
   `ZbtAaCarrier.kt`, `ZbtProbe.kt`, `ZbtDaemonReachability.kt`; also `/dev/socket/zj_bt_socket`,
   `zjsocket/ZjSocketConnector.kt:23`). 🔬 `ZbtByteChannel.kt` header: *the daemon owns the AA RFCOMM server on the
   module itself* and `RequestInit enable_type = 2` is the whole setup; `ModulePhoneWatcher.kt:14-20`: *the module hands
   the phone's data to the newest client*. So **`gocsdk_zj` is QF's shared Bluetooth infrastructure** (QF BT starts and
   stops it, `BluetoothModeManager`), and the module's AA server exists once a client asks for it. **R1 is partly
   answered:** the AA record on the module is not Z-Link's own; it is a service of `gocsdk_zj` that **any** client (Z-Link's
   BT service via `libzbt_core`, or OHU) turns on. The conflict is two clients of one server, newest wins.
8. **Keys to CarPlay:** `BridgeVoiceKeyWatcher.kt` forwards unit keys to CarPlay as key 1500 (`ZlinkHub.injectKey`).
9. **OEM-app policy:** `OemAppManager.kt:54-55` lists Z-Link as a «conflicting OEM app»; `runAutoKillIfEnabled`
   (`OemAppManager.kt` ~`:288-296`, Volodymyr) refuses to disable anything while Z-Link is installed.

### 11.2 Z-Link has two states, OHU has two states

| selection | OHU | Z-Link |
|---|---|---|
| `openheadunit` | **ACTIVE** (package enabled) | **HEADLESS**: package enabled, daemon `z-link` running, DaemonService and BT/phone-state services alive; UI components disabled; Android Auto switched off inside Z-Link; launcher entry hidden |
| `zlink` | **SILENT** (package disabled — everything in OHU registers nothing) | **FULL**: all components enabled, all features on, UI visible |

The asymmetry is the point: Z-Link is never «fully off» in OHU mode, OHU is fully off in Z-Link mode.

### 11.3 Component map (a = needed by OHU and kept alive · b = conflicting, silenced · c = UI, hidden · d = shared, not ours)

Where «OHU calls» is empty, OHU does not call it. Evidence marks follow the legend at the top.

| # | Z-Link component | class | OHU calls (file:line) | who starts it | behaviour in HEADLESS, and how it is held there |
|---|---|---|---|---|---|
| 1 | init service `z-link` → `zlink5.sh` (watchdog, rmem, USB gadget `iap.gs0`+`ncm.gs1`) 🔬 | a | none (OHU depends on its effect) | init at boot today | runs. **Must start AFTER the feature flag is set** (§11.5) → image: `disabled`, reconciler `ctl.start` (kept from §3 L2, now for both states) |
| 2 | engine `z-link -c qianfeng` (`libzjL10001.so`): CarPlay, USB iAP/usbmuxd, MFi chip, `hostapd_cli`, `iptables` self-rules 🔬 | a (CarPlay) / **b (Android Auto, CarLife, HiCar, Mirror, DLNA)** | feed: `ZlinkProxy.kt:72-100`; keys `ZlinkHub.kt` | `zlink5.sh` | runs for CarPlay. Everything else is silenced by Z-Link's own native knob, the property **`rw.zlink.disable.features`** (letters; CarSettings writes `hl` / `hil` for the HiCar checkbox, `Function.java:632`, `FactorySettingsActivity.java:762`). Which letter disables Android Auto, and whether it also stops its USB claim, BT service use and AP raising: **R6** ❓ |
| 3 | `z-mdnsd` (CarPlay/AirPlay discovery) 🔬 | a | none | the engine | runs with the engine |
| 4 | engine helpers `z-usbmuxd`, `z-dhcpc`, `libzbt-main` (`/data/local/tmp`), `z-netshare` 🔬 | a for wired/wireless CarPlay; role of `libzbt-main` ❓ | none | the engine | children of the engine; stopped with it |
| 5 | **OHU's redirect rule** (not Z-Link's, but the glue) | a | `ZlinkRedirect.kt:12-60`, `ZlinkService.kt:46-47` | OHU on service start | needs root. Answer §11.4 |
| 6 | app `StartupBroadcastReceiver` (`LOCKED_BOOT_COMPLETED`, `START_DAEMON_SERVICE`, `START_ZLINK_SERVICE`) 🔬 | a | none | system at boot | must stay enabled: it is what brings the app side up |
| 7 | app `DaemonService` (exported; serves 1666/1777/1888/1999 upstream ❓ which half listens: R10) | a | `ZlinkService.kt:55,111,169-170`; upstream dial `ZlinkProxy.kt:94` | receiver, OHU `am start-foreground-service` | must be running before CarPlay offers |
| 8 | app BT services `ZBTService`, `BluetoothService`, `BluetoothHCTService`, JNI `libzbt_core` (`com.zjinnova.jni.Zbt`) 🔬 | a for CarPlay wake / **b for Android Auto** (a second client of `gocsdk_zj`) | OHU's competing client: `zbt/ZbtByteChannel.kt:92`, `ModulePhoneWatcher.kt` | app process | **R8 ❓**: while OHU carries AA the Z-Link BT client must not `RequestInit` for AA (the AA letter in #2). In CarPlay mode OHU never holds the module (`ModulePhoneWatcher.kt:14-20`) |
| 9 | app activities: `MainActivity`, `CarPlayActivity`, `CarPlayAutoActivity`, `AutoActivity`, `HiCarActivity`, `CarLifeActivity`, `MirrorActivity`, `DlnaActivity`, `*Unavailable*`; `SecondaryScreenService` 🔬 | **c** | `ZlinkScreenPolicy.kt:29-40,64,86-110` | the engine (`am start …ZLINK_MAIN`), launchers | component-disabled by the **reconciler** (it replaces `hideZlinkUi`). Disabling stops the app process → reconciler restarts `DaemonService` right after (📻 comment `ZlinkService.kt:52-55`) |
| 10 | app `MediaButtonReceiver` (media keys) 🔬 | **b** (competes with OHU's `CarKeyBroadcastReceiver`, `RemoteControlReceiver`, `MediaButtonReceiver`) | none | system on media key | component-disabled in HEADLESS; whether CarPlay needs it: proof P21 |
| 11 | app `PhoneStateReceiver` 🔬 | a ❓ | none | system | keep (CarPlay call state) |
| 12 | app activation / licence (`ActivationActivity`, `CheckPermissionsAndActivationActivity`, `/great/protect_dir/.zjinnova`) 🔬 | a | none (❓ whether the daemon itself refuses without activation is **not established**, see §14.1) | the app | **never touched**; D3 closed in §14 |
| 13 | app audio engine / AEC (`libzlink` `AudioEngine`, `libzjaudio_jni` `Zaec`, `libwebrtc_apm_plus`) 🔬 | a ❓ | OHU plays and records itself: `ZlinkAudioSink.kt`; framework `AudioRecord` special-cases Z-Link (`:547`) | the app | **R9 ❓ who holds the microphone during a CarPlay session** (Siri/call): relevant to the owner's NS requirement |
| 14 | the unit's soft-AP raised via the framework / `hostapd_cli` 🔬 📻 | d (shared) | `EarlyHotspot.kt:33-50`, `HotspotConfig*`, `ProjectionSwitcher.kt:46-62` | engine on a CarPlay/AA offer; OHU on its own start | one AP, two requesters. Rule: whoever's mode is active raises it; the reconciler stops it only on a switch (L3). OHU's mode switch (`ProjectionSwitcher`) already sequences CarPlay ⇄ AA inside OHU |
| 15 | `gocsdk_zj` (module UART, `127.0.0.1:3152`, HFP/A2DP for QF BT) 🔬 | **d (not Z-Link's)** | `ZbtByteChannel.kt:92` and 4 more files | init at boot, QF BT | **never touched by the switch** (calls depend on it). Exclusivity of its AA client = #8 |
| 16 | broadcast `com.zjinnova.zlink` (state) | a | none | Z-Link app on CONNECT/DISCONNECT | in HEADLESS Z-Link keeps sending it for **CarPlay** sessions, so QF BT/Canbus keep working for CarPlay with no change. **OHU emits it only for its own Android Auto sessions** (§6.3 stays) |
| 17 | `com.qf.action.cp.call` / key 1500 target (`setPackage(zlink)`) | a (CarPlay) | none | QF BT, AiBridge | still correct for a CarPlay session; for an AA session it must reach OHU → **the resolver answers by who owns the live session**, not only by the setting: CarPlay → Z-Link package, Android Auto → OHU package, FULL → Z-Link. `sys.qf.carplay.connect` vs `sys.qf.bt.aa.connect` say which |

### 11.4 The redirect without root

`iptables` needs `CAP_NET_ADMIN`; an app under uid 1000 does not keep it. Proposal: **two root init oneshot services with
fixed arguments**, `ohu-redir-on` and `ohu-redir-off` (same rules as `ZlinkRedirect.ruleAdd/ruleDelete`, four ports),
started by the CoD build with `SystemProperties.set("ctl.start", …)` — the same mechanism QF BT already uses for
`gocsdk_zj` — and sepolicy that lets the system app start exactly those two. The reconciler owns on/off (the rules exist
only while OHU is selected and the daemon runs). OHU's `ZlinkRedirect.ensure/remove` in codos call the property instead of
returning `false`. Two facts to check first: **R7** — the daemon's real uid on A14: canon 52 says user `shell`, OHU's rules
match `--uid-owner 0`; if the daemon is `shell` (2000) the rule never matched there and must say 2000.
Fallback if init services are refused: OHU binds 1666…1999 itself and stands in for the app (needs the app's side of the
protocol: large, not recommended).

### 11.5 Revised state model and flow

- **Feature flag.** `rw.zlink.disable.features` gets **one writer**: the reconciler, computing it from two inputs —
  CarSettings' HiCar checkbox (today it writes the property directly, `Function.java:632`: that write moves into the
  reconciler's input) and the projection selection (`openheadunit` ⇒ add the Android Auto letter). It is an `rw.` property
  (not persisted) and the engine reads it at start, so it must be set **before** `ctl.start z-link` on every boot and every
  switch — which is exactly why the init service becomes `disabled` (#1).
- **Boot (`apply(read setting)` after `boot_completed`):** set the flag → component-disable Z-Link UI (HEADLESS) or enable all
  (FULL) → redirect on (HEADLESS) / off (FULL) → `ctl.start z-link` → `START_DAEMON_SERVICE` / start `DaemonService` → OHU
  enabled (HEADLESS case) / disabled (FULL case) → verify. 🧩 Z-Link starts a few seconds later than today; its own gate
  already delays it past `boot_completed` (`ActivityManagerShellCommand.java:1052`).
- **To `openheadunit` (from Z-Link FULL):** (precheck: no live session or the person agreed) → QF BT `projection.reset` →
  `ctl.stop z-link` → AP/P2P down → set flag with the AA letter → disable Z-Link UI components (process dies) → redirect on
  → `ctl.start z-link` → start `DaemonService` → **enable OHU last** → verify → write the setting.
- **To `zlink` (from HEADLESS):** reset → **disable OHU first** (it dies; its records go) → AP/P2P down → `ctl.stop z-link` →
  redirect off → set flag without the AA letter → enable all Z-Link components → `ctl.start z-link` → start `DaemonService` →
  verify → write the setting.
- **Never both:** Android Auto can only exist in one place at any instant because OHU is enabled **after** Z-Link's AA is
  off and disabled **before** Z-Link's AA is on. A crash leaves «neither AA» (safe), never «both».
- **OHU self-check (belt):** OHU starts its AA entry points (`NativeAaHandshakeManager`, `ZbtAaCarrier`, `UsbAttachedActivity`
  work) only if the setting is `openheadunit` **and** Z-Link's launcher component is observed disabled. Otherwise it logs
  once and does nothing. Observed state, not a copy of the choice.
- **Single owners (no island logic):** Z-Link UI hidden/visible — the reconciler (in `codos` OHU's `ZlinkScreenPolicy`
  becomes read-only and `hideZlinkUi` stops writing); the redirect — the reconciler; the feature flag — the reconciler;
  Z-Link package/components — the reconciler.

### 11.6 What each class means in practice, in plain words

- **Needed (a):** the daemon, its helpers, the app's `DaemonService`, the startup receiver, the BT/phone-state services for
  CarPlay, the activation check, the AP. They keep running while OHU is selected.
- **Silenced (b):** Z-Link's Android Auto (everywhere: BT, USB claim, AP raising), CarLife/HiCar/Mirror/DLNA, its media-button
  receiver, and the competing second client of the Bluetooth module for Android Auto.
- **Hidden (c):** every Z-Link screen and its launcher entry.
- **Not Z-Link's (d):** `gocsdk_zj`, the access point, the phone's pairing.

### 11.7 New research and proofs

- **R6** decode `rw.zlink.disable.features` / `persist.sys.zlink.disable.features`: letters, who parses them (engine strings,
  app), which letter is Android Auto, and whether it also stops the USB claim, the BT AA request and AP raising; is it read
  only at start (assumed) or live.
- **R7** the daemon's uid/capabilities on A14 (`ps -A -o USER,PID,NAME,LABEL`), and a working `iptables` rule from an init oneshot.
- **R8** how Z-Link's BT services behave with AA off: do they still `RequestInit enable_type=2`? What does the module do with two clients.
- **R9** mic and AEC ownership during a CarPlay session (Z-Link's `Zaec` vs OHU).
- **R10** which half listens on 1666…1999 (daemon or `DaemonService`), and what `FoxClientConnect` / `LocalSocketClientConnect` carry.
- **P15** wireless and wired CarPlay through OHU with Z-Link HEADLESS: video, touch, keys, audio, Siri.
- **P16** Android Auto in OHU mode: `logcat` tags `btopt`, `sd`, `zbt_main` show **no** Z-Link Android Auto activity; the phone sees one AA service.
- **P17** `getprop rw.zlink.disable.features` read at daemon start has the AA letter in OHU mode and not in Z-Link mode.
- **P18** redirect rules appear and disappear with the selection; none left after a switch.
- **P19** HEADLESS leaves no Z-Link window: no activity of `com.zjinnova.zlink` resumed at any point (dumpsys activity).
- **P20** Z-Link licence file untouched across switches (hash before/after).
- **P21** media keys with Z-Link's receiver disabled: CarPlay media control still works through OHU's path.

### 11.8 Owner decisions added

- **D3** CarPlay in OHU mode needs a valid Z-Link licence (we never touch it). A person who lost it gets Android Auto only —
  acceptable? (Recommended: yes; OHU's point is Android Auto without a licence.)
- **D4** the HiCar checkbox in the factory menu stays but its write moves into the reconciler (single writer of the flag).

## 12. The choice put to the owner (D1), both options designed

Head lead, 24.11: put to the owner not only «OHU = Android Auto only», but also «Z-Link's daemon stays alive for OHU's
CarPlay feed while Z-Link's AA record / AP / UI are silenced». Owner's own words so far: «Деякі потрібні хору.»

| | **Option A — OHU = Android Auto only** | **Option B — Z-Link headless for OHU's CarPlay** (§11) |
|---|---|---|
| what runs in OHU mode | OHU only. Z-Link package disabled **and** `z-link` daemon stopped | OHU + Z-Link daemon + app services; Z-Link UI hidden; Android Auto off inside Z-Link |
| CarPlay in OHU mode | none (an iPhone user selects Z-Link) | yes, through OHU's screen and feed |
| needs Z-Link licence in OHU mode | no | **yes** (D3) |
| conflict surface | none by construction: nothing of Z-Link is alive | Android Auto (module client, USB claim, AP), media buttons, AP ownership, mic ownership — each must be held silent |
| extra work | none beyond the reconciler | init oneshots for the redirect (root), feature flag as single-writer, UI component control, R6–R10 |
| what the owner experiences | switch is the only way to CarPlay | CarPlay and Android Auto both inside one app, no switch needed for them |
| risk | low | medium: depends on facts not yet known |

**What decides B** (and what R1 changed):
- **R1 (partly answered, §11.1 item 7):** Z-Link's Android Auto record on the module is not Z-Link's own; it is `gocsdk_zj`'s
  server, turned on by any client's `RequestInit enable_type=2`. So *silencing Z-Link's Android Auto means stopping Z-Link's
  own BT client from asking for it*, not removing a record. That is possible only if Z-Link has an off switch for it:
- **R6 (open, decisive):** does `rw.zlink.disable.features` have a letter that disables Android Auto *including* the BT request,
  the USB claim and the AP raising for it? Outcomes:
  1. **Yes** → Option B works as designed in §11.
  2. **Letter exists but only hides the UI / refuses the session, the BT request and AP raising stay** → B is possible only with
     the module-client rule R8 (Z-Link's BT service kept from `RequestInit` some other way) or with OHU as the sole client
     of `gocsdk_zj` for Android Auto while CarPlay uses it only briefly; needs a test on a unit to be believed.
  3. **No such letter** → B has no clean silencer. Fall back to **A**, or to **B′**: keep the daemon for CarPlay only when
     the phone is an iPhone (OHU watches the module phone kind — `ModulePhoneWatcher`/`PhoneKindPolicy` already know it) and
     stop the daemon otherwise. B′ is more state, more failure paths, and is proposed only if the owner insists on both.
- **R7/R10 (feed prerequisites for B):** the redirect rule must really match the daemon's uid and the proxy must be the
  server's peer; without them even a perfect silencer gives no CarPlay in OHU.

**Recommendation to put to the owner:** build the switch and the reconciler **now for Option A's end states plus the Z-Link
HEADLESS state as a switch-on flag** — i.e. the reconciler already has both states from §11; the first release ships with
HEADLESS **off** (Z-Link daemon stopped in OHU mode = Option A), and Option B is enabled by one flag when R6/R7/R10 are
proven on a unit. This costs nothing extra (the code path exists in the design), makes the first release safe, and keeps
the owner's «Деякі потрібні хору» as the next step instead of a blocker. If the owner wants B on day one he accepts that
the first image may have to drop to A after the stand test.

**What the owner is asked** (morning): D1 — A first with B behind a flag (recommended), or B first? D2 — migration of stock-`zj`
units. D3 — if B: a lost Z-Link licence means no CarPlay in OHU mode, acceptable? D4 — the factory HiCar checkbox write moves
to the reconciler.

## 13. Revision 3 — decision: Option A is the shipping model; no root anywhere

Owner (24.11), via the head lead: «Саму програму, якщо вона не потрібна, щоб підтримувати CarLink або Android Auto,
вимикаєш. Можна просто disable. Enable. … Ми ж керуємо своєю прошивкою, правильно? … Просто одне з другим точно не
уживається.» **D1 = A.** Selecting Open Headunit disables Z-Link **entirely** (its app and its init daemon); selecting Z-Link
silences Open Headunit. Option B (§11, §12) stays a documented future option only; nothing in the first image depends on it.

### 13.1 The model that ships

| selection | OHU | Z-Link app `com.zjinnova.zlink` | Z-Link init daemon `z-link` |
|---|---|---|---|
| `openheadunit` (default) | enabled, active | **disabled** (`COMPONENT_ENABLED_STATE_DISABLED`) | **stopped** (`ctl.stop`) |
| `zlink` | **disabled** | enabled | **started** (`ctl.start`) |

Both are plain package states plus one init service; every mechanism is the firmware's own, run by uid system:
`PackageManager.setApplicationEnabledSetting` (needs only `CHANGE_COMPONENT_ENABLED_STATE`, which the system uid holds) and the
`ctl.start` / `ctl.stop` properties (QF Bluetooth already does `ctl.stop gocsdk_zj`, `BluetoothModeManager`). In the image the
service stanza becomes `service z-link … disabled`, so it never starts by itself; the reconciler starts it only in the
`zlink` state. Z-Link's data, libraries and **licence files are never touched**: disabling an app keeps its data, and
enabling it returns exactly the previous state.

Order (the invariant «never both»):
- **to `openheadunit`:** QF BT `projection.reset` → `ctl.stop z-link` → disable Z-Link → soft-AP and P2P down → **enable OHU last** → verify → write the setting.
- **to `zlink`:** `projection.reset` → **disable OHU first** → AP/P2P down → enable Z-Link → `ctl.start z-link` → start its
  `DaemonService` the vendor way → verify → write the setting.
- **boot:** the same sequence for whatever the setting says (absent = `openheadunit`), after `boot_completed`.
- **OHU self-check (belt):** OHU starts its Android Auto entry points only if the setting is `openheadunit` **and** the Z-Link package
  is observed disabled. Observed state, not a copy of the choice.
- Failure: a crash between «stop old» and «start new» leaves neither up; the next apply fixes it; both is impossible by the order.

What this deletes from the earlier design: the Z-Link «headless» state, the feature-flag single writer (D4), the media-button
and BT-client silencing, the redirect init services, R6–R10 as blockers, D3 (no Z-Link licence is involved in OHU mode).
The switch is now the §1–§8 design with D1 settled. §11/§12 are kept as the record of Option B.

### 13.2 What OHU's code does under A (for Volodymyr, `codos` only)

CarPlay-through-Z-Link does not exist in OHU mode, so its machinery must not run and must not be offered:
- `ProjectionModePolicy`/`ProjectionSwitcher`: in `codos` the projection mode is fixed to Android Auto; the «CarPlay» tile and
  settings row are not shown.
- `ZlinkScreenPolicy.getInstalledPackages()` returns an empty list in `codos` (Z-Link is installed but disabled; OHU must not
  believe it is on a Z-Link unit). This restores the §6.2 text that revision 2 had withdrawn.
- `ZlinkService`, `ZlinkProxy`, `ZlinkRedirect`, `ZlinkHub` and the `hideZlinkUi` setting are never started in `codos` (they
  already do nothing without the redirect; the guard makes it explicit and removes the log noise).
- Everything else of §6 stays: self-check, the link-state broadcast `com.zjinnova.zlink` for OHU's own Android Auto sessions,
  the voice and call entries, whitelists.

### 13.3 Why OHU's CarPlay proxy wanted root, and how it goes without

The owner asked: «чому саме потрібен root? Невже до тих самих … демонів і служб не можна достукатися без рута?»

**Plain answer.** Almost everything of Z-Link *can* be reached without root, and OHU already does: it connects to the
Z-Link app's local ports, starts its `DaemonService`, hides its screens, reads its properties — all with ordinary or system
permissions. Root was needed for exactly **one** trick: the **redirect**.

How the CarPlay feed is built: Z-Link's daemon is a *client* that connects to the Z-Link *app* on `127.0.0.1:1666/1777/1888/1999`
(the app is the server, `ZlinkProxy.kt:72-100`). Volodymyr wanted OHU to sit **between** them and still let the real app answer.
Because the daemon dials the original ports, the only way to put OHU in the middle is to **divert the daemon's connections**
to OHU's own ports — a netfilter `iptables -t nat … REDIRECT` rule, which the kernel allows only to uid 0
(`CAP_NET_ADMIN`; `ZlinkRedirect.kt:12-32`). Nothing else in OHU's Z-Link support needed root except the same kind of shell
conveniences that now run as uid system.

**Three ways to avoid root, and what is honest about each:**
1. **An init oneshot in the image** that installs the same rule. It is part of the firmware (init runs it as root, with
   fixed arguments, the way init runs every other service); OHU gets no root, it only asks init through `ctl.start`.
   Works and keeps the rule's logic; but it only helps when Z-Link's app is **alive** (Option B), so under A it has no purpose.
2. **OHU binds `127.0.0.1:1666…1999` itself** after Z-Link's app is disabled, so the daemon connects straight to OHU and no
   redirect is needed. Technically possible (these are ordinary unprivileged loopback ports), **but it is not acceptable:**
   the Z-Link *app* is the half that performs Z-Link's **activation and licence** and speaks to the daemon about it
   (canon 52: the app deletes the licence file when its unit id is wrong; whether the daemon itself asks anyone about activation is **not established**, ❓ — see §14.1, which withdraws this reason).
   An OHU that stands in for that half would use Z-Link's CarPlay engine **without Z-Link's authorisation check** — that is
   the «bypass of paid authorisation» the CoD rules forbid. We do not build it, even if the protocol were known (it is not:
   the app's side is a packed dex, canon 52).
3. **Say plainly that CarPlay in OHU mode is not possible under A.** This is the shipping answer: with Z-Link's app disabled
   there is no licence-checking half and no feed; with it enabled there is no switch to speak of (Option B).

**So the no-root verdict is:** under A there is no root anywhere in OHU and no CarPlay in OHU mode; an iPhone user selects
Z-Link, which is what the switch is for. Option B would need only the firmware's init oneshot (way 1), still no root in OHU.

### 13.4 Does Z-Link belong to the firmware? («Служби Zetlink в прошивці, чи це частина прошивки?»)

Yes, both halves ship in the image (canon 52, `ZLINK_MAP.md`): the **daemon** is an init service (`service z-link
/system/bin/zlink5.sh`, `/system/bin/z-link`, `/system/bin/z-mdnsd`, in `init.rc`), the **app** is `/system/app/zlink5/…apk`
with its 56 native libraries. Because it is our firmware, we can change when init starts it (`disabled`) and switch both
halves from the system — which is the whole of the shipping model. What we cannot change is the Z-Link code itself (a packed,
licensed program), and we never touch its licence.

### 13.5 Effect on the tasks and decisions

- **Gate for repair/ROM builder:** the CarSettings row and reconciler (§5, now §13.1), `ctl.start/stop z-link` sepolicy for the
  reconciler's domain, `service z-link … disabled` in the image, QF BT `projection.reset`, the resolver (§4 B-table) for
  `com.zjinnova.zlink` call sites — the resolver now has only two answers (one app or the other), by the setting alone.
- **Dropped:** D3, D4, the feature-flag writer, the redirect init services, R6–R10 as blockers. **Still open:** R2 (the exact
  `z-link` stanza and whether `ctl.stop` takes every child), R3 (USB gadget teardown on a live switch), R4 (soft-AP stop), R5 (A14
  image pass 2), D2 (migration of stock-`zj` units).
- **Proofs P1–P14 stand;** P15–P21 apply only if Option B is ever built.

## 14. Revision 4 — the firmware's Z-Link daemon is ours; OHU serves the feed itself (target model C)

Owner (24.11), via the head lead: «Zclink по факту розповсюджується через сайт. А виробник магнітоли бере на себе частину
сервісу в самій магнітолі. Тобто те, що в прошивці від QF, наше по природі своїй … І до ліцензії Zclink воно ніякого
відношення не має. Самим Zclink ми не користуємося.»

**D3 is closed.** The QF-firmware parts — the init service `z-link`, `zlink5.sh`, `/system/bin/z-link`, `/system/bin/z-mdnsd`, the
engine libraries, `gocsdk_zj` — are ours; no Z-Link licence is involved in using them. The Z-Link **app** we do not use.

### 14.1 Correction of two statements in earlier sections (they were stronger than the evidence)

- §11.3 row 12 said «the daemon will not run CarPlay unlicensed» and §13.3 way 2 leaned on «the daemon runs CarPlay only for an
  activated unit». **That is not established** (❓, now R11). The only measured fact (canon 52 📻) is that the **app** process deleted
  the licence file when the unit id was wrong, the daemon alone did not. Whether the daemon itself asks anyone about activation is
  unknown. Way 2 of §13.3 («OHU binds the ports») is therefore **not rejected on those grounds any more**; it is re-examined below.
- The one red line stays and is now a rule of the design: **OHU may serve the CarPlay feed; it never answers an activation or
  licence question on Z-Link's behalf.** If the daemon turns out to need such an answer from its app half, OHU does not give a
  made-up one and CarPlay in OHU mode stays out (the model then falls back to A, §13).

### 14.2 The target model C

| selection | OHU | Z-Link app | firmware daemon `z-link` |
|---|---|---|---|
| `openheadunit` | enabled; **the server** on 127.0.0.1:1666/1777/1888/1999 | **disabled** | **running**, Android Auto and the rest of its features switched off by the flag (R6) |
| `zlink` | disabled | enabled (its own servers on the same ports) | running, all features |

Differences from A (§13): the daemon is **not stopped** in OHU mode, and OHU takes the place of the app on the ports. The
unit's own CarPlay engine keeps feeding video and audio, OHU draws and plays them. No iptables, no REDIRECT, no root, no
Z-Link app. The state machine of §13.1 stays; one parameter is added to it: `daemon_in_ohu_mode` ∈ {off = model A, on = model C}.
It defaults to **off** and is turned on when the open items below are proven on a unit, so the first image is safe either way.

### 14.3 Can OHU be the server? — answer

**Mechanically yes, with no root; whether it is enough depends on three facts that nobody has read yet.**

*What is certain (🔬 our code, no root involved):*
- The daemon is the **client** and the app is the **server** on the four ports (`ZlinkProxy.kt:72-100` accepts the daemon's
  connection and dials the app, `:94`). Ports above 1023 on `127.0.0.1` need no privilege: an app may bind them. With the Z-Link app
  disabled the ports are free. So OHU does what the app does today: `ServerSocket` on the real ports instead of `+10000`, and
  no upstream dial. In code this is `ZlinkProxy.runServer` with `listenPort = port` and the upstream branch replaced by OHU's own
  endpoint — a small change; `ZlinkRedirect` and its iptables disappear from the CoD build entirely.
- The port roles are known (`ZlinkProxy.kt:205-209`, `ZlinkHub.kt:371-378`): 1666 audio (daemon→app), 1777 control, 1888 video
  (daemon→app), 1999 «extra» (role not known).
- OHU already speaks the **daemon→app** direction completely (video 0x302, audio 0x202, control, heartbeat 0x118) and the part of
  the **app→daemon** direction it injects today: touch 0x111, key 0x113, test 0x705, force-keyframe 0xd0b (`ZlinkHub.kt:301-310`).
- OHU knows the app's start message, `0x102` on 1777: it already edits it — field 4 = frame rate, field 7 = link mode
  (0 = offer off, 2 = offer CarPlay) (`ZlinkHoldPolicy.kt:45-61`, `ZlinkMessage.kt`), and keeps the last real one
  (`lastInitFrame`).

*What the daemon may need from its app half — the list, with what is known (❓ = not yet read; every ❓ is checked by observation, §14.4):*

| # | what | known | note |
|---|---|---|---|
| 1 | the `0x102` init: screen geometry, fps, link mode, other fields | partly (fields 4, 7; the others are logged but not decoded) | OHU can send a template with its own fps/mode/size; the meaning of the remaining fields comes from one capture |
| 2 | heartbeat `0x118` and other control types the app sends | `0x118` seen; further types are only logged when first seen (`ZlinkHub.kt:359-368`) | the full set = one capture of a live session |
| 3 | the role of port **1999** | ❓ | must be read |
| 4 | microphone path (Siri, calls): which port/type carries PCM app→daemon | ❓ | OHU owns the microphone by contract §5; format from the capture |
| 5 | **how the daemon starts the screen:** canon 52 shows `am start -a zjinnova.android.intent.action.ZLINK_MAIN --es page LINK --es feature carplay` *and* `am start -n com.zjinnova.zlink/…CarPlayActivity` | both strings exist in the engine | OHU can answer the **action** form by declaring an activity with that intent filter (works while the Z-Link app is disabled); the **explicit component** form cannot be answered by OHU. **R12: which one CarPlay uses, and what the daemon does when the start fails** (the AA failure signature in canon 52 shows it restarts itself) |
| 6 | `am broadcast -a com.zjinnova.zlink` (state), `…VIDEO_RESIZE_CHANGE` | known to be sent by the daemon | QF BT already consumes the first with no app involved; OHU can receive the second |
| 7 | Bluetooth help for wireless CarPlay (phone wake, the AP details): the app's `ZBTService` talks to `gocsdk_zj` through `libzbt_core` | ❓ role of the app vs `libzbt-main` (daemon side) | OHU has its own `gocsdk_zj` client (`zbt/ZbtByteChannel.kt:92`); CarPlay's messages are not the AA ones → **R8** |
| 8 | system properties the app writes for the daemon: `rw.zlink.act.ret`, `rw.zlink.mfi.id`, `rw.zlink.bt.*`, `persist.zj.*` | names known, writer and reader unknown | if the daemon *reads* an activation property the app *writes*, that is the licence question of §14.1: not answered by OHU |
| 9 | USB for wired CarPlay (gadget `iap`+`ncm`, usbmuxd), MFi chip | daemon side, no app | unchanged by who serves the ports |

*Not needed from the app:* audio output and AEC engines — OHU plays and records itself (`ZlinkAudioSink.kt`); the activities; the
second screen; Mirror, CarLife, HiCar, DLNA.

### 14.4 How to find out — read-only, no root, no change to Z-Link

OHU's proxy already sits on a **live real session** and sees every frame in both directions. Add, in a debug build, a capture:
log (to a file in the app's own storage) the type, length and first N bytes of every frame on all four ports in both directions
for one session of each kind (wireless CarPlay, wired CarPlay, idle with a phone paired), and the daemon's own `logcat`
lines. That answers #1, #2, #3, #4 and the start of #8 from the firmware's own interface (interoperability observation, the
ground the CoD rules rely on). Second experiment: with the Z-Link app **disabled** and nothing listening, watch what the daemon
does on a CarPlay offer (R12, #5). Third: a listener that only accepts and logs (no replies) on 1777, to see how long the daemon
waits and what it prints. All three are stand work; the stand is frozen until the head lead releases it, so this section is the
plan, not a result.

### 14.5 Effect on the tasks (model C)

- **OHU / Volodymyr (`codos`, only after the capture):** `ZlinkProxy` gets a *server mode* on the real ports; `ZlinkRedirect`
  becomes unused on CoD; the app's `0x102` is produced by OHU from a captured template; a declared activity answers
  `zjinnova.android.intent.action.ZLINK_MAIN`; the Z-Link UI policy and `hideZlinkUi` are not used (the app is disabled, not hidden).
  Until then model A applies: mode fixed to Android Auto, `getInstalledPackages()` empty (§13.2).
- **repair / ROM builder (unchanged from §13, plus one):** the `z-link` init service stays `disabled` in the image and is started by the
  reconciler in both selections when `daemon_in_ohu_mode` is on; in A it is started only for `zlink`.
- **Research:** R6 (which feature letter turns Android Auto off in the daemon and whether it also stops the USB claim, the BT request
  and the AP raising), R8, R11 (does the daemon itself check activation), R12 (how CarPlay is started), plus the capture of §14.4.
  R7/R10 are answered by this design (no iptables; the daemon is the client, the app was the server).
- **Proofs added:** P22 the daemon connects to OHU on 1666…1999 with the Z-Link app disabled; P23 a wireless CarPlay session
  end to end with no Z-Link app (video, audio, touch, keys, Siri, call); P24 the daemon raises no Android Auto, no BT AA request,
  no USB claim for AA in OHU mode; P25 switching selection both ways with the daemon alive in OHU mode, no reboot.
- **What is not changed:** the shipping default stays A until P22–P25 hold; the licence of Z-Link is untouched in every state.

## 15. Review of Volodymyr's QF4 commits (24.11, `b7173a5b`…`b2da9c1d`) against this design

Merged fast-forward into our branch; no conflict. Six commits: key-log watcher keeps running when the service dies, a cancelled key-log
stream now really ends (also in `SystemUidShell.streamLines`, which is our codos helper — his edit is correct and keeps the codos branch),
hold-to-seek for next/previous and a key-repeat debounce, a CarPlay «connecting» step, **wired Android Auto on a zLink unit** with a home
USB/Wi-Fi switch, and **Android Auto that follows the ignition**. App version is now `4.1.0-beta1-QF4`, versionCode 127.

**No new root, su, self-ADB, iptables or manifest permission** in these commits (checked by diff: manifest, `app/src/codos`, `magisk/`,
Gradle flavors untouched; the only shell use added is the already-codos-guarded key-log stream). `cod/ROOT_TO_SYSTEM.md` needs no new row.

### 15.1 One correction to §13.2 / §6.2 (this changes what Volodymyr must NOT do)

`ZlinkScreenPolicy.getInstalledPackages()` is used at 17 call sites for **two different meanings**:
- **(A) «this is a QF-family unit with the unit's own hotspot and key service»** — H.265 default (`Settings.kt:1291`), the unit's hotspot
  and its 5 GHz keeping (`App.kt:101`, `EarlyHotspot.kt:37`, `SoftApCredentialsProvider.kt:316`, `AapService.kt:2114`, `HotspotConfigClient`
  rows `SettingsFragment.kt:995,1241`), the key-log watcher and its setting (`BridgeVoiceKeyWatcher.kt:139`, `SettingsFragment.kt:2448`),
  USB/Wi-Fi modes and the home switch (`SettingsFragment.kt:3525,3324`, `HomeFragment.kt:635,2083`), join-refusal restart
  (`NativeAaHandshakeManager.kt:3444`), onboarding (`MainActivity.kt:1591`), OEM-app management hidden (`SettingsFragment.kt:2760`,
  `OemAppManager.kt:293`).
- **(B) «Z-Link is the CarPlay source»** — the CarPlay mode and its service, i.e. `ProjectionModePolicy`/`ProjectionSwitcher`/`ZlinkService`.

§13.2 said «in `codos`, `getInstalledPackages()` returns an empty list». **Withdrawn.** On CoD OS the unit is still a QF unit in OHU mode
(its hotspot, its key service, its H.265 decoder), so meaning (A) must stay **true while the Z-Link package is installed — enabled or
disabled**. `getInstalledPackages()` uses `getPackageInfo(pkg, 0)` (`OemAppManager.kt:93-107`), which still returns a disabled app 🧩, so under
model A it keeps working unchanged. What changes for model A is only meaning (B): in `codos` the projection mode is fixed to Android Auto and
the CarPlay tile/row are not offered (`ProjectionModePolicy`, `ProjectionSwitcher`, `ZlinkService` never started). Proof P26: with the
Z-Link package disabled, `getInstalledPackages()` still returns it and the hotspot rows, key watcher, H.265 default and the USB/Wi-Fi home
switch are all present.

### 15.2 Process-level receivers and the switch

`AccOnReceiver` (implicit ACC_ON, `RECEIVER_EXPORTED`) and `UsbAttachReceiver` (USB attach) are registered in `App.onCreate`
(`App.kt`), so they live as long as OHU's process. Under L1 (package disabled when Z-Link is selected) there is no process and nothing
registers: silent as the contract demands. They do not read `cod_projection_app`; the §6.1 self-check is therefore still the belt to add
(`AapService` start paths, these two receivers, `NativeAaHandshakeManager`). The new `ACTION_CHECK_USB` start makes a plug-in start the
service from a cold process — another reason the package must be **disabled**, not merely idle, in Z-Link mode.

### 15.3 Wired Android Auto on a zLink unit — an input for R6

Volodymyr now offers USB (wired Android Auto through OHU's own USB host) on a unit where Z-Link's app and daemon are alive. If his
unit really connects a phone over the cable while Z-Link's daemon runs, that is **evidence for model C**: the daemon does not take the
Android Auto USB device. The commit says «on a zLink unit» but does not say it was measured with the daemon running and which Android Auto
path Z-Link had on. **Ask him** (one question): was wired AA through OHU measured with Z-Link's daemon alive and its Android Auto not
disabled? Proof P24 stays required on CoD.

### 15.4 Build state

Our last module zip (`2e0cce05…`) is built from QF3 and older than two of our code commits and now Volodymyr's QF4. Before the next
hand-over: `module.prop` → `v4.1.0-beta1-QF4-codos`, versionCode 127, then `tools\build_codos.ps1`. Not done in this review (no request,
and the stand is frozen). The new unit tests were not run here either; they are his and sit under his gate (`testGithubDebugUnitTest` /
`testCodosDebugUnitTest` should be run once after the merge).
