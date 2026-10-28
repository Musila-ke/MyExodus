# Workstation Tooling — Setup, Fixes, and Procedures

Local development machine setup for MyExodus, verified **2026-10-24**. Read this before installing, updating, or debugging any dev tool on this Mac.

## Machine constraints (hard limits)

| Item | Value |
|---|---|
| macOS | 12.7.6 Monterey (build 21H1320) — **cannot be upgraded** (2015 Intel Mac) |
| CPU | Intel Core i5-5287U (`x86_64`) — use Intel/`amd64` builds only |
| RAM | 8 GB |
| Disk | ~44 GB free (2026-10-24) |
| Hypervisor | `kern.hv_support=1` (Docker/emulators possible) |

**Rule:** before installing or updating any tool, check its minimum macOS version. Never assume "latest" runs here. Proof in this doc's Remotion section: a routine update silently broke video rendering.

## Docker Desktop

- **Installed:** Docker Desktop **4.41.2 (build 191736)**, Intel, at `/Applications/Docker.app`.
- **Why this version:** 4.42.0 (2025-06-04) raised the minimum to macOS 13.3 Ventura; 4.41.2 is the last macOS 12 release.
- DMG retained at `~/Downloads/Docker-4.41.2-191736.dmg`.
- Signature/notarization verified (Developer ID: Docker Inc, Team ID 9BNSXJN65R).
- **Open item:** first launch + privileged-helper approval require the owner's admin password; not yet done (2026-10-24).

## Security tooling (open source only)

Full matrix, applicability, and authorization rules live in the project `CLAUDE.md` → "Security tooling" and the global `CLAUDE.md` → "Security Tooling Stack".

- **Installed:** Opengrep, Trivy, Gitleaks (all `/usr/local/bin`, wired into Gate 2.5); Cloudflare `security-audit` skill at `~/.claude/skills/security-audit` (invoke `/security-audit`).
- **Not installed (by owner decision, 2026-10-24 — too early):** OSV-Scanner, TruffleHog, ZAP, Nuclei, Checkov, Prowler, MobSF, mobsfscan, Syft, Grype.
- **Gate 2.5 re-run recipe:** memory `gate25-tooling`. Android lint stays a separate invocation (ADR #208).
- **Never** point ZAP, Nuclei, or Prowler at systems without ownership or written authorization.

## OpenMontage (video production)

Location: `~/tools/OpenMontage` (shallow clone of `origin/main`).

### Environment (verified 2026-10-24)

| Component | Value |
|---|---|
| Python | `/opt/anaconda3/bin/python` 3.13.5 — auto-detected via `CONDA_PREFIX`; system `python3` 3.14.7 lacks deps |
| Node / npx | v22.17.1 / 10.9.2 |
| ffmpeg / ffprobe | 9.0.2 (`/usr/local/bin`) |
| Remotion | 4.0.484 (`remotion-composer/node_modules/@remotion/cli`) |
| Python deps | `requirements.txt` fully satisfied |
| Provider registry | 166 tools; 8 core local providers configured; all API and local-GPU tools unavailable (expected — no API keys by owner rule) |

### Verification evidence (2026-10-24)

- `make test-contracts` → **1241 passed, 7 skipped** in 49.43 s.
- `make test` → **2043 passed, 12 skipped, 3 xfailed** in 214.87 s.
- End-to-end render: `python render_demo.py focusflow-pitch` → 1920×1080 h264 + AAC, 22.53 s, 3.33 MB; frame at 6 s shows the expected stat card.

### The macOS 12 / Remotion failure and its fix

**Symptom:** render reaches `Rendered N/N` frames, then aborts:

```
dyld: Symbol not found: (_AVCaptureDeviceTypeContinuityCamera)
Referenced from: .../@remotion/compositor-darwin-x64/libavdevice.dylib
Expected in: /System/Library/Frameworks/AVFoundation.framework/.../AVFoundation
```

**Root cause (verified, not guessed):**
1. Remotion's bundled `ffmpeg`/`ffprobe` binaries ship `LC_BUILD_VERSION minos 15.0` — built for macOS 15.
2. Remotion maintainer, issue #7027: *"we cannot support any older versions than macOS Ventura (macOS 13), due to not being able to compile binaries for older versions of macOS."*
3. Same failure class on macOS 12 confirmed in issue #4879 (2025-02-12); only fix there was upgrading macOS.

This machine is macOS 12.7.6, so **Remotion's bundled binaries can never run here**. Downgrading Remotion is not viable (all `@remotion/*` packages must match; the composer uses current APIs).

**Fix applied:** point the compositor at the system ffmpeg/ffprobe, with a shim for the one non-free encoder Remotion requests (`libfdk_aac`) that Homebrew ffmpeg lacks. The shim rewrites `-c:a libfdk_aac` → native `aac` and drops the libfdk-only `-cutoff` flag.

Shim files (durable, outside node_modules):

```
~/tools/openmontage-remotion-shims/ffmpeg     # arg-rewriting shim → /usr/local/bin/ffmpeg
~/tools/openmontage-remotion-shims/apply.sh   # idempotent installer
```

What `apply.sh` does in `@remotion/compositor-darwin-x64/`:
- moves the original `ffmpeg`/`ffprobe` to `*.bak` (once) before replacing anything;
- symlinks `ffmpeg` → the shim and `ffprobe` → `/usr/local/bin/ffprobe`.

**Re-apply after any `npm install` or OpenMontage update** (node_modules gets rewritten):

```bash
~/tools/openmontage-remotion-shims/apply.sh
cd ~/tools/OpenMontage && /opt/anaconda3/bin/python render_demo.py focusflow-pitch
```

Verify the output with `ffprobe` (expect `h264` + `aac`, correct duration) and by extracting a frame — not just the exit code.

**Limit (known):** the shim only exists in `node_modules`, which is untracked. An OpenMontage update that changes the compositor package name, or a clean reinstall, requires re-running `apply.sh`. If Remotion eventually supports macOS 12 again, remove the shims (`mv *.bak` back) and let the bundled binaries run.

### Post-update checklist

1. `cd ~/tools/OpenMontage && git status` — expect clean; `git log -1` for the version.
2. `~/tools/openmontage-remotion-shims/apply.sh` (re-applies after dependency refresh).
3. `make test-contracts` (fast) or `make test` (full, ~3.5 min).
4. Render one demo and `ffprobe` it (`focusflow-pitch` is the shortest, ~20 s).

### Optional local extras NOT installed (install only if a workflow needs them)

`faster-whisper` (transcription), `yt-dlp` + `youtube-transcript-api` (downloading/transcripts), `manim` (math animation), `rembg` (background removal), `piper` (TTS). None block compositing, cutting, grading, or Remotion rendering.

### Owner constraint

OpenMontage is used **as-is, without any APIs**. Image-generation tools (Ideogram, Recraft, OpenAI, Grok, etc.) all require keys and are unavailable by design. Poster/image work is limited to local composition (Remotion/CSS/HTML or ffmpeg).
