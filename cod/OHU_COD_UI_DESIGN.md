# Open Headunit on CoD OS: the three-button behaviour copy of Z-Link — design draft 1

Author: Open Headunit session, 24.11.2026. For the head lead's gate. **Design only: nothing built from this, nothing installed, stand 198 untouched.**
Marks: 🔬 read in code or resources, 📻 measured, 🧩 inferred, ❓ not verified.

## 0. What the owner asked (this chat, 24.11, replacing the head lead's relay)

Head lead's relay: «коли сесія зробить з нього копію Zetlink, який запускається 2 кнопками … в інтерфейсі буде мати хіба 2 кнопки, як Zetlink, от тоді я її пущу на стенд».
**The owner then corrected it himself, to me:** «Мені не потрібна точна копія. Я мав на увазі копія по поведінці. Ти можеш залишити інтерфейс такий, як він зараз є. Просто там
має бути кнопка **Android Auto, CarPlay, Connect**, а не 100 500 налаштувань, які є в оригіналі.» So:
- a **behavioural** copy of Z-Link, not a visual one; the current OHU look stays;
- the main screen has **three buttons: Android Auto, CarPlay, Connect**;
- the many settings go under a developer section; the person never meets them.
(The earlier «two buttons = USB vs Wi-Fi» of contract §0 is replaced: the transport is not a choice, as with Z-Link; see §3.)

## 1. Reference: how Z-Link behaves (stock zlink5 6.1.06, resources of the unpacked APK; no live screens, the stand is frozen)

- **Its home** (`layout/fragment_main_ui.xml`): a title bar (home, help, the car's Bluetooth name and pairing code), up to four link tiles (`link_1…link_4`, filled at run time
  from the enabled features), and a bottom bar: the protocol selector (`first_group` = CarPlay, `assistant_group` = «ANDROID AUTO»), settings, «more». Settings and help are secondary
  pages (`activity_settings`, `activity_help`, `fragment_help_aa/cp/cl/hi/mr`).
- **Its behaviour is the point:** the person does not choose a transport. The help text says it: «1 Connect your phone to the car USB cable or via the car's Bluetooth.»
  (`aa_step1`, `cp_step1`). A cable phone and a Bluetooth/Wi-Fi phone are both taken as they come; what the person picks is **which protocol** the unit shows (CarPlay or Android
  Auto), and Z-Link then opens its own screen for it when the phone connects.
- ❓ A photo or screenshot of the live Z-Link home on the owner's unit would settle the visual reference; not needed for a behavioural copy.

## 2. Today's OHU home (🔬 `res/layout/fragment_home.xml`, `main/HomeFragment.kt`)

Left bar: `projection_button` (one tile that toggles Android Auto ⇄ CarPlay, `HomeFragment.kt:2000-2008`), `usb_button`, `wifi_button`, `self_mode_button`, `bluetooth_button`
(hidden), `settings_button` (`:2014`), `exit_button` (`:1997`), `sidebar_apps_button` (hidden). Main area: `home_pager` with page 1 = `view_home_primary_card` (the state and the
**primary button**, `primaryAction` → `onPrimaryActionClicked`, `:2098`) and `view_home_favorites`; more pages of widgets and favorites; page dots. The card's button is
the one Connect.

## 3. Target: the CoD home (codos only; the other flavors unchanged)

| element | codos |
|---|---|
| **Android Auto** button | new, left bar: a tile for the protocol, highlighted when it is the mode; replaces the toggle tile's AA half |
| **CarPlay** button | new, left bar: the other half. **Shown only where CarPlay can work** (§5); otherwise absent, never a dead button |
| **Connect** | the existing primary card button (`primaryAction`) and its state line, big, centre. It is what starts the connection (§4) |
| `usb_button`, `wifi_button`, `self_mode_button`, `bluetooth_button`, `sidebar_apps_button` | **hidden**: the transport is not a choice (`Settings.connectionModes` is fixed to USB + Wi-Fi already, commit f190585a); Self Mode does not exist on CoD |
| `settings_button` | **hidden** from the person; the settings open only through the developer gesture below |
| `exit_button` | kept (how a person leaves); a decision for the owner if he wants it gone too |
| favorites, widgets, extra pages, page dots | **hidden**: nothing but the card on the home |
| developer section | `SettingsActivity` as it is today (every one of the settings, 100 500 of them), reached by **7 taps on a small version label** on the card (contract §2). Nothing in it is deleted |

Implementation shape (small, flavor-gated, no deletion): `BuildConfig.CODOS` branches in `HomeFragment` (`:232-240` view lookups, `:1995-2020` listeners, the bar and card render),
`fragment_home.xml` gains the two protocol tiles (hidden in other flavors), a `CodosHomePolicy` (pure, tested) decides which tiles are visible and which is selected, and the
version-label gesture lives in one small helper. The existing `ProjectionSwitcher`, `HomePrimaryPolicy` and the settings stay as they are.

## 4. What each button starts (every occurrence)

**Android Auto button** → `ProjectionSwitcher.set(ctx, ANDROID_AUTO)` (`zlinkcarplay/ProjectionSwitcher.kt`): stores `settings.projectionMode`; ends a CarPlay session
(`ZlinkHub.endSession(USER)`); on the WIFI_DIRECT strategy only, brings the station Wi-Fi up (`svc wifi enable` through `OemAppManager`, the system shell on CoD) — the pinned
strategy is HOTSPOT (`App.kt`), so this is skipped; `ZlinkService.sync` stops the CarPlay service. **Nothing connects.** Not a start by itself.

**CarPlay button** → `ProjectionSwitcher.set(ctx, CARPLAY_ZLINK)`: stores the mode; `ZlinkHub.holdOffUntilConnect()` (no auto-connect); stops the Android Auto service
(`AapService ACTION_STOP_SERVICE`, `EXTRA_KEEP_HOME`); `ZlinkService.sync` **starts** the CarPlay service (foreground service, `ZlinkProxy` on 11666…11999, the redirect rules, the
Z-Link UI hide, `DaemonService` start; `ZlinkService.kt:44-80`). Needs Z-Link's daemon and the app alive and the iptables redirect (root) — §5.

**Connect** → `HomeFragment.onPrimaryActionClicked` (`:2098`):
1. CarPlay mode: `openCarPlayIfSelected` (`:2046`): `PhoneKindGate.checkBeforeCarPlayConnect` (one read of the Bluetooth module), `ZlinkHub.resumeOffer(force)`, `ZlinkCarPlayActivity`.
2. Session up: `openProjection()` (back to the running projection).
3. Android Auto, by `decision.primary` (`HomePrimaryPolicy`): **USB** → `onUsbClicked` (`:2174`): a cable phone present → `MainActivity.beginAutoConnect` → `AapService` USB connect;
   none → waits for attach. **Wi-Fi** → `onWifiClicked` (`:2263`): with `wifiConnectionMode` pinned NATIVE (`App.kt`) the wireless stack is armed: `NativeAaHandshakeManager`
   (the Bluetooth module carrier `zbt/ZbtAaCarrier` over `gocsdk_zj` 127.0.0.1:3152 or the Android radio), the unit's hotspot (`EarlyHotspot`, `HotspotManager`), the join and the handshake.
   `decision.primary` is chosen by the policy from what is present, so the person never picks it.

**Without any button** (as with Z-Link, which just waits for a phone): `BootCompleteReceiver` (boot), `AccOnReceiver` (ACC on, process-level), `UsbAttachReceiver` (cable plugged,
process-level), `AutoStartReceiver` (Bluetooth ACL), `WifiAutoStartReceiver`; the wireless stack arms itself; a phone that appears connects. Connect is for the person who wants to
start it now.

Whatever a button starts, **the session announcement of contract §10** goes out when the handshake ends (the Z-Link broadcast), and nothing else is written for it.

## 5. The CarPlay button depends on the projection switch

CarPlay in OHU is **carried by Z-Link's daemon and the Z-Link app** (OHU is a proxy between them, `ZlinkProxy.kt:72-100`, design `PROJECTION_SWITCH_DESIGN.md` §11, §14), and the redirect it needs is root
today (`ZlinkRedirect`, disabled in `codos`). So whether the button can exist depends on the model the owner decides:
- **Model A (shipping default, §13):** Z-Link is disabled while OHU is selected → **no CarPlay in OHU mode**. The CarPlay button is **absent** (`projectionAvailability` = Android Auto only). The owner's own
  condition (a CarPlay button) is then met only by selecting Z-Link, which has CarPlay.
- **Model B/C (§11, §14):** Z-Link's daemon (and for B the app) stay alive; the redirect becomes a root init oneshot or OHU serving the ports itself; the button appears (`projectionAvailability` BOTH).
**Decision D5 for the owner:** he now asks for a CarPlay button in OHU. That is a request for model B or C. Recommendation: build the three-button home now with the CarPlay button
gated by `projectionAvailability` (already in Volodymyr's code), ship Android Auto only, and turn CarPlay on when the redirect and the Z-Link side are proven on a unit.

## 6. Writes to «our system settings»: inventory and proposal (🔬 grep of `app/src/main/java`)

No `Settings.System/Secure/Global.put`, no `setprop`, no property writes (the contract §10 uses a broadcast now). What OHU does change in the unit, and what to do:

| # | write | where | proposal |
|---|---|---|---|
| 1 | the unit's **hotspot configuration** (name, password, band) | `HotspotConfigTool` `setWifiApConfiguration` (`tools/HotspotConfigTool.kt:56,66`); `HotspotConfigClient.set` (a settings row); **`ensure5Ghz` runs at every start** (`App.kt`, 20 s after start) | **remove `ensure5Ghz` in codos** (it rewrites the system hotspot on every start); the hotspot's band is the system's. Keep a name/password row only in the developer section, off by default |
| 2 | the hotspot **on/off** | `HotspotManager.setHotspotEnabled`, `EarlyHotspot`, `SoftApCredentialsProvider:223` | **keep**: it is what wireless Android Auto needs, as with Z-Link; it is a function, not a setting |
| 3 | the **station Wi-Fi** on/off | `HomeWifiStatus.kt:265`, `ProjectionSwitcher.kt:59` (`svc wifi enable`), `WifiStackRecovery`, `P2pStackWedgePolicy` | keep only the recovery paths in the developer section; drop the `ProjectionSwitcher` call in codos (the strategy is pinned HOTSPOT) |
| 4 | the **Bluetooth adapter** on/off | `NativeAaHandshakeManager.kt:1261,1999,2011` (the radio-cycle recovery) | disabled in codos: the unit's Bluetooth is QF Bluetooth's (the module), not ours to cycle |
| 5 | **Z-Link's components** enabled/disabled | `ZlinkScreenPolicy`, `OemAppManager` (`pm disable-user`) | not used in model A; in B/C the reconciler owns it (design §13/§11): OHU does not |
| 6 | the **volume / audio mode** | `setMode 3/0` and `setCommunicationDevice` only (mic contract §5) | keep: part of a call, restored after |
All the writes in rows 1, 3, 4 are removed or confined to the developer section by a `BuildConfig.CODOS` branch; row 2 stays. ❓ The owner's phrase «відучити лізти в наші системні настройки» is read as this list; if he meant something else (a CarSettings row), tell me which.

> **Revision 24.11 (sound line, MIC_CALIBRATION_CONTRACT §5):** 48 kHz is NOT the default. Our 3:1 conversion is a 3-sample mean, not the required low-pass (stopband at least 60 dB from 8 kHz), so the CoD default stays 16 kHz; NS stays on by default; no own `ns` on VOICE_RECOGNITION; no second capture in a unit call (`sys.qf.call_state`); VOICE_COMMUNICATION calls are held until C15. Built in commit 650ea9dbd. The plan text below is the earlier one.

## 7. Noise suppression on CoD OS (the earlier owner requirement, `OWNER_REQUIREMENTS_OHU.md`)

Facts (🔬 mic contract §5, helper 2, on 198): OHU announces a mono **16 kHz** capture (`MicCaptureFormat.SAMPLE_RATE_HZ`; Android Auto accepts 16 000 or 48 000, `ServiceDiscoveryResponse.kt:236`);
its NS is the platform `NoiseSuppressor` attached to the capture session (`MicRecorder.kt:420`), controlled by a **pref that is off by default** (`settings.micNoiseSuppressor`); the live
`audio_effects.xml` defines `ns` for `mic` and `voice_communication`, so the effect exists; AGC and AEC are «Unsupported» by design (the call AEC lives in the AGDSP).
So «not active» is the **pref being off**, plus the 16 kHz format; the platform supports it.
Plan (codos defaults, Layer 2 only, **no mic gain is ever written**): `micNoiseSuppressor = true`; capture announced at **48 kHz** (the option exists); source `VOICE_COMMUNICATION` during a
call, `VOICE_RECOGNITION` for the assistant (contract §5); read BitPerfect's published calibration, never write it. ❓ Measure on a unit that `ns` attaches and the result is audibly better at 48 kHz (P34).

## 8. Proofs and what is not done

P34 NS attaches and is on at 48 kHz in a call and for the assistant. P35 the home shows Android Auto, (CarPlay when available), Connect and nothing else; the settings open only
through the gesture. P36 a cable phone connects with no button (attach) and with Connect; a wireless phone the same; both announce per contract §10. P37 no hotspot configuration
is rewritten at start (rows 1). P38 CarPlay button absent in model A, present in B/C.
**Not done:** no UI code, no build for this design. The §10 broadcast and the pinned connection types are already in the tree (f190585a) and in the module being built.

## 9. Decisions and a request

- **D5** the CarPlay button: gated by the model (§5); recommendation as above.
- **D6** is the `exit` button wanted on the CoD home?
- The head lead's gate on §3 (home elements) and §6 (the write list) before any UI code.
