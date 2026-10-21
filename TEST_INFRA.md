# Test Infrastructure Specification: DLivePTApp (Daniel Streams)

## 1. Test Philosophy & Principles

The DLivePTApp test infrastructure is designed under a **requirement-driven, opaque-box (black-box) testing methodology** derived directly from `ORIGINAL_REQUEST.md` and `PROJECT.md`. It verifies system behavior against functional specifications, protocol contracts, security guarantees, and user experience requirements.

### Core Tenets:
1. **Opaque-Box Contract Verification**: Tests evaluate system inputs and observable outputs (network responses, HTTP status codes, CORS headers, JavaScript DOM modifications, build artifacts, and intent extras) rather than private internal implementation details.
2. **Non-Destructive Execution**: Tests and audit runners must never alter the master channels catalog (`channels.json`) or modify runtime configuration in place.
3. **High Real-World Fidelity**: Live upstream servers (DaddyLive, TimStreams, EpicSports, RTP, SIC, TVI, Canal 11) and actual build toolchains (Gradle 8.6, AGP 8.4.1, Java 17/21) are exercised to ensure genuine end-to-end viability.
4. **Adversarial & Boundary Rigor**: Inputs include malformed URLs, missing properties, cross-origin DOM attacks, rapid remote control zapping bursts, and renderer crash simulations.
5. **Deterministic Isolation**: Every test is self-contained, initializes its required state, enforces explicit timeouts (avoiding indefinite network hangs), and cleans up all processes and temporary files.

---

## 2. Feature Inventory Matrix

| Feature ID | Feature Name | Requirement Source | Target Component | Verifiable Invariants |
|---|---|---|---|---|
| **FEAT-STREAM-AUDIT** | Stream Connectivity & Catalog Census | R1, PROJECT #1-#6 | `channels.json`, Stream Endpoints | 100% of 733 channels mapped; TimStreams 302 redirect verified; DaddyLive Player 1 & 2 verified; EpicSports & M3U8 CORS/Referer verified. |
| **FEAT-SANDBOX-OVERRIDE** | Dynamic Iframe Sandbox Neutralization | R2, PROJECT #7 | `PlayerActivity.kt` JS Injections | Interception of `setAttribute`, `setAttributeNS`, `toggleAttribute`; masking via `hasAttribute`/`getAttribute`; MutationObserver removal & iframe reload. |
| **FEAT-AD-BLOCKING** | High-Performance Ad-Blocking & Guard | R2, PROJECT #8-#9 | `PlayerActivity.kt` `shouldInterceptRequest` | 50+ ad and betting patterns; zero-byte JS response; pre-allocated static hash set; strict URL whitelist blocking malicious TLDs and external schemes. |
| **FEAT-TV-FOCUS** | Google TV D-Pad UX & Focus Navigation | R3, PROJECT #13-#15 | `HomeScreen.kt`, `PlayerActivity.kt`, `EpgDetailPanel.kt` | Soft keyboard suppression on TV; high-contrast focus indicators; D-pad key mappings (zapping, drawer, HUD); non-focusable media player surface. |
| **FEAT-ASSEMBLE-RELEASE** | Release Build & Packaging Guardrails | R4, PROJECT #16 | `app/build.gradle.kts`, Gradle Toolchain | Clean `./gradlew assembleRelease` exit code 0; signed APK artifact generation; ProGuard/R8 retention rules. |

---

## 3. Test Architecture: 4-Tier Hierarchy

The test suite is organized into four distinct tiers under `tests/e2e/`:

```
tests/e2e/
├── fixtures/
│   ├── mock_channels.json
│   ├── mock_hls_manifests.py
│   └── dom_sandbox_harness.js
├── tier1_feature_coverage/
│   ├── test_feature_stream_audit.py          (>= 5 test cases)
│   ├── test_feature_sandbox_overrides.py     (>= 5 test cases)
│   ├── test_feature_ad_blocking.py           (>= 5 test cases)
│   ├── test_feature_tv_focus.py              (>= 5 test cases)
│   └── test_feature_assemble_release.py      (>= 5 test cases)
├── tier2_boundary_corner/
│   ├── test_boundary_malformed_urls.py       (>= 5 test cases)
│   ├── test_boundary_empty_channels.py       (>= 5 test cases)
│   ├── test_boundary_cross_origin_iframe.py  (>= 5 test cases)
│   ├── test_boundary_rapid_zapping.py        (>= 5 test cases)
│   └── test_boundary_renderer_crash.py       (>= 5 test cases)
├── tier3_cross_combinations/
│   └── test_cross_combinations.py            (>= 5 pairwise combination test cases)
├── tier4_realworld_scenarios/
│   └── test_realworld_scenarios.py           (>= 5 real-world application test cases)
└── run_all_e2e.py                            (Unified CLI runner)
```

### Tier Breakdown:
- **Tier 1 (Feature Coverage)**: Validates standard primary behavior (happy path) for each of the 5 core project features with >=5 dedicated tests per feature (total >= 25 tests).
- **Tier 2 (Boundary & Corner Cases)**: Stress-tests boundary values, malformed inputs, edge conditions, resource constraints, and error recovery with >=5 dedicated tests per category (total >= 25 tests).
- **Tier 3 (Cross-Feature Combinations)**: Evaluates pairwise interactions between features (e.g. failover during active ad-blocking, D-pad navigation during stream stall, CORS origin headers with HLS baseUrl).
- **Tier 4 (Real-World Application Scenarios)**: Simulates authentic user scenarios (Portuguese national channel priority playback without 9-second delay, DaddyLive failover to Player 2, TimStreams 302 token resolution, full release build packaging).

---

## 4. Test Execution & Coverage Thresholds

### Execution Commands:
- **Run Full E2E Test Suite**:
  ```pwsh
  python tests/e2e/run_all_e2e.py
  ```
- **Run Specific Tier**:
  ```pwsh
  python -m unittest discover -s tests/e2e/tier1_feature_coverage -p "test_*.py"
  python -m unittest discover -s tests/e2e/tier2_boundary_corner -p "test_*.py"
  python -m unittest discover -s tests/e2e/tier3_cross_combinations -p "test_*.py"
  python -m unittest discover -s tests/e2e/tier4_realworld_scenarios -p "test_*.py"
  ```
- **Run Single Test Module**:
  ```pwsh
  python -m unittest tests/e2e/tier1_feature_coverage/test_feature_stream_audit.py
  ```

### Coverage Thresholds & Guardrails:
1. **Feature Coverage**: 100% of defined features (R1 through R4) must have active tests in Tier 1.
2. **Test Count Minimums**:
   - Tier 1: Minimum 5 tests per feature (Total >= 25 tests).
   - Tier 2: Minimum 5 tests per boundary domain (Total >= 25 tests).
   - Tier 3: Minimum 5 cross-feature interaction tests.
   - Tier 4: Minimum 5 real-world end-to-end scenario tests.
   - Overall Suite: >= 60 distinct automated test cases.
3. **Execution Exit Code Contract**:
   - Return code `0`: All tests passed cleanly.
   - Return code `1`: One or more test assertions failed or errored.
4. **Timeout Thresholds**:
   - Individual HTTP probe timeout: `<= 5000ms`.
   - Node DOM simulation timeout: `<= 3000ms`.
   - Gradle build timeout: `<= 60000ms`.
