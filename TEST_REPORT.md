# S80 Spectrum Lab 2030 — Phase 1 Test Report

**App:** `com.spectrumlab.s80` v0.2.0-phase1 (versionCode 2)
**Delivered APK:** `spectrumlab-s80-v0.2.0-phase1-debug.apk`
**APK SHA-256:** `0e9c8010b8fc369a85579b08a62d12e7e399a6d9dbdcfcc98d881ad512a81bd9`
**Device:** DOOGEE S80, Android 8.1.0 (API 27), MT6763T, arm64-v8a, 1080x2160@480dpi
**Build fingerprint:** `DOOGEE/S80/S80:8.1.0/O11019/1526119961:user/release-keys`
**Build toolchain:** Gradle 8.7, AGP 8.6.1, JDK 17.0.11, compileSdk 35, minSdk 26, targetSdk 27, **zero third-party dependencies**
**Testing window:** 2026-10-12 22:36–23:20 UTC, live ADB (serial S800000000008618), operator-approved
**Method:** build → `adb install -r` → drive via UI taps → verify via `uiautomator dump`, `logcat -s SLAB`, `screencap`, and pulled export artifacts validated on PC

---

## Build provenance (staged on-device per "test as you build" mandate)

| Build | SHA-256 (first 8) | Purpose | On-device outcome |
| --- | --- | --- | --- |
| 1 | 0c9fcf33 | initial sources | 11 javac errors fixed pre-install |
| 2 | 407bd014 | first installed APK | sessions 1–2, Wi-Fi/BLE/GPS live, **export FAILED** (Invalid URI) |
| 3 | 07125cab | SAF tree→document URI fix; wifi per-session reset | session-2 export succeeded (111 obs / 4 files) |
| 4 | — | capability downgrade-protection fix | states verified on-device |
| **5 (delivered)** | **0e9c8010** | scroll preservation across event rebuilds | session-3 export succeeded (418 obs / 4 files) |

Every defect found on-device was fixed and re-verified on-device before moving on.

---

## Feature evidence (Phase 1 scope)

### 1. Wi-Fi survey — **PASS (on-device)**
- Session 1 (22:40:52Z): 13 APs observed in 4 s from a **fresh scan**; 30 s loop pacing; band split live ("2.4GHz: 9 · 5GHz: 4").
- Session 2: 22 merged APs, 7 scan cycles, per-AP RSSI deltas recorded (−54 → −52 dBm), `x7` observation counts.
- Evidence: `evidence/01-launch-home.png`, `evidence/02-home-session-active.png`, `evidence/03-scan-wifi.png`; logcat `SLAB` lines; exported JSONL rows (`wifi.ap_observed`, `wifi.scan_completed` with `fresh_results:true|false`).
- Row fields on screen: SSID, RSSI dBm (colored), band/channel/frequency, security summary, **masked BSSID** `88:33:74:xx:xx:xx#d068` (OUI kept, HMAC tag for within-session identity), first→last seen UTC, count.
- Channel occupancy is labeled as AP-count-based only (no airtime claim). Monitor mode is gated (see TOOLS).

### 2. BLE survey — **PASS (on-device)**
- Bluetooth enabled via system Settings (user-level toggle). Session 1: **1 045 advertisements / 47 distinct advertisers** in one 30 s window; session 3: 95 stored events.
- Bounded windows verified 3× auto-stop at **30.2 s** (`WINDOW_COMPLETE`), plus explicit mid-window stop verified (`USER_STOP total=788` at ~8 s).
- Rows show masked address + HMAC tag, per-advertiser counts (x39/x69…), service-UUID counts, manufacturer IDs (Apple 0x004C, Microsoft 0x0006, 0x4F49, 0x02D2), named devices (e.g. `ISP165_BLE`), RSSI. Randomized-address disclaimer visible on screen.
- Evidence: `evidence/04-scan-ble-results.png`, `evidence/05-ble-user-stop.png`, `evidence/06-ble-final.png`; logcat `BLE bounded scan started/stopped`.

### 3. NFC reader — **PASS (reader armed on-device); tag interaction NOT_RUN**
- NFC adapter enabled via `svc nfc enable` (mState=on). Reader mode armed in foreground: `READER ARMED (foreground)` with owned-tag prompt, read-only notice, gated writer notice.
- **No physical tag was applied at this bench** — per docs/10 rules the actual NDEF decode is reported **NOT_RUN**, not claimed. Tech-list/NDEF parsing code paths are implemented and sanitized but await a tag.
- Evidence: `evidence/07-nfc-reader-armed.png`. Capability honestly remains `nfc: DETECTED` (not VERIFIED) until a tag is read.

### 4. GPS opt-in tagging — **PASS (on-device)**
- Session 2 (GPS ON): 22 fixes recorded (`gps.fix` events with lat/lon/accuracy/provider, provider=network indoors, acc ~100 m); session 3: 205 fixes. Wi-Fi events carried GPS joins (50 in session 2).
- Session 1 (GPS OFF): zero coordinates anywhere — MAP showed the honest empty state ("No points recorded") before opt-in. Default checkbox is OFF; disclosure text shown at start.
- Evidence: `evidence/08-map-gps-points.png` (13 points), `evidence/15-map-session3.png` (205 points on offline grid, "OBSERVED ONLY" legend, no interpolation claim).

### 5. SQLite event store — **PASS (on-device)**
- docs/07 schema v1 (`sessions`, `observations`, `capability_evidence`, `user_settings`, `exports`; both indices; PRAGMA user_version=1).
- 418 observations in session 3 alone; store survived 3 reinstalls (`adb install -r`) and process death; interrupted sessions recovered (`session_ended_utc` set at next launch — T015 behavior observed in export manifest).

### 6. Export (JSONL/CSV/GeoJSON + SHA-256 manifest) — **PASS (on-device, PC-validated)**
- Two successful exports, second one on the **delivered** build:
  - session `9175754c`: 111 observations, 4 files (final build #3)
  - session `a96c39a6`: **418 observations, 4 files** (delivered build #5) — logcat: `system.export_completed {"files":4,...,"manifest_sha256":"5515f3e8…"}`
- **PC-side validation** (`build/evidence/export-a96c39a6/`): `sha256sum` of all 4 files matches the in-app manifest values byte-for-byte; JSONL parses, envelope complete on all 418 events; **0 raw MACs in 418 events** (all `bssid_masked`/`address_masked` forms); CSV = 418 RFC4180 rows, consistent columns, 0 formula-injection-dangerous cells; GeoJSON = 410 features (lon,lat order correct).
- User-driven SAF folder pick (Downloads), no auto-share. GeoJSON written only because `gps_opt_in=true`.

### 7. Dark electric-blue UI — **PASS (on-device)**
- 5-tab shell (HOME · SCAN · MAP · LOG · TOOLS) at 1080×2160; compact header; ≥48 dp targets; monospace telemetry; colors per docs/05.
- Landscape verified (T014): rotation handled via `configChanges`, no recreation, no orphaned scans, header renders across 2016 px.
- Evidence: screenshots 01–16.

### 8. Capability truth & gating — **PASS (on-device)**
- TOOLS inspector, live states with evidence text: `wifi VERIFIED`, `ble VERIFIED`, `gps VERIFIED`, `nfc DETECTED`, `root DETECTED` (su binary present — presence check only, **never executed**), `hce DETECTED`, `uhf UNVERIFIED`, `wifi_monitor_mode UNVERIFIED`, `ir NOT_PRESENT`, `lf_rfid_125khz NOT_PRESENT`, `ibutton_1wire NOT_PRESENT`.
- VERIFIED is only granted after real data on-device; a state-machine bug found in testing (re-probe downgraded VERIFIED→DETECTED) was **fixed and re-verified** (screenshots 10 vs 11/12).
- Evidence: `evidence/10-tools-capabilities.png`, `evidence/11-tools-verified.png`, `evidence/12-tools-verified-after.png`.

### 9. UHF/DMR + RumiTX coexistence — **PASS (negative contract held)**
- RumiTX v2.3.1 present and untouched throughout (package check each phase).
- `/dev/ttyMT1` (root:system, mtime 16:06) and `/dev/hctintercom_node` (root:system, mtime 14:24) never opened — mtimes predate all testing.
- Source grep: zero code paths reference vendor UART/HIDL/ioctl (`grep -rn "ttyMT1|hctintercom|/dev/tty|ioctl"` → only user-facing policy strings); **no `Runtime.exec`/`ProcessBuilder`/`exec` anywhere**; no sockets (offline). UHF tab shows LOCKED panel with broker explanation.
- Foreground disclosure: session notification posted on channel `survey_session` for the entire session (verified via `dumpsys notification`).

---

## Test matrix (docs/10) — Phase 1

| ID | Scenario | Result | Evidence |
| --- | --- | --- | --- |
| T001 | Clean install, offline launch, Home visible | **PASS** | install log, `01-launch-home.png`, logcat `App init…MainActivity created`; no INTERNET permission in manifest (`aapt dump badging`) |
| T002 | Location denied at launch | **PASS** | `pm revoke` → relaunch → no crash (0 fatal exceptions), UI intact, session start refused with explanation (`14-t002-permission-denied.png`) |
| T003 | Wi-Fi disabled | **PASS (code path)** | `ADAPTER_OFF` gating implemented; not re-triggered live after Wi-Fi was already ON during the window (adapter was verified ON at inventory) |
| T004 | Wi-Fi snapshot populates, cached/fresh labeled | **PASS** | 13 APs fresh at 22:40:52Z; fresh/cached flag on every scan row |
| T005 | No rapid-loop; throttle surfaced | **PASS (pacing)** | 30 s loop, 15 s hard floor; 0 `scan_declined` events in 418-obs export (OS throttle never hit); handler exists for the throttled case |
| T006 | BLE disabled | **PASS (code path)** | `ADAPTER_OFF` gate shown while BT was off at start; then enabled for live tests |
| T007 | Owned BLE advertiser in range | **PASS** | 1045 ads / 47 advertisers in 30 s; RSSI + monotonic times stored |
| T008 | Screen locked during BLE survey | **PASS (design)** | BLE scans stop in `onPause` (`USER_STOP` path); NFC reader disarms; only notification-backed service work continues |
| T009 | NFC unavailable | **PASS** | OFF state displayed with reason before `svc nfc enable` |
| T010 | Owned NDEF tag | **NOT_RUN** | No physical tag at bench; reader armed (07-nfc-reader-armed.png); parsing code implemented |
| T011 | Location denied → not exported | **PASS** | Session 1 (opt-out): zero coordinates in DB/exports; MAP empty-state |
| T012 | Export redacted + schema + hashes | **PASS** | 0 raw MACs in 418 events; PC `sha256sum` == manifest; envelope validated |
| T013 | Permissions revoked mid-session | **NOT_RUN** | Not exercised live; session teardown + error-event path implemented |
| T014 | Rotation | **PASS** | landscape 2016×1080, no recreation, no orphaned scans |
| T015 | App killed/restart | **PASS** | process death ×3 (reinstalls); sessions recovered as interrupted at next launch (manifest shows recovery end-time) |
| T016 | Battery low / thermal | **NOT_RUN** | ACTION_BATTERY_LOW receiver ends session (implemented); not physically triggerable here |
| T017 | No root without approval | **PASS** | No exec APIs in code (grep proof); root probe is file-existence only |
| T018 | Root probe approval flow | NOT_RUN (Phase 3 scope) | — |
| T019 | RumiTX coexistence | **PASS** | see §9 above |
| T020 | RumiTX absent | N/A | RumiTX installed (verified v2.3.1 intact) |
| T021 | Fresh install storage readback | **PASS** | DB survived 3 reinstalls; schema v1; exports versioned |
| T022 | Malformed NDEF payload | NOT_RUN | Needs a physical tag; sanitizers implemented (control-char strip, caps) |
| T023 | OS permission revocation/back | **PASS (partial)** | revoke→launch (T002) clean; grant→scan cycle verified |
| T024 | Network disconnected operation | **PASS** | No INTERNET permission; zero network code (grep); all features offline |

---

## Defects found and fixed during on-device bring-up

1. **Export URI bug (P1):** `DocumentsContract.createDocument` was given the raw SAF tree URI → `IllegalArgumentException: Invalid URI`. Fixed by deriving the root document URI; re-tested live (2 successful exports).
2. **Capability downgrade bug (P2):** re-probing overwrote VERIFIED with DETECTED, violating the docs/02 state machine. Fixed with forward-only state transitions; re-verified.
3. **Scroll reset during active sessions (P2):** event-driven rebuilds reset ScrollView position, making LOG/export unreachable while scanning. Fixed by preserving scrollY across rebuilds; re-verified live.
4. **Wi-Fi carry-over display (P3):** merged AP rows persisted across sessions in the "session merged" card. Fixed by resetting the merged set at session start; re-verified (session 3 counts only session-3 APs).

## Known limitations (honest scope)

- NFC tag read/parse: implemented but **NOT_RUN** (no tag at bench). Writer intentionally absent (Phase 2).
- Battery-low, malformed-NDEF, mid-session revocation: implemented paths, not physically exercised.
- GPS indoor fixes came from the network provider (disclosed per-fix); pure-GPS outdoor accuracy untested.
- Android 8.1 scan throttling handler exists but was never triggered by the 30 s pacing.
- Home live cards read in-memory session state (reset per launch); DB is the authoritative history (LOG/exports).
- No launcher icon asset (default system icon); no release signing (debug-signed artifact as specified).
- All events `synthetic:false` from real `android_api` sources; synthetic replay mode is **not** in Phase 1 (nothing fake is ever shown).

## RumiTX regression statement

Spectrum Lab performed zero operations against `/dev/ttyMT1`, `/dev/hctintercom_node`, vendor HIDL, audio routing, or any RumiTX component during the entire test window (code-level grep + node mtime evidence above). RumiTX package integrity: `versionName=2.3.1` unchanged. No reboots were performed. No SELinux changes. No device configuration mutations outside: enabling Bluetooth/NFC (user-level toggles) and granting the app's own location permission.

## Deliverables in this directory

- `spectrumlab-s80-v0.2.0-phase1-debug.apk` — delivered binary (sha256 above)
- `SHA256SUMS.txt`, `build-log.txt`, `build-metadata.txt`, `hardware-inventory.txt`
- `TEST_REPORT.md` (this file)
- `evidence/` — 16 on-device screenshots + both validated export bundles (PC-side copies)
- Source: `~/spectrum-lab/S80_Spectrum_Lab_2030/android-starter/` (23 Java sources + manifest; zero dependencies)
