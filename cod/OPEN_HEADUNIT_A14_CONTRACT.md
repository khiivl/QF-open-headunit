# Open Headunit on CoD OS (qf001 A14 graft on K706) — integration contract

**Parties:** Open Headunit, QF fork by Volodymyr (khiivl): `github.com/khiivl/QF-open-headunit`, upstream
`andreknieriem/headunit-revived`, licence **AGPL-3.0**. And the CoD OS image line: head lead (ROM Builder) + qf001 +
repair (CarSettings / framework) + sound (BitPerfect A14).
**Status:** opened by the head lead on 22.11.2026 at the owner's order.
**Where the CoD work happens** (owner, after talking to Volodymyr, 22.11): branch **`open-headunit`** of the private
A14 repo `kostyamat/qf001_Android14_project`, synced from the fork's `feature/zlink-carplay-feed` @ 30d52f5d (his last
push). Volodymyr reviews it and commits there; useful fixes go upstream as PRs. A first PLACEHOLDER build (root →
`android.uid.system`, CoD-signed, system app) is made from this branch by a CoD session, so the switch Z-Link / Open
Headunit can be tried on stand 198. The canonical copy of this file is in `C:\APPS_Contacts\OpenHeadunit--qf001-A14\`
on the owner's PC; `cod/OPEN_HEADUNIT_A14_CONTRACT.md` in the branch is its mirror.

## 0. What the owner decided (22.11.2026, verbatim where it matters)
- Open Headunit replaces Z-Link as the **default** projection app of the future CoD OS image. Z-Link stays in the image,
  hidden, and the user may switch back. Reason: many users lost their Z-Link licences (a PAC restore, or missing the
  firmwares that auto-licensed it), and nobody outside QF gets Z-Link updates.
- **No licence of any kind on it:** «Ніяких ліцензій ми на неї накладати не будемо. Це open source … Вона працює
  завжди. Працює під своєю ліцензією.» Useful fixes go upstream as PRs. So: no CoD licence gate and no CoD firmware-binding
  check (`COD_OS_FIRMWARE_BINDING.md` does NOT apply). It must run on any firmware.
- Native on CoD OS: signed CoD, **no root — `android.uid.system` instead**.
- Z-Link-like simplicity: «дві кнопки». Only the choice **USB / Wi-Fi** stays visible. Everything else sits in a
  developer section «щоб людині туди не хотілося лізти», hidden in code «if defined код ос», not deleted. The launcher
  (HOME) role is also only there.
- At least as good as Z-Link: the best connection, the fastest video path, no lag in video or sound. «Нажав кнопку і
  поїхало.»
- No separate CoD line works on it now. Volodymyr develops it, together with the DSP work; this file says where CoD OS
  needs it to go.

## 1. Identity (decide once — it cannot change after the first image)
| item | value |
|---|---|
| package | ❓ keep `com.andrerinas.headunitrevived` or a CoD id. Note: a CoD-signed system copy blocks the Play/upstream APK of the same id on that unit |
| signature | CoD platform key (cert SHA-256 `766232df…ad9e`; Volodymyr holds the CoD keys) |
| shared uid | `android:sharedUserId="android.uid.system"` |
| place | `/system/priv-app/OpenHeadunit/OpenHeadunit.apk` + `/system/etc/permissions/privapp-permissions-openheadunit.xml` |
| root | none. Every `su` call becomes the system API it stood for (USB host permission, network/Wi-Fi, input, settings …). List them in the fork's README |
| build | a `codos` product flavor (`BuildConfig.CODOS`), our answer to «if defined»; the other flavors stay as upstream |

The 4.1.0-beta1 now on stand 198 (`/data/app`, DEBUGGABLE, another signer) is a test copy, not the image file.

## 2. UI on CoD OS (`codos` flavor)
- **Main screen:** two big actions, USB and Wi-Fi, plus the connection state. Nothing else.
- **Developer section:** everything else — codecs, resolutions, DPI, audio options, transports, launcher mode. Reached
  by a hidden gesture (e.g. 7 taps on the version).
- Day/night and the CoD look follow the system (skill `qf-a14-system-ui` for colours and rows).

## 3. Choosing Z-Link or Open Headunit (repair builds the switch)
- CarSettings gets one row: «Проєкція телефона: Open Headunit / Z-Link». Default Open Headunit.
- ❓ The store, agreed with repair before either side builds: proposed Settings.Global `cod_projection_app` =
  `openheadunit` | `zlink`.
- The inactive app is not auto-started and its launcher entry is hidden. Z-Link's licence files in
  `/great/protect_dir/.zjinnova/` are never touched.
- 🔴 **Bluetooth: when Open Headunit is NOT the chosen projection app, it registers nothing on Bluetooth and does not
  react to USB attach** (📻 stand 198, 22–23.11.2026):
  - What it did: the placeholder's `NativeAaHandshakeManager` registers on the SoC Bluetooth an RFCOMM server with the
    Android Auto wireless UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66` («AndroidAuto»), plus a «Hands-Free Unit» HFP
    record. Z-Link serves that same UUID on the car Bluetooth module.
  - Its `UsbAttachedActivity` came up at boot on the hub/Ethernet attach.
  - What followed: from 23:52, when it was installed, the owner's phone never opened Z-Link's AA channel. Z-Link cycled
    its hotspot every ~8 s and the phone reboot did not help.
  - The fix that worked (23.11 00:14): Open Headunit disabled, then the owner RE-PAIRED the phone with the unit's
    Bluetooth. Wireless Z-Link came up and stays up with Ethernet back.
  - ❓ Open Headunit is the prime suspect, but this is not proven: it was not re-enabled to reproduce.
  - Rule from here on: the AA UUID, the HFP record and the USB-attach entry are registered only while
    `cod_projection_app` = `openheadunit`. They are released at once when the switch moves to Z-Link.

## 4. Sound on CoD OS — facts the app must respect
- **Android's digital gain is pinned at unity** (BitPerfect / repair «unity gate»). The audible volume is the **MCU's**,
  per source, 0..32 (`sys.media.vol`, `sys.radio.vol`, `sys.call.vol`, `sys.aux.vol`). The app never scales its own
  output to «set volume»: AudioTrack volume stays 1.0.
- When Android plays media, the MCU switches its source to the media channel.
- HAL: `audio.primary.ums512.so` with BitPerfect A22 + C14. The HAL never enters its VoIP mode (`sprd_voip_start` is
  ignored), so app audio is always a normal playback and a normal record. Formats and rates: ask the sound line or read
  `dumpsys media.audio_flinger` on the unit.
- Usages:
  - phone media → `USAGE_MEDIA`;
  - navigation prompts → `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, so the system ducks the music;
  - the phone call through AA → `USAGE_VOICE_COMMUNICATION`;
  - the phone's assistant voice → `USAGE_ASSISTANT`.
  - Never `MODE_IN_COMMUNICATION` outside a real call.
- 🔴 **Bug to find (owner):** «При старті програми звук … дуже-дуже тихенький, а потім через якийсь час з невідомої
  причини він стає нормальним … через секунду, може не статися ніколи, а може … через 2 хвилини, 30 секунд.»
  - Candidates to check together:
    - the MCU source switch racing the first AudioTrack;
    - a ducked focus state at start that is never restored;
    - the media stream index against the unity gate;
    - AA's own start volume;
    - an output re-open / resampler change.
  - Evidence to take while it is quiet and again once it is normal: `logcat` (AudioFocus, the unity-gate lines,
    the MCU source change), `dumpsys audio` and the whole `dumpsys media.audio_flinger`, each to a file.
- 🔴 **No assistant is a player** (owner's standing rule):
  - the assistant's audio never takes permanent media focus;
  - the assistant never becomes the target of media or volume keys.
  - AA itself is a legitimate media source.

## 5. Microphone
- Follow `C:\APPS_Contacts\wDSP--BitPerfect\MIC_OWNERSHIP_CONTRACT.md`.
- Open the mic only while the phone asks for it (assistant or call):
  - `VOICE_COMMUNICATION` during a call;
  - `VOICE_RECOGNITION` for the assistant.
- Release the mic at once, and never hold it idle.

## 6. Keys
- **Assistant key:** the CoD router (repair, being built) sends the assistant key to the projection app only while
  **its activity is on screen**; otherwise the local CoD assistant gets it.
  - The app receives it on an explicit, permission-protected entry and sends the AA voice request to the phone.
  - ❓ The entry's name and the caller check are agreed with repair. Proposed: action
    `com.communityofdevs.projection.VOICE`, accepted only from uid 1000.
  - Z-Link has this from the factory; here we build it ourselves.
- **Steering media keys** (next / previous / play-pause) go to AA while it is the active media source.

## 7. Volume from the phone's assistant
- Owner: Google Assistant in AA should really change OUR volume, «а не казати „виконано" і нічого не робити».
- ❓ First find whether the AA protocol carries a volume command to the head unit.
  - If it does, the app turns it into a change of the Android music-stream index. CoD OS (owner's design) keeps that
    index without any effect on the sound and syncs it with the MCU level 0..32 through the AI bridge, so the MCU moves.
  - If it does not, record that, so nobody keeps trying.

## 8. Optional: CoD Island
The connection state as an island item: `C:\APPS_Contacts\SystemUI--CodIsland\COD_ISLAND_API.md`.

## 9. The image slot (qf001)
- qf001 reserves the place, the privapp XML and the default selection in the recipe.
- The APK comes from Volodymyr's `codos` build, CoD-signed with `android.uid.system`. A re-signed beta would freeze the
  wrong identity (§1).

## 10. Projection session state: Open Headunit announces a session exactly as Z-Link does (24.11.2026, owner's ruling, third revision)

Owner (24.11): «ти розібрався що розсилає, і що вмикає Z-Link?? Ось те саме маєш вмикати ти, а все решту нафіг». Before that: «виставляти точно таку саму
властивість; головне правило — SSOT і гармонія між програмами, а не 100 500 різних пропів». So OHU does **not** write any property of its own and
does **not** invent a signal: it sends what Z-Link sends, and the system does what it already does for Z-Link. Replaces the earlier drafts
(`sys.qf.ohu.connect` + `.pid`; then a direct write of `sys.qf.bt.aa.connect`), both withdrawn the same day.

Why: owner's order to the Bluetooth line, 24.11: «коли активний Android Auto в активному підключенні, ти не перехоплюєш фокуси і не виводиш
ніякі повідомлення, які приходять по Bluetooth» — «поки є активне підключення».

### 10.1 What Z-Link does, and so what OHU does

Z-Link announces an Android Auto session with one broadcast: action **`com.zjinnova.zlink`**, extras **`status`** = `CONNECTED` / `DISCONNECT` and
**`phoneMode`** = `auto_wireless` / `auto_wired` (`BTReceiver`, branch `ANDROID_AUTO`/`ANDROID_AUTO_WIRED`, 🔬 A10 decompile and the BT line's jadx of A14).
QF Bluetooth's own receiver turns it into everything the system does for an Android Auto session: it sets `sys.qf.bt.aa.connect` (`true` on CONNECTED,
`false` on DISCONNECT or `phoneMode=none`), runs `disableA2dpIfNeed()` / `resumeA2dpIfNeed()`, and the other listeners of the same action
(the in-call dialog, the Canbus distributors) get it too. **OHU sends the same broadcast with the same words.** Nothing else: no property, no
second action, no pid. The flag has the **one writer it always had**, QF Bluetooth; only one projection app is active (design §13), so Z-Link's
and OHU's announcements never overlap.

| OHU sends | when |
|---|---|
| `com.zjinnova.zlink`, `status=CONNECTED`, `phoneMode=auto_wireless` (Wi-Fi link) or `auto_wired` (cable) | `CommManager` reaches `HandshakeComplete` / `TransportStarted`: the SSL handshake is done, a working Android Auto session exists (`CommManager.kt:201-218`) |
| `status=DISCONNECT`, the `phoneMode` of the connect it ends | the state leaves those two (disconnect, error, a new attempt), the service stops, the process hits an uncaught exception |
| `status=DISCONNECT` once at process start | only if `sys.qf.bt.aa.connect` is still `true` from an earlier process that was killed (the cleanup Z-Link's daemon restart does not do); otherwise nothing |

Where in OHU (`codos` only, `connection/ProjectionSession.kt`): the one state setter `CommManager.connState` (a setter, not a collector: the flow is
conflated); `AapService.onDestroy` (publishes what the state really is); `App.onCreate` (stale cleanup + crash handler). Connecting, Connected,
StartingTransport, Error and Disconnected are **down**: a phone that keeps failing its handshake does not toggle the system.

### 10.2 What this does for the system, and what is different from Z-Link
- **QF BT v35 `Projection`** reads `sys.qf.bt.aa.connect` itself at its decision points (no polling, callback or hint broadcast): no BT-music focus, no delayed
  AVRCP pause, no BT state toasts while a projection is up. Right for OHU: the owner's order.
- **The A2DP drop** (`disableA2dpIfNeed`) now happens for OHU **as it does for Z-Link**. Right: in OHU the Android Auto link carries the media audio,
  as in Z-Link. The stock toast that goes with it is a BT state toast: v35 shows those only with no projection up, and `BTReceiver` sets the flag **before**
  it calls `disableA2dpIfNeed()` (`BTReceiver.java:364` then `:365`), so the toast is suppressed. **Confirmed by the BT line from its A14 build (24.11, v35 installed on 198):** the `auto_*` CONNECTED branch runs `setZlinkAutoConnectedStatus(true)`, then sets
  `sys.qf.bt.aa.connect = true` (jadx `:302-304`), then `disableA2dpIfNeed()`, which only posts the toast runnable; `Projection.toast` re-reads the property when it runs, so it is already true and nothing shows.
  On DISCONNECT `resumeA2dpIfNeed` reconnects A2DP only if HFP is up. Still ❓ on a unit with OHU as the sender: proof P28.
- **The island, Canbus distributors and the in-call dialog** listen to the same action and receive OHU's session like Z-Link's.
- **AI bridge (steering voice key):** on `true` it forwards the key to Z-Link's package (`onZlinkVoice`, `com.qf.action.cp.call`, `setPackage(com.zjinnova.zlink)`).
  With Z-Link disabled that reaches nobody, so during an OHU session the voice key opens nothing on the head-unit side; **until the fix lands the phone gets the key
  through OHU's own key watcher** (`BridgeVoiceKeyWatcher`). Fix (head lead's ruling): the owner's rule is ONE assistant function, so the resolver of design §4 B5
  lives in **AssistRouter**: its projection branch picks the active projection app, Z-Link or OHU, and AiBridge's `onZlinkVoice` defers to it. Owner of the change:
  repair. Proof P32.
- **Sender check: none** (A10 source, and confirmed for A14 by the BT line): `BlueToothApp` registers `BTReceiver` dynamically, `registerReceiver(receiver, filter)` for the action `com.zjinnova.zlink`, with no permission;
  `BTReceiver` reads only `status` and `phoneMode`. The broadcast is a plain implicit one, as Z-Link's. Proof P27 still shows it end to end on a unit.

### 10.3 Stale case and gap (kept stock-like, head lead and BT line, 24.11)
If the OHU process is killed (OOM, `kill -9`, force-stop) during a session no code runs, and the flag stays `true` until a new OHU process starts (it then sends the
`DISCONNECT`, §10.1) or the unit reboots. Stock Z-Link has the same gap (nothing clears the flag except its own broadcast). No reader-side check, no pid, nothing extra.
The reconciler of the projection switch (design §13.1) also sends `DISCONNECT` once after it disables the OHU package, so a switch leaves nothing stale.

### 10.4 Register of the flag: writers, readers, two-writer cases (24.11, accepted by the head lead as §10.6)
Writers/clearers found in the whole searched image: **QF BT `BTReceiver` only** (A10 `BTReceiver.java:364` true, `:368` false on DISCONNECT, `:386` false on `phoneMode=none`;
the A14 jadx `:303`, `:307`, `:325`) plus the owner's two Magisk modules that write «Z-Link is on top» into the same flag: **QF_HID_Daemon v1.75** (`hid_daemon.sh:551-563`)
and **QF_KeyLearn** (`module/src/40_system.sh:33-45`); neither is installed on stand 198. Z-Link itself does not write it (no occurrence in its 56 native libraries; the Java
layer is packed ❓). Nothing writes it at boot, on BT disconnect or on ACC off; `sys.*` resets at reboot. Readers: QF BT `Projection` (v35), the AI bridge voice-key router
(`McuKeyEventHandler.java:79,98`, `Constants.java:56`), NetworkBurst's BT2 pager (`NetworkBurstService.java:1495`, `PhonePager.java:109`: slows or stops, right for OHU), DialerKM
`isCarPlayConnected` (no caller). **The conflict is only the owner's modules**: while Z-Link is selected they write `false` when Z-Link leaves the top although the session goes on;
while OHU is selected they write only their start value `false`, which clears OHU's `true` if one of them starts or restarts mid-session. Proposed fix: delete the flag block in
both (the owner's decision). The canon (`PROPERTIES_SSOT`, qf-knowledge `535e3d0`) carries these rows.

### 10.5 The connection types are not a choice on CoD OS (same owner order: «вибір типів підключення і т.п. нафіг»)
In the `codos` build: `Settings.connectionModes` is always {USB, Wi-Fi} (both transports on, the way Z-Link takes whatever phone comes); the rows that choose it are hidden
(`connectionMode`, `scopeNotice`, `listenForUsbDevices`, `useLibusb`; `SettingsProfilePolicy.HIDDEN_ON_CODOS`); the home screen shows both connections as two tiles and no longer
collapses them into one that flips (`HomeFragment`). The rest of Volodymyr's zLink-unit profile (the hidden Wi-Fi modes, the pinned hotspot strategy, the 5 GHz keeping) already
follows the Z-Link path and is unchanged. Not touched: the auto-start and auto-connect rows, the developer section.

### 10.6 Proofs (stand 198, after the owner lifts the disable of 23.11; nothing runs before)
P27 a real session: `logcat` shows QF BT receiving `com.zjinnova.zlink` from OHU, `getprop sys.qf.bt.aa.connect` before = false, during = true, after = false. P28 during the session: QF BT takes no
focus, shows no BT toast, A2DP is dropped as with Z-Link. P29 service stop and ACC off: DISCONNECT, flag false. P30 switch to Z-Link mid-session: false. P31 reboot: false.
P32 voice key in an OHU session: recorded; fixed when AssistRouter's resolver lands. P33 a phone on the cable connects as `auto_wired`; a wireless phone as `auto_wireless`; both with no
setting touched.

### 10.7 Receivers and users of `com.zjinnova.zlink` in the real A14 image (R5, 24.11, read-only decompile of the images' own apps)

Source: the A14 `system.img` / `system_ext.img` of `a14-jitu2.romws` (the image Z-Link and the CoD graft run in); every APK and the two framework jars that
mention `zjinnova` were decompiled with jadx. **Two apps register a receiver for the action** (`addAction("com.zjinnova.zlink")`): QF Bluetooth and QF Canbus. Everything
else only starts Z-Link by name, checks it, or declares its package in a list. Z-Link's own receiver of the action cannot be read: the app's dex is the Legu wrapper
(`MyWrapperProxyApplication`, the real code is an encrypted asset), so «Z-Link's `SystemEventsBroadcastReceiver` receives it» rests on the head lead's statement ❓ and is
the reason for the guard `ProjectionSessionPolicy.mayAnnounce` (OHU sends nothing while Z-Link is enabled).

| # | who (A14) | where | what it does with `com.zjinnova.zlink` | right for OHU's `auto_wireless` / `auto_wired`? |
|---|---|---|---|---|
| 1 | **QF Bluetooth** `QF_Bluetooth_Gongban3` | `BTReceiver.java:234` (`case "com.zjinnova.zlink"`), registered dynamically by `BlueToothApp`, no permission, no sender check (BT line, jadx `:302-304`) | `auto_*` CONNECTED: `setZlinkAutoConnectedStatus(true)`, **`sys.qf.bt.aa.connect=true`**, `disableA2dpIfNeed()` (toast gated by `Projection.toast`); DISCONNECT: flag false, `resumeA2dpIfNeed()` (A2DP back only if HFP is up) | **Yes**, the whole point of §10 |
| 2 | QF BT `TechBTSettingManager.java:994`, `Function.java:334` | not receivers: a check of `/data/data/com.zjinnova.zlink` and the «easy connect» launch of Z-Link's `MainActivity` | the data directory stays when the package is disabled; the launch does nothing while Z-Link is disabled | harmless; the easy-connect launch needs the resolver (design §4 B4) |
| 3 | **Canbus** `QF_Canbus` `SourceDistributor:448` | receiver; A10 reading of the same code: reacts only to `phoneMode=carplay_wireless` (source id 33 on CONNECTED) | nothing for `auto_*` | **Yes: inert** |
| 4 | Canbus `TeslaDataController:204` | receiver; only `phoneMode=carplay_wireless` sets a CarPlay flag | nothing for `auto_*` | **Yes: inert** |
| 5 | Canbus `KeyDistributor:355` (`:162` handler) | receiver; reacts to `status=PHONE_CALL_ON/OFF` only (remaps steering call keys); `:542` starts Z-Link's `MainActivity` on an MCU key | OHU never sends `PHONE_CALL_*`, so nothing; the MCU key `Zlink=79` opens Z-Link's screen | **Yes: inert** for the session; the key's target needs the resolver (design §4 B7) |
| 6 | **AiBridge** `QF_AiLit_Bridge` | `McuKeyEventHandler.java:29`, `Constants.java:55` | not a receiver: **sends** `com.qf.action.cp.call` to Z-Link's package for the voice key and checks `/data/data/com.zjinnova.zlink`; reads `sys.qf.bt.aa.connect` | the voice key goes nowhere while Z-Link is disabled: **AssistRouter's resolver** (repair), §10.4; until then OHU forwards it to the phone itself |
| 7 | CarSettings `QF_CarSettings_GongBan3` | `CarSettingService:794` (top package is Z-Link and the AP failed → Wi-Fi on), `Tools:267` (voice app list), `KillApps` white list | not receivers | inert for OHU; the voice-app list entry for Z-Link stays visible (design §4 B10) |
| 8 | **Launchers** (FutureWings, BlueDesert, CyanMountains, QFUIMountainSea, QFUIDreamMountain), Recent, NavigationBar | `Utils`, `Function.RunApp`, `CustomAppWidget*`, `BubbleTextView`, `RecentService:104`, `Utils:149`, `Utils:183` | not receivers: launch Z-Link by name, name its tile «ZLINK», default the first launcher card to it | none of them can start OHU; the OHU tile is repair's (design §4 B12). With Z-Link disabled a tap on the old tile does nothing |
| 9 | `QF_OTAUpgrade`, `QF_Test` | `QFAppPackageConstants`, `FactoryTestMainActivity:1164`, `Constants.ZLINK_ENABLE_FILE=/great/protect_dir/.zjinnova` | not receivers: a constant, a factory-test launch, the licence file path | inert for OHU |
| 10 | **NetworkBurst** `QF_NetworkBurst` | `tunnel/QFTunnelVpnService.java:30` `EXCLUDED_PACKAGES = {"com.zjinnova.zlink"}` | not a receiver: keeps Z-Link's traffic **out of its VPN tunnel** (the wireless Android Auto link is local to the unit) | **MISFIRE for OHU: `com.andrerinas.headunitrevived` is not excluded.** With the tunnel up, OHU's own link to the phone can go into the tunnel and break the session. NetworkBurst line must add the OHU package to the list |
| 11 | **framework** `framework.jar` (the patched `Activity` and `Display`) | `android/app/Activity.java:841` and `:1223`: **in every Activity of package `com.zjinnova.zlink`, `onCreate` registers a receiver for `com.qf.action.cp.call` and `onDestroy` removes it**; `android/view/Display.java:711,:747`: Z-Link (and a camera app) get their own window height (1292 / 1372 on the tall-screen units) | not a receiver of our action, but **the call/voice key path into Z-Link lives here**: QF BT and AiBridge send `com.qf.action.cp.call` to Z-Link's package, and only a Z-Link Activity listens. OHU's activities have no such receiver, so the call keys and the voice key **cannot** reach OHU through that route | **Misfire for OHU**: needs the same hook for the projection app the resolver names (framework change: ROM builder / repair), or OHU's own exported, uid-1000-only receiver for `com.qf.action.cp.call` (contract §6). The `Display` special case is a parity point on tall screens (design D2). `QF_OTAUpgrade` also embeds A10 copies of `AudioRecord` (`:547,:581` mutes the amp ~100 ms when Z-Link starts recording), `View` (`:12113`) and `Display` (`:415,:428`) |
| 12 | `services.jar` | `ActivityManagerShellCommand.java:116-117` + `:1629,:1663` (the `START_DAEMON_SERVICE` boot gate), `BroadcastSkipPolicy.java:25` (constant only), `MediaFocusControl.java:1096` (`sys.carplay.bt.call` true: every audio-focus request except Z-Link's fails) | the focus rule can only bite if someone sets `sys.carplay.bt.call`, which only QF BT does on `PHONE_CALL_ON` for CarPlay/HiCar | OHU never sets it, so no effect; if OHU ever announced a call state it would need the resolver here (design §4 C4) |
| 13 | **SystemUI** `SystemUI.apk` | `statusbar/NotificationListener.java:174`: a notification suppression list that names `zjinnova` packages (the status-bar listener drops certain notifications by package and channel) | not a receiver; **hides some of Z-Link's own notifications** | parity point: OHU's foreground-service notification is not in that list and shows in the status bar; whether that is wanted is a UI decision (design §4 / UI design) |
| 14 | **Z-Link app** `zlink6-qianfeng-release-v6.1.06` | Legu wrapper only (`WrapperProxyApplication`) | ❓ not readable | guarded: OHU announces only when Z-Link is not enabled |

**Vendor files that name Z-Link** (text scan of all four images): `system/etc/init/hw/init.rc` and `vendor/etc/init/hw/init.common.rc` (`service z-link /system/bin/zlink5.sh`, `class main`, `oneshot`, `seclabel u:r:shell:s0`; the same stanza in both),
`system/bin/zlink5.sh` (gadget functions `iap.gs0`, `ncm.gs1`, the watchdog loop, `LD_LIBRARY_PATH=/data/data/com.zjinnova.zlink/lib`), `vendor/build.prop` (`rw.zlink.disable.features=hl`,
`rw.zlink.audiostream.*`), `system/config/NotKillAppsBeforeSleep.ini` and `FreezerIgnore.ini` (both list `com.zjinnova.zlink`; **OHU is in neither: for repair, design L6**).
The flag `sys.qf.bt.aa.connect` itself occurs only in `QF_Bluetooth_Gongban3` and `QF_AiLit_Bridge` in this image.

## Ledger (each side ticks its own column, with proof)
| item | Open Headunit (Volodymyr) | CoD OS (head lead / qf001 / repair / sound) |
|---|---|---|
| §1 identity decided | | |
| §1 no `su` left | | |
| §2 `codos` UI | | |
| §3 switch + store | | |
| §4 quiet-start found | | |
| §4 usages | | |
| §5 mic | | |
| §6 assistant key | | |
| §7 volume answer | | |
| §9 image slot | | |
| §10 session announced as Z-Link does (`com.zjinnova.zlink`) | | |
