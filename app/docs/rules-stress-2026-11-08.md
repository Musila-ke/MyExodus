# Rules Page Stress Pass — 2026-11-08

Owner mandate: walk the Rules page with long lists, every mechanism combination, apps,
websites and the VPN path; judge cognitive load; prove or retract each claim. Companion
decision record: ADR #277. Environment: X6532 physical device (`install -r`, release
APK 1.1.5 / versionCode 7), 720x1600, Android per device, Chrome + Brave installed,
MyExodus VPN tunnel up for the website legs.

Every leg below is either PASSED (with the evidence) or BLOCKED (with the reason), never
silently dropped.

## 1. Long list / cognitive load — PASSED

- Adult category preset expanded from 10 to 32 hosts (ranked by Similarweb adult top-50,
  September 2026). Preset sheet on device: "32 of 32 selected"; all 32 rows scrolled and
  reviewed before "Block 32 sites" added them to a batch.
- Batch builder with 32 items: item list is a `LazyColumn` capped at
  `RULES_CARD_MAX_HEIGHT = 480.dp` (`RulesScreen.kt`), so neither the builder nor the
  saved batch card grows with member count; the card keeps its own short scroll.
- Saved "Adult" batch card on the Rules page: title + "32 items" + one toggle, members
  alphabetical, each row icon / host / mechanism / live state ("Always on · Blocked now"),
  and a single "Delete batch" action at the card foot. Page order stayed Batches → Apps →
  Websites → Deactivated. Verdict: organized, no wall of controls, no cognitive overload.

## 2. Mechanism combinations and precedence — PASSED

Engine order per `BlockDecisionEngine.evaluateRule` (`BlockDecisionEngine.kt:166-177`):
expiry Allow → time window Block → spent quota Block → always-on Block → Allow. The first
Block wins; all mechanisms are OR-ed, none can cancel another.

- Window + quota, schedule hit first (owner's question — "what if I hit my schedule before
  I hit the daily usage limit?"): AnkiDroid edited to window 6:00 PM–8:00 PM with a 2h
  limit and 1 min used at 6:44 PM. Row read `6:00 PM–8:00 PM · 2h daily limit /
  Used today: 1 / 120 min / Blocked now`. Inside the window the rule blocks with the quota
  almost untouched; the row says so. Rule then restored to its original 1m limit, no
  window (verified: `1m daily limit / Used today: 1 / 1 min / Blocked now`).
- Quota hit first: same AnkiDroid rule at 1m limit with usage 1/1 blocks all day and the
  row reads "Blocked now" with no window present.
- Overnight window + single-day scope + expiry: Bible rule (Wed only, 10:00 PM–7:00 AM
  overnight, Ends Nov 11 2026) row reads
  `10:00 PM–7:00 AM (overnight) · Wed · until Nov 11, 2026` / "Not active now" on a Sunday.
- Paused vs expired: Deactivated showed "Paused" for a switched-off website rule and
  "Expired" for a lapsed one; the expired label outranks pause (ADR #258/#18).
- Weekdays/Weekends preset chips exist in both editors and their labels are unit-pinned
  (`ScheduleSummaryTest`). Not separately device-saved today; recorded as covered by unit
  tests, not by this device pass.

## 3. Honest feedback before save — PASSED

- "Block all day" on: an inline note now appears under the switch (schedule and daily
  limit stay off, save no values of their own), so the two dead rows have a visible reason.
- Ends before the first chance to block: rejected at save with
  `This block ends before it can take effect...` (RuleTiming oracle). Device witness from
  the same session: the Bible rule refused Ends = today and saved once the end date moved
  to Wed Nov 11, 2026.
- Same-time window, invalid/duplicate target and out-of-range limit keep their existing
  inline messages.

## 4. Websites + VPN path — PASSED, one leg BLOCKED

- Engine consent: "Turn on" in the website editor raised the system VPN consent; accepted.
  `dumpsys activity services com.example.myexodus` shows `DnsSinkholeVpnService` running;
  `dumpsys connectivity` shows `ni{VPN CONNECTED}` on `tun0` with DNS 192.0.2.53 /
  fd00:14:14::53, `OwnerUid` = MyExodus, underlying Wi-Fi.
- Chrome: blocked host -> `DNS_PROBE_FINISHED_NXDOMAIN`; unblocked host (wikipedia.org) loads.
- Brave: blocked host -> "This site can't be reached — DNS address could not be found";
  unblocked host loads.
- Resolver independence: with Private DNS set to dns.google (validated 8.8.4.4/8.8.8.8)
  the same blocked host still failed through MyExodus, and the accessibility wall
  ("example.com is blocked — Blocked by your MyExodus rules") appeared. Private DNS
  restored to off.
- Owner's NXDOMAIN concern: a Brave address-bar search for "elections in israel" resolved
  search.brave.com and rendered results; a non-blocked site loads normally. The sinkhole
  answers NXDOMAIN only for listed hosts and forwards everything else.
- Website rule pause/unpause: toggling example.com off moved it to Deactivated as
  "Paused" and vacated the Websites section (empty-state card with category chips);
  toggling back restored `Always on / Blocked now` under Websites.
- **Real third-party VPN leg — PASSED (owner-approved run, same session).** Vehicle: a
  throwaway `VpnService` helper APK built outside the repo (`/tmp`, debug-signed),
  installed with the owner's go-ahead, then uninstalled and deleted. Sequence:
  - Helper's consent dialog accepted -> the system revoked MyExodus on the tap:
    `logcat -s MyExodusVpn` printed `MARKER: vpn-state REVOKED` at 19:17:52, the tunnel
    and `tun0` disappeared, and MyExodus posted "MyExodus protection revoked / The VPN
    connection was taken back. Re-enable protection from the app." on `vpn_spike`.
  - **Blocking is genuinely off while displaced:** with the helper holding contest of the
    slot, Chrome loaded `https://example.com` (the page's own example-domain text
    rendered) — the same host returned `DNS_PROBE_FINISHED_NXDOMAIN` under the tunnel
    earlier in this pass.
  - **Opening MyExodus re-establishes protection:** `MainActivity.onResume` ->
    `VpnLifecycle.ensure` restarted the tunnel with no consent dialog (network created
    19:22:10, `tun0` back, OwnerUid = MyExodus), the Rules rows returned to
    `Always on / Blocked now`, and `example.com` was blocked again by the accessibility
    wall ("Blocked by your MyExodus rules").
  - **OEM behavior recorded:** on this XOS API 34 build the displacement did *not* clear
    MyExodus's platform consent, so the "connection is off" row (which requires
    `NEEDS_CONSENT`) never surfaced and the engine self-healed silently instead. The
    `VpnLifecycle` comment's "revocation clears the platform's consent" held for the
    stale-claim correction but is not guaranteed across OEMs; the notification is the
    only signal at displacement time. The helper itself flapped (system logged
    `VPN CONNECTED`/`DISCONNECTED` pairs; XOS `Hiber/networkDetectorService` reacts to a
    new VPN app) because it is a debug app with no foreground service; the displacement
    was already proven before the flap. Also noted for future testers: a helper route
    outside the interface's address subnet made `establish()` return null on API 34; an
    in-subnet route established.
  - Known product risk remains: Android runs one VPN at a time, so MyExodus silently
    re-takes the slot (and displaces the other VPN) the next time the app is opened.
    Blocking pauses for as long as another VPN holds the slot and MyExodus is not
    foregrounded.

## 5. Known limitations recorded (product decisions, not defects)

- Ends is a date (end of that local day); "ten minutes from now" is not expressible. The
  earliest possible expiry is end of today, and a rule that cannot fire before that is
  refused.
- Website daily limits are disabled in this build; the editor says so and website rows
  carry no "Used today" line. Blocking and time windows still work.
- One VPN at a time (see above).

## Suite and tooling

JVM suite re-run with this change set: 2,541 tests / 0 failures / 0 errors / 0 skipped
(274 classes). Android lint
runs alone (ADR #208). Website preset pin added (`WebsitePresetsTest`), snapshot ordering
tests added (`RulesSnapshotTest`), row-state usage tests added (`ScheduleSummaryTest`),
`RuleTimingTest` covers the first-opportunity calendar.
