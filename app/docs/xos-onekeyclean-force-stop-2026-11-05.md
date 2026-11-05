# XOS OneKeyClean force-stop — root cause and prevention (2026-11-05, X6532)

**Symptom.** MyExodus showed "accessibility service is off / protection is paused" without the owner touching the app or permissions.

## Proven root cause (device evidence + decompiled OEM binaries)

- XOS `Griffin OneKeyClean` in system_server (pid 1821) force-stopped the app:
  ```
  ApplicationExitInfo #0: timestamp=2026-11-05 11:50:30.471 pid=10680
    reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) importance=125
    description=stop com.example.myexodus due to from pid 1821
  ```
- A force-stop kills the package; the platform then disables its accessibility service. The app's existing `UserStoppedDetector` correctly persisted `requiresExplicitReEnable`, so the paused notice was accurate, not a second bug.
- Ruled out: sleep/doze (`IdleClean` does not kill), VPN/Wi-Fi toggling, a broad multi-app clean (only one force-stop row across 211 packages), any app-side service crash.

## Why OneKeyClean killed it — verified bytecode chains

Decompiled from the device's own jars (`services.jar`, `griffin_policy.jar`, `griffin_core.jar`, launcher dex):

1. Sweep `griffinpolicy.d.a(String, ArrayList)` (griffin_policy.jar @0x024bfc): for each running process it consults
   `TranStateProvider.inCleanWhiteList(pkg)` (@0x024e54, true → protect branch) and then calls
   `IActivityManager.forceStopPackage(pkg, user)` (@0x025246, log `Griffin/OnekeyClean: OneKeyClean stop <pkg>`).
   Whitelisted packages are added to the protect list and never reach force-stop.
2. Whitelist write path: `ActivityManagerService.setCleanProtect(String, boolean)` (services.jar @0x36567c), gated by
   `TranAmHooker.checkApmPermissionLocked` — allowed only for uid ≤ 1000, shell, system / updated-system / platform-signed /
   FLAG_PERSISTENT apps; everyone else is silently refused ("Non-system applications are not allowed to call this method").
   The system-signed XOSLauncher calls it through a hidden PpdAPI reflection wrapper (`l.a.a.a.a.a.z.d0.e` @0x43614c).
3. Downstream: `TranGriffinComponentImpl.setCleanProtect` → `TranConfigProvider.addCleanWhiteList` →
   `ConfigImpl.addCleanWhiteList` → `griffincore.v.a(pkg, true)` → `m.d.add(pkg)` (persisted to
   `/data/performance/user/whitelist.xml`). `inCleanWhiteList` reads the same set.

**Consequence:** a third-party app cannot add itself to this list on any build — the gate is caller identity, not a permission.

## Fix (user-applied, no code change): lock MyExodus in Recents

Long-press the MyExodus card in Recents → **Lock** (same store as Phone Master's protected apps). Verified on device:

```
11-05 15:07:21.926 1821 4556 D Griffin/CleanWhiteList: callerPkg: com.transsion.XOSLauncher,
  setCleanProtect pkg:com.example.myexodus, protect = true
```

Two real OneKeyClean runs afterwards (15:08:10 removed gm / gallery / chrome / mpesa / whatsapp tasks;
15:12:36 removed Instagram's task): MyExodus pid 21397 unchanged, accessibility still bound, no new exit row.
Screenshots: `evidence/2026-11-05-xos-recents-lock-menu-x6532.png`,
`evidence/2026-11-05-xos-recents-locked-card-x6532.png`,
`evidence/2026-11-05-myexodus-protecting-after-onekeyclean-x6532.png`.

### Caveat (stated exactly)

In those two runs the sweep force-stopped no other app (no `OneKeyClean stop` lines), so survival was not observed
against a simultaneously killed third-party app. The causal chain rests on: the bytecode branch (whitelist → protect →
skip force-stop), the AMS write log above, the historical force-stop, and survival across two OneKeyClean runs.

## Cross-skin answer (honest)

- No app can prevent a system-privileged force-stop on any Android skin; there is no AOSP "protect my app" API.
- Prevention is per-OEM, user-applied: XOS = Recents Lock / Phone Master protected apps (proven above). Other skins have
  analogous user-facing whitelists (battery/background managers) but each needs its own verification.
- Universal app-side scope is limited to accurate detection (already shipped: `UserStoppedDetector` →
  `requiresExplicitReEnable` → neutral paused copy) plus per-OEM guidance. Any guidance/deep-link UI is a product change
  and needs owner approval.
