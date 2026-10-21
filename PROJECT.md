# Project: DLivePTApp (Daniel Streams) Audit and Remediation

## Architecture
- **Language / Platform**: Kotlin 1.9.24, Android SDK 34 (minSdk 24, targetSdk 34), Jetpack Compose (BOM 2024.05.00), Gradle 8.6, AGP 8.4.1, JVM 17.
- **UI Architecture**: Dual TV & Mobile UI: Jetpack Compose for `HomeScreen` / `MainActivity` / `EpgDetailPanel`, Android Views (`activity_player.xml`) for `PlayerActivity`.
- **Playback Architecture**: Exclusively `WebView`-driven (`PlayerActivity.kt`). No ExoPlayer. Uses embedded `hls.js` with `loadDataWithBaseURL` for direct M3U8 feeds, and iframe sandbox attribute neutralization for third-party embeds (DaddyLive, TimStreams, EpicSports).
- **Stream Providers**:
  - DaddyLive Player 1 (`dlive.sx/stream/`) -> upstream `dembed.st`
  - DaddyLive Player 2 (`dlive.sx/player/`) -> upstream `wideiptv.top`
  - TimStreams 1080p -> `ehgembxe.cfd` with HTTP 302 JWT play token redirect
  - EpicSports -> `epicsports-tv.com` with mandatory Referer header
  - Official Portuguese M3U8s -> RTP, SIC (CORS: `https://sic.pt`), Canal 11 (CORS: `https://canal11.pt`), Porto Canal, TVI/CNN (IOL tokens)

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Catalog Census & Endpoint Discovery | Survey and map 100% of 733 channels and ~1568 endpoints in `channels.json` | M1 | Survey / R1 |
| 2 | Automated Stream Connectivity Audit Tool | High-speed (25 workers) Python audit script testing all primary streams and fallbacks | M1 | Survey / R1 |
| 3 | TimStreams 302 & Domain Verification | Verify active domain `ehgembxe.cfd` and 302 JWT redirect; purge dead `grandemx.org` mirror | M1 | Survey / R1 |
| 4 | DaddyLive Player 1 & 2 Upstream Audit | Validate both reproduction pages and embedded upstream CDNs (`dembed.st`, `wideiptv.top`) | M1 | Survey / R1 |
| 5 | Official M3U8 CORS & Referer Validation | Verify origin CORS headers (`https://sic.pt`, `https://canal11.pt`) and Referers | M1 | Survey / R1 |
| 6 | Consolidated Stream Audit Report | Generate Markdown and JSON audit matrices covering 100% channels and fallbacks | M1 | Survey / R1 |
| 7 | Dynamic Iframe Sandbox Neutralization | Strengthen DOM overrides (`setAttributeNS`, `toggleAttribute`, `iframe.src = iframe.src`), add `onPageStarted` fallback | M2 | Survey / R2 |
| 8 | High-Performance Ad-Blocking | Pre-allocated static `AD_BLOCK_PATTERNS` set, expanded to 50+ sports-stream ad/betting networks | M2 | Survey / R2 |
| 9 | Strict URL Whitelist & Navigation Guard | Eliminate wildcard TLD allowances (`.top`, `.sbs`, etc.), block malicious schemes and subframe redirects | M2 | Survey / R2 |
| 10 | Render Process Crash Loop Resilience | Replace unconditional `recreate()` in `onRenderProcessGone` with crash counter and failover advancement | M2 | Survey / R2 |
| 11 | Complete Lifecycle Memory Leak Prevention | Fix `onDestroy()` cleanup (remove JS interface, detach WebView, cancel animators, dismiss dialogs) and Compose `onRelease` | M2 | Survey / R2 |
| 12 | Failover Priority Inversion Fix | Fix `MainActivity` and `PlayerActivity` to prioritize official M3U8 streams ahead of DaddyLive for numeric channels | M3 | Survey / R3 |
| 13 | TV Virtual Keyboard Suppression | Gate `etDrawerSearch.setOnFocusChangeListener` behind `!isTv` to prevent intrusive keyboard popups on D-pad navigation | M3 | Survey / R3 |
| 14 | Google TV High-Contrast Focus Visuals | Add focus indicators to Category filter pill in `HomeScreen`, buttons in `EpgDetailPanel`, and block focus on preview WebView | M3 | Survey / R3 |
| 15 | Dynamic Failover HUD Feedback | Update connecting overlay status dynamically during failover instead of static text or clipped Toasts | M3 | Survey / R3 |
| 16 | Gradle Release Build & Lint Guardrails | Configure `lint { abortOnError = false }` in `app/build.gradle.kts`, verify `./gradlew assembleRelease` exit code 0 | M4 | Survey / R4 |
| 17 | Opaque-Box E2E Test Suite | Comprehensive 4-tier requirement-driven test suite verifying streams, security, UI, and compilation | E2E | Dual Track |

## Milestones
| # | Name | Scope | Dependencies | Status | Key Outputs |
|---|------|-------|-------------|--------|-------------|
| M1 | Stream Audit & Catalog Tooling | Script `scripts/audit_channels_r1.py`, 100% channel & fallback testing, report generation, dead mirror cleanup | none | DONE | `scripts/audit_channels_r1.py`, `build/reports/stream_audit_report.json`, `build/reports/stream_audit_report.md`, `ChannelRepository.kt` mirror cleanup |
| M2 | Security, WebView & Sandbox Hardening | Hardened dynamic sandbox, strict whitelist, ad-block expansion, crash resilience, memory leak fixes | none | DONE | `PlayerActivity.kt` hardened, `EpgDetailPanel.kt` onRelease, clean renderer recovery |
| M3 | TV UX, D-Pad & Failover Inversion | Priority inversion fix, TV soft keyboard popup elimination, high-contrast focus visuals, HUD feedback | M2 | DONE | `MainActivity.kt` (priority routing), `PlayerActivity.kt` (failover list & HUD), `HomeScreen.kt` (pill focus), `EpgDetailPanel.kt` (button focus & WebView isolation) |
| M4 | Integration & assembleRelease Verification | Gradle lint guardrails, end-to-end integration, `./gradlew assembleRelease` verification | M1, M2, M3 | IN_PROGRESS | `app/build.gradle.kts`, release APK |
| E2E | E2E Testing Suite Track | Independent requirement-driven test harness and test cases (Tiers 1-4), publish `TEST_READY.md` | none | DONE | `TEST_INFRA.md`, `TEST_READY.md`, `tests/e2e/` (71 tests passing) |
| M-FINAL | Final Verification & Adversarial Hardening | Phase 1: 100% E2E test pass; Phase 2: Tier 5 adversarial coverage hardening | M4, E2E | PLANNED | Verified release product |

## Interface Contracts
### `scripts/audit_channels_r1.py` ↔ Application & Reports
- **Input**: `app/src/main/assets/channels.json`
- **Output Report**: `build/reports/stream_audit_report.json` and `build/reports/stream_audit_report.md`
- **Exit Code**: 0 if audit completed and report produced.
- **Report Schema**:
  - `total_channels`: 733
  - `total_endpoints`: ~1568 (actual: 1535)
  - `status_summary`: `{ "ONLINE": count, "DEGRADED": count, "CRITICAL": count }`
  - `channels`: array of `{ id, name, category, primary_status, fallback1_status, fallback2_status, overall_status }`

### `MainActivity.kt` ↔ `PlayerActivity.kt` Intent Contract
- `EXTRA_DIRECT_STREAM_URL`: String? — If channel has an official M3U8 broadcast (`.m3u8`, RTP, SIC, TVI, Canal 11) or TimStreams, MUST pass direct URL regardless of whether channel ID is numeric.
- `EXTRA_CHANNEL_ID`: String — Channel identifier.
- `EXTRA_CHANNEL_NAME`: String — Channel name.

### `PlayerActivity.kt` ↔ `activity_player.xml` UI Contract
- `tvConnectingStatus`: TextView — Updated dynamically on failover to reflect active server transition.
- `etDrawerSearch`: EditText — Focus listener must not request soft keyboard when `isTv == true`.

## Code Layout
- `app/src/main/assets/channels.json` — Master channels catalog (733 channels).
- `app/src/main/java/com/dlive/ptstream/MainActivity.kt` — Channel launch & intent configuration.
- `app/src/main/java/com/dlive/ptstream/ui/screens/PlayerActivity.kt` — Video playback, WebView, failover, D-pad events, security handlers.
- `app/src/main/java/com/dlive/ptstream/ui/screens/HomeScreen.kt` — TV grid, Category pills, Live Event cards, Focus borders.
- `app/src/main/java/com/dlive/ptstream/ui/screens/EpgDetailPanel.kt` — EPG right panel, action buttons, preview WebView.
- `app/src/main/java/com/dlive/ptstream/data/ChannelRepository.kt` — Channel loading, mirror configs, preferences.
- `app/build.gradle.kts` — App-level build configuration, signing, lint options.
- `scripts/audit_channels_r1.py` — Requirement R1 automated stream audit tool.
- `tests/e2e/` — Requirement-driven opaque-box E2E test suite.
