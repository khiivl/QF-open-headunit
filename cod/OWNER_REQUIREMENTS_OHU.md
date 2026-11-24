# Owner's future requirements for Open Headunit on CoD OS

Recorded 24.11.2026 on the head lead's relay. **No work now**: OHU stays disabled on stand 198 until a projection
switch rule exists. Volodymyr owns the app; items 1-3 are for a later session, probably on his side or a dedicated one.

Owner, verbatim:

> «у нас в системі ще тепер є хур … Він теж бере мікрофон. Для дзвінків у машині. І для свого власного асистента …
> бере він його 16 кГц. Хоча можна перемкнути на 48, і там теж є повзунок шумозаглушення, але чомусь він каже, що на
> цій системі він не активний і не може включити його. По-перше, його треба навчити цьому, щоб він його включав. І
> використовував. А по-друге, відучити, лізти, у наші системні настройки. Взагалі той хур треба буде, якусь сесію
> колись відкрити, і переписати нафіг під нашу систему таким чином, щоб у людини лишилося 2 кнопки. Як в Z-Link.»

## Items

1. **Noise suppression (NS) must work on CoD OS.** Find why OHU reports NS as «not active» here, teach it to switch NS
   on and use it, with the mic at 48 kHz instead of 16 kHz. It sits on top of BitPerfect's mic calibration:
   contract `C:/APPS_Contacts/wDSP--BitPerfect-A14/MIC_CALIBRATION_CONTRACT.md`. OHU is a **Layer-2 consumer: it never
   writes mic gains.**
2. **List and remove every write OHU makes to our system settings** (`Settings.System/Secure/Global` writes, system
   properties, anything that changes the unit's configuration). Start with the list; nothing is removed before the
   owner sees it.
3. **Later: a UI with two buttons, like Z-Link** (rewrite for our system). Volodymyr's UI work; see the contract.
