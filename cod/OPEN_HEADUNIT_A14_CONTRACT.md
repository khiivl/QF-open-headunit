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

## 10. Projection session state: how Open Headunit tells the system «a session is up / down» (24.11.2026, DRAFT for the head lead's gate)

Why: owner's order to the Bluetooth line, 24.11: «коли активний Android Auto в активному підключенні, ти не перехоплюєш фокуси і не виводиш
ніякі повідомлення, які приходять по Bluetooth» — «поки є активне підключення». QF Bluetooth gates its focus and messages on one
`Projection.active()`; it knows Z-Link from Z-Link's own broadcast `com.zjinnova.zlink` and the props `sys.qf.carplay.connect` /
`sys.qf.bt.aa.connect`, and knows nothing about Open Headunit.

**Rule: OHU does NOT imitate Z-Link's broadcast.** QF BT treats that action as Z-Link's and reacts with the stock A2DP disconnect and a
Chinese toast (BT line, `BTReceiver`). This replaces the plan «OHU emits `com.zjinnova.zlink`» of earlier drafts (design §6.3, withdrawn).

### 10.1 The contract: two system properties in the QF pattern (a prop, not a broadcast)

| property | value | meaning |
|---|---|---|
| `sys.qf.ohu.connect` | `"true"` / `"false"` (absent = false) | an Open Headunit Android Auto session is up |
| `sys.qf.ohu.pid` | the process id of the OHU process that set it up, or absent | lets a reader tell a live «true» from a stale one |

**Up** means: OHU's `CommManager` state is `HandshakeComplete` or `TransportStarted` — the SSL handshake with the phone is done, i.e. the phone
is a working Android Auto phone and a session exists (`CommManager.kt:201-218`). `Connecting`, `Connected`, `StartingTransport`, `Error` and
`Disconnected` are **down** (a phone that connects and fails its handshake over and over must not toggle the system).

**Reader rule (QF BT, and anyone else):** OHU's session is active **iff** `sys.qf.ohu.connect == true` **and** process `sys.qf.ohu.pid` is alive
and is OHU (`/proc/<pid>/cmdline` equals `com.andrerinas.headunitrevived`; both sides run as uid 1000, so the process is visible). Anything else is
down, whatever the property says. `Projection.active()` = Z-Link's existing signals **or** this. Reaction to changes: poll, or
`SystemProperties.addChangeCallback` and re-read; nothing is pushed.

Why a property and not a broadcast (the choice asked for):
1. **Late readers and restarts.** A property holds the current truth; a reader that starts later (QF BT restart, boot order) reads it. A broadcast would be lost.
2. **No spoof surface.** Only system domains can set a `sys.qf.*` property; a broadcast needs a signature permission declared by someone and checked at the receiver.
3. **It is the pattern QF already uses** for exactly this (`sys.qf.carplay.connect`, `sys.qf.bt.aa.connect`; readers: AiBridge, Canbus, framework).
4. **It cannot trigger Z-Link's reactions**, because nothing listens to the name.
5. **No sepolicy change needed** if `sys.qf.*` is already settable from the system app domain (QF BT does it) ❓ proof P27: OHU, as the system app, sets and clears it on a unit.

Cost accepted: a property cannot clear itself when its writer dies. That is why the reader rule includes the pid check, and why `sys.*` is reset at boot.

### 10.2 Where OHU sets and clears it (`codos` only, one class `ProjectionSessionPublisher`)

| event | action | where |
|---|---|---|
| state becomes `HandshakeComplete` or `TransportStarted` | write `sys.qf.ohu.pid = <pid>`, then `sys.qf.ohu.connect = true` (pid first, so a reader never sees `true` with no pid) | the one state setter `CommManager.connState` (`CommManager.kt:239-243`). A setter, not a flow collector: the state flow is conflated and a collector can miss the transition (comments `CommManager.kt:291-305`) |
| state becomes anything else (disconnect, error, new attempt) | `sys.qf.ohu.connect = false` | the same setter, same call |
| the service stops (ACC off, user stop, settings restart) | `false` | `AapService.onDestroy` and the `ACTION_STOP_SERVICE` path, as a second net |
| process start | `false` and clear pid (drops a value left by the previous process) | `App.onCreate` |
| uncaught exception | best-effort `false`, then the previous handler runs | a default `UncaughtExceptionHandler` installed in `App.onCreate` (none exists today) |
| the process is killed (OOM, `kill`, `pm disable`, force-stop) | **no code runs** → the reader rule decides: the pid is gone, so «down» | readers |
| switch to Z-Link | the reconciler (uid system) disables the OHU package, which kills the process, and **also clears** `sys.qf.ohu.connect` right after as hygiene | the reconciler (design §13.1); this is the only second writer and it only clears |
| reboot | `sys.*` properties reset | system |

So it is never left «up» after OHU dies: orderly paths clear it, disorderly ones are caught by the pid check, a reboot clears it, a
switch clears it.

### 10.3 What stays as it is
- Z-Link keeps its own signals (`com.zjinnova.zlink` broadcast, `sys.qf.carplay.connect`, `sys.qf.bt.aa.connect`); OHU never writes those three.
- The prop pair is documented here and must be added to `qf-platform` `PROPERTIES_SSOT` by whoever owns that canon (head lead); this file is the source until then.
- Not in scope: audio focus rules inside OHU, the in-call dialog; they stay QF BT's, driven by `Projection.active()`.

### 10.4 Proofs (stand, after the gate)
P27 OHU sets/clears the pair as the system app. P28 a real session: `connect=true` and the pid is OHU's; QF BT stops taking focus and showing BT messages
for its duration; Z-Link's A2DP/toast reaction does **not** occur. P29 `kill -9` of OHU mid-session: QF BT reads «down» within its poll interval. P30
switch to Z-Link mid-session: down, nothing stale. P31 reboot: down.

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
| §10 session state (`sys.qf.ohu.connect` / `.pid`) | | |
