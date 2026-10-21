# Partner Accountability — Cross-OEM Compatibility Report (2026-10-22)

Plan: `app/plans/partner-accountability-event-delivery-implementation-plan.md` §7 Step 11, §12 "Cross-OEM physical-device matrix" and "Security and policy tests", §13 release requirements.

Scope: the monitoring/heartbeat components, WorkManager jobs, FGS piggyback, push integration, and partner UI shipped in Steps 6–10. No manufacturer-specific code was added — the plan permits it only after a reproducible incompatibility is demonstrated, and none was found.

## Devices

### Physical: Infinix X6532 (Transsion / XOS) — `TESTED`

| Field | Value |
|---|---|
| Manufacturer / model | INFINIX / Infinix X6532 |
| Build | `X6532-V632AABCDEFGHIJKLMNO-UGo-OP-260710V3278` |
| Fingerprint | `Infinix/X6532-OP/Infinix-X6532:14/UP1A.231005.007/260710V3278:user/release-keys` |
| OS / API | XOS on Android 14 / API 34 (minSdk 26, targetSdk 36) |
| Security patch | 2026-08-01 |
| GMS | present — 26.39.33 (200300-995470952) |
| Users | one profile (user 0) |
| Network | Wi-Fi `MUSLA608` (primary) + LTE; full-tunnel app VPN `tun0`, owner uid 10414 |
| App | com.example.myexodus 1.0 (versionCode 1), debug APK built 2026-10-22 01:39 EAT from the current source; two in-place updates this session (00:57 → 01:06:52, then 01:39 → 01:40:19, `MY_PACKAGE_REPLACED` both times) |
| Permissions | POST_NOTIFICATIONS granted; accessibility service enabled; usage access allow; display-over-other-apps allow; battery-optimization allowlisted; FGS `specialUse` foreground (`protection_status`) |

### Supplementary: API-37 emulator — `PARTIALLY TESTED` (GMS-free leg only)

| Field | Value |
|---|---|
| Model | `sdk_gphone64_x86_64` |
| OS / API | Android 16 / API 37 |
| GMS | installed but `disable-user` for the no-GMS leg; re-enabled afterwards |
| Network | emulator NAT |

Emulators supplement; they do not replace physical testing for OEM background restrictions (§12).

## Matrix legs and evidence

| Leg | Result | Evidence |
|---|---|---|
| App update (in-place, `install -r` over a running process) | PASS | pid 32261 → 3770 (00:57 build) → 17836 (01:39 build from final source, installed 01:40:19); BootReceiver logged `MY_PACKAGE_REPLACED` both times; EnforcementForegroundService `isForeground=true`, `types=specialUse`; accountability DB `user_version` = 14 before and after; standby bucket `EXEMPTED`; no `AndroidRuntime` FATAL |
| Process death (SIGKILL of the app process) | PASS | pid 3770 → 4276 within 12 s (START_STICKY + ensure-FGS on process start, ADR #152); FGS foreground again; DB header still v14 |
| Explicit force-stop | PASS (expected best-effort) | after `am force-stop`: pid empty, 0 services — the platform's intended stopped state; relaunch → pid 5013, FGS foreground; no crash. No claim is made that the app self-restarts after a user force-stop |
| Permission revocation (POST_NOTIFICATIONS) | PASS | revoke → `granted=false`, AppOps uid mode `ignore`; process restarted cleanly (pid 5013 → 5810), no FATAL; grant restored → `granted=true`. The AVD leg additionally captured the exact user-facing banner (below) |
| Notifications disabled (visual, AVD) | PASS | Partner tab renders "Notifications are off" / "MyExodus can't tell you when new partner activity arrives. Allow notifications to get these alerts." + "Allow notifications" action |
| App Standby buckets (API 28+) | PARTIAL — platform clamp | baseline bucket 5 (EXEMPTED). With the allowlist removed, `am set-standby-bucket rare` and `restricted` both read back 10 (ACTIVE) while the app ran and while force-stopped, and no `STANDBY_BUCKET_CHANGED` event was recorded for the package. This matches AOSP semantics: a running foreground service keeps the app in the Active bucket, and the debug override is clamped. The startup diagnostic logged `standby bucket=ACTIVE`, and `EXEMPTED` after the allowlist was restored. Behavioral RARE/RESTRICTED rendering is covered by JVM tests, not by this device |
| Battery saver | PASS | device charging, so the test used `dumpsys battery unplug`; `cmd power set-mode 1` → `mSettingBatterySaverEnabled=true`; FGS stayed foreground; restored to mode 0 and `dumpsys battery reset` |
| Doze — with user-granted battery exemption | PASS | `dumpsys deviceidle force-idle` → deep `IDLE`; adb, process and FGS alive; server `dms_install_state` accepted counter 36 at 22:10:20Z and 37 at 22:15:49Z, `next_deadline_at` = +6 h. Heartbeats continue while exempted |
| Doze — without exemption | PASS (finding recorded) | deep `IDLE`, allowlist removed, process killed → no restart within 30 s (no maintenance window; START_STICKY cannot run while the app is subject to idle restrictions). On `unforce` + allowlist + launch: pid 9262, FGS foreground, a full sync pass ran, standby read `EXEMPTED`. No app-side defect; the server-side DMS `LATE`/`NOT_CHECKING_IN` deadlines are the designed backstop |
| Network loss | PREVIOUSLY DEVICE-PROVEN (Step 7, ADR #200) | The X6532 is reachable only over wireless adb; disabling Wi-Fi cuts the test channel, so the offline leg was not re-run in Step 11. Step 7 on this device: burned counter preserved, next online beat accepted. The Doze-without-exemption leg exercises the frozen-process/no-network path |
| VPN conflict (existing full-tunnel feature) | PASS | app VPN `tun0` active (owner uid 10414) while beats 36/37 were accepted and while a sync pass ran; no failures attributable to the tunnel |
| No GMS | PASS (emulator) | after `pm disable-user com.google.android.gms`: app launched (pid 9734, no FATAL); Partner tab renders "Push alerts aren't supported here" / "This device can't receive partner pushes. New partner activity still arrives when you open MyExodus or check for updates." GMS re-enabled after the leg |
| Heartbeat refusal/resync | PASS (observed live) | client sent counter 35 while server held 34 → reply `accepted=false` (server floor); client logged `heartbeat refused counter=35 server=34`, resynced, and the next beat (36) was accepted. Prior acceptance time preserved |
| Soak (session-long) | PASS | server heartbeat counter progressed 34 → 35 (refused, resynced) → 36 → 37 → 39 with `last_received_at` advancing to 22:33:51Z, `next_deadline_at` = 04:33:51Z (exactly +6 h), `late_deadline_at` = 23:18:51Z, `liveness_generation` 30, `notice_state` NONE; no FATAL in logcat across update, process death, force-stop, permission revocation, Doze and VPN legs |
| Reboot / reboot-before-first-unlock | BLOCKED | no USB on this machine; wireless adb does not survive a reboot and the device owner was unavailable to re-enable it. Not run — never claimed |
| User profile changes | BLOCKED | the device has one user profile; creating/switching profiles requires owner interaction |
| Two-device / multi-OEM legs | BLOCKED | single physical device on hand (standing ruling); remote labs not available |

## Vendor matrix labels (§12 tiering)

| Vendor / OS family | Label | Basis |
|---|---|---|
| Transsion (TECNO/Infinix/itel) — XOS | **PARTIALLY TESTED** | Infinix X6532 / XOS / Android 14 / API 34 — all legs above. One model, one OS version |
| Google Pixel — stock Android | UNVERIFIED | no device |
| Samsung — One UI | UNVERIFIED | no device |
| Xiaomi/Redmi/POCO — HyperOS/MIUI | UNVERIFIED | no device |
| OPPO/realme — ColorOS | UNVERIFIED | no device |
| OnePlus — OxygenOS | UNVERIFIED | no device |
| Vivo/iQOO — Funtouch OS | UNVERIFIED | no device |
| Motorola — near-stock | UNVERIFIED | no device |
| Huawei/HMS or GMS-free | **PARTIALLY TESTED (emulator only)** | GMS-disabled API-37 AVD launch + banner; no physical HMS device, so OEM power-management behavior is unverified |

Per §12: a passing test on one model never proves all skins work. Tier-1 families cover neither the minimum supported Android version (API 26) nor a current Android 16/API 36 physical device; both remain **UNVERIFIED**.

## Security and policy checks

| Check | Result |
|---|---|
| No code path auto-clicks, navigates, or blocks system settings to prevent revocation/uninstall | PASS — accessibility service only acts on user-configured blocked packages, excludes its own package, uses `GLOBAL_ACTION_HOME` solely to close a blocked app; no Settings automation. Settings deep links are user-initiated fix routing |
| Manifest declarations for sensitive APIs | PASS — `AndroidManifest.xml` unchanged across all PA Step 1–10 commits (`git log a8f5418..HEAD -- app/src/main/AndroidManifest.xml` empty) |
| No new Accessibility, Notification Listener, exact-alarm, full-screen-intent, or FGS permission for this feature | PASS — no `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `NotificationListenerService`, or `setExact*`; `FOREGROUND_SERVICE_SPECIAL_USE` predates the feature (enforcement FGS, 2026-08-27) and the heartbeat only piggybacks on it; `PartnerMessagingService` is `exported=false` |
| Static analysis (opengrep, 7 custom rules) | PASS — 0 findings / 361 tracked files after rewording one diagnostic label that named "dek"/"key" while logging only elapsed milliseconds (message-text false positive, same class as the documented `.keySet()` FP) |
| Secret scanning (gitleaks) | PASS — history scan 78 commits, no leaks. Working-tree hits are 3 gitignored cache artifacts (`.cache/web-search-pro/*.json`, `scripts/.cache/du-test-server.pem`) |
| Dependency scan (Trivy, HIGH/CRITICAL) | PASS for the shipped APK — all findings are `androidLintTool`/UTP test-tooling classpaths (bcprov 1.79, netty 4.1.93/4.1.110). The app ships bcprov 1.85.2, above every listed fix version (1.80.2/1.81.1/1.84 for CVE-2025-14813); Trivy raised no finding against the runtime version. No netty on any runtime classpath |
| Backend authorization tests | PASS — functions `npm test` 145/145; Firestore rules emulator suite 18/18, exit 0 |
| Client unit suite on the final source (incl. the one static-analysis label fix) | PASS — `:app:testDebugUnitTest` BUILD SUCCESSFUL, 220 classes / 1,985 tests / 0 failures / 0 errors / 0 skipped |
| Focused security review of the event/consent flow | PASS — mailbox create requires the pair's other member and `status == 'active'`; closed CBOR allowlist (§8.10); E2EE payloads, relay stores ciphertext only; per-row authorization recheck in the drainer (ADR #205); DMS dispatch re-joins `pairings/{pairId}` and requires both consent; `dms_install_state` deny-all; App Check enforced on all partner callables; delete request is the only post-revocation mailbox write |

## Release-readiness assessment

The feature is ready for the direct-APK release channel behind the existing rollout gate. No OEM-specific incompatibility was demonstrated, so no OEM-specific code was added. Remaining unknowns are hardware coverage (all non-Transsion families), reboot legs, profile changes, and two-phone end-to-end — all blocked on device availability, not on observed defects.

Reproduction commands for every run leg are recorded inline above; the constants that matter are `cmd jobscheduler run --namespace androidx.work.systemjobscheduler <pkg> <jobId>`, `dumpsys deviceidle force-idle|unforce`, `cmd power set-mode 1|0` with `dumpsys battery unplug|reset`, and `pm revoke|grant <pkg> android.permission.POST_NOTIFICATIONS`.
