# AdBlocker — AI agent handoff

> **اقرأ هذا الملف أولاً.** يحتوي كل ما تحتاجه لتفهم المشروع وتعدّل وتنشر إصدارًا
> بدون استكشاف الكود أو استهلاك توكن إضافي. القسم الإنجليزي هو المرجع التقني.

Repo: `github.com/spec2250/adblock` · branch `main` · app id `com.adblocker.app`

---

## 1. What this app is

A native Android ad blocker (Kotlin) that blocks ads **system-wide for every app**
without cutting the internet. Two modes:

| Mode | How | Notes |
|---|---|---|
| **VPN mode** (default, no root) | `VpnService` that hijacks **DNS only** (`10.0.0.3`) and relays all other traffic untouched | Full-speed internet preserved |
| **Root mode** (optional) | Writes a marked section into `/system/etc/hosts` via `su` | Applied by `RootManager` |

It blocks **automatically** — there is no manual block button. Unknown ad/tracking
hostnames are recognised by a local heuristic and learned permanently.

UI: creamy/gold Material 3, Arabic (RTL), a `MaterialSwitch` toggle, a custom shield
motion graphic, and a **real phone notification** with a turn-on/turn-off action.

---

## 2. Stack & build

| Item | Value |
|---|---|
| Language | Kotlin 1.9.22 |
| AGP / Gradle / JDK | 8.2.2 / 8.2 / 17 |
| compileSdk · targetSdk · minSdk | 34 · 34 · 21 |
| UI | Material 3 (`com.google.android.material:material:1.11.0`), AppCompat, ViewBinding enabled but code uses `findViewById` |
| CI | GitHub Actions → debug APK + GitHub Release per push |

**There is no local JDK, Gradle or git on the current dev machine.** Compilation is
only verified by CI. Push, then read the Actions log.

---

## 3. File map (everything that matters)

```
app/src/main/
├── assets/blocklist.txt                  ← 2.3 MB StevenBlack hosts list (~76k domains). Do not rewrite.
├── AndroidManifest.xml                   ← perms, VpnService (foregroundServiceType=specialUse), FileProvider, BootReceiver
├── java/com/adblocker/app/
│   ├── AdBlockerApp.kt                   ← Application: loads list off-main-thread, weekly refresh
│   ├── BlocklistManager.kt               ← THE core: parse, whitelist, auto-detect, persist, update
│   ├── MainActivity.kt                   ← switch UI, animations, update check, whitelist dialogs
│   ├── UpdateManager.kt                  ← GitHub releases API → download → unzip → installer
│   ├── boot/BootReceiver.kt              ← reboot auto-start (reads pref auto_start)
│   ├── root/RootManager.kt               ← /system/etc/hosts patching between markers
│   ├── ui/ShieldMotionView.kt            ← custom-canvas shield animation (enable/disable)
│   └── vpn/
│       ├── AdBlockVpnService.kt          ← THE engine: VPN, DNS interception, relay, notification
│       └── DnsUtils.kt                   ← DNS question parser helpers
└── res/
    ├── layout/activity_main.xml          ← header, status card + MaterialSwitch, 200dp motion stage,
    │                                        counter card, info card, progress, 2 action buttons
    ├── layout/dialog_update.xml
    ├── drawable/ic_shield_notification.xml ← status-bar icon (alpha mask: outline shield + check)
    ├── values/strings.xml                ← ALL UI text is Arabic; add new strings here
    ├── values/colors.xml · themes.xml    ← creamy palette, dark (default) + light theme
    └── xml/provider_paths.xml            ← files-path update/
```

Support files: `app/build.gradle.kts`, `.github/workflows/build.yml`,
`tools/push.ps1` (token-based uploader, see §7).

---

## 4. How blocking works (important invariants)

### BlocklistManager (object / singleton)
- `blocked: MutableSet<String>` — `@Volatile`, `ConcurrentHashMap.newKeySet()`.
- `load(context)` parses `assets/blocklist.txt` + `filesDir/blocklist.txt`, then adds
  `extraAdProviders` (curated list) + `autoBlocked`, then removes whitelisted entries
  with an **Iterator loop** (`removeIf` needs Android 7+, minSdk is 21).
- `isBlocked(host)` — **whitelist first**, then exact match, then parent-domain match.
- `isAdLike(host)` — local heuristic: whole-label match against `adLabels`
  + substring match against `strongMarkers`. No network, no UI.
- `autoBlock(context, host)` — adds to the live set **and** persists to `auto_blocked`
  (cap 6000, `@Synchronized`).
- `whitelistVersion: Long` — bumped on whitelist edits; the VPN clears its DNS cache
  when it changes (so changes apply instantly instead of after 60 s).
- `update(context)` — downloads the list from `blocklist_url` pref (default StevenBlack).

### AdBlockVpnService
- Builder: address `10.0.0.2/24`, DNS `10.0.0.3`, **only `/32` routes for DNS**
  (`10.0.0.3`, 8.8.8.8, 8.8.4.4, 1.1.1.1, 1.0.0.1, 9.9.9.9) — never `0.0.0.0/0`,
  otherwise all traffic would go through the tunnel and slow down.
- `setBlocking(true)` is required: the worker loop reads the tun fd and would spin otherwise.
- TCP/UDP/ICMP relay loops are kept as a safety net.
- DNS path: 60 s in-memory cache → blocked? → ad-like? → parallel upstream
  (all servers at once, 800 ms timeout each, first valid answer wins).
- Blocked answer = **NOERROR + A 0.0.0.0** (`zeroResponse`), TTL 30. Never NXDOMAIN
  (apps treat it as a fatal error).
- `recordBlock()` increments `blockedCount`, sets `lastBlockedDomain`, refreshes the
  notification every 10 blocks.

### Notification (real, system-level)
- Channel `adblocker_status` (`IMPORTANCE_LOW`, no sound/vibration).
- id **1** = running, `setOngoing(true)`, action «إيقاف» → `ACTION_STOP`.
- id **2** = stopped, action «تشغيل» → `ACTION_START` via `PendingIntent.getForegroundService`
  (Android 12+ allows it because it comes from a user tap).
- Tapping the body opens `MainActivity`.
- `enabled` and `auto_start` prefs are written by the service so the app switch and
  `BootReceiver` stay in sync.

### SharedPreferences (`"adblocker"`)
| key | type | meaning |
|---|---|---|
| `enabled` | bool | protection on/off (source of truth for the switch) |
| `auto_start` | bool | start on boot |
| `light_theme` | bool | theme |
| `whitelist` | stringSet | user whitelist |
| `auto_blocked` | stringSet | learned ad domains |
| `blocklist_url` | string | list source |

---

## 5. Update flow (in-app)

`UpdateManager.check()` → `GET /repos/spec2250/adblock/releases/latest`, compares the
digits in `tag_name` (e.g. `v19`) with `BuildConfig.VERSION_CODE`
(= `github.run_number`, injected with `-PbuildNumber`). If newer: download the asset
(ZIP or APK) into `filesDir/update/`, unzip the first `*.apk`, then `FileProvider` →
`ACTION_VIEW` installer.

**Signature stability (already solved — do not break):**
- CI caches `~/.android` under key `android-debug-keystore-v1`.
- CI creates the keystore with `keytool` if missing.
- `app/build.gradle.kts` pins `signingConfigs.debug` to `~/.android/debug.keystore`.
- Verified: v18 and v19 have the same certificate `45CBAF40…`.
If the signing key ever changes, every user must uninstall once before installing.

---

## 6. CI / release

`.github/workflows/build.yml` — on every push to `main`:
1. checkout → cache keystore → setup JDK 17 → ensure keystore → Gradle 8.2
2. `gradle assembleDebug -PbuildNumber=${{ github.run_number }} --no-daemon`
3. upload artifact → zip → **create release `v<run_number>`** with `app-debug.zip`

Release URL pattern:
`https://github.com/spec2250/adblock/releases/download/v<N>/app-debug.zip`

**Gotcha:** editing `.github/workflows/*` requires a token with
**Workflows: Read and write** in addition to **Contents: Read and write**.
Without it the Git Data API returns `403 Resource not accessible by personal access token`.

---

## 7. How to push without git (the workflow that works here)

The dev machine has **no git**. Everything is pushed through the GitHub Git Data API,
which creates **one commit** (no per-file noise, no extra CI runs):

1. `GET /git/ref/heads/main` → `headSha`
2. `GET /git/commits/{headSha}` → `treeSha`
3. `POST /git/blobs` for each changed file (`{content: base64, encoding: "base64"}`)
4. `POST /git/trees` with `{base_tree: treeSha, tree: [{path, mode:"100644", type:"blob", sha}]}`
5. `POST /git/commits` with `{message, tree, parents: [headSha]}`
6. `PATCH /git/refs/heads/main` with `{sha: newCommit, force: false}`

A ready-made PowerShell implementation is in **`tools/push.ps1`**
(computes git blob SHA-1 locally so unchanged files are skipped, e.g. the 2.3 MB list):

```powershell
$env:GITHUB_TOKEN = "github_pat_..."   # user supplies, never commit it
powershell -NoProfile -ExecutionPolicy Bypass -File tools/push.ps1
# optional: -Message "my change"
```

Watch the run: `GET /actions/runs/{id}/logs` (needs Actions read; returns a ZIP).
The token only needs, for this repo: **Contents: RW** (+ **Workflows: RW** when
touching `.github/workflows`). Ask the user for it — never store it in the repo.

---

## 8. House rules (do not regress)

- **No manual blocking UI.** Detection must stay automatic.
- **Never** add `builder.addRoute("0.0.0.0", 0)` — it kills battery and speed.
- **Never** return NXDOMAIN for blocked domains; keep `0.0.0.0`.
- **Whitelist first** in `isBlocked()` / `isAdLike()` — breaking apps is worse than an ad.
- Keep minSdk 21 compatibility (no `removeIf`, no Java 8-only APIs, no `java.time`),
  or enable core-library desugaring first.
- Don't touch `assets/blocklist.txt` (2.3 MB, known-good).
- Don't remove the keystore cache/signing config (breaks in-app updates).
- All user-facing text is **Arabic** in `strings.xml`; RTL must stay intact.
- `R.string` / `R.id` references must exist — the project has no resource-lint gate,
  a typo only shows up as a CI compile error.

---

## 9. Known limitations (do not "fix" with DNS tricks)

- Same-domain/content ads (YouTube, Instagram feed, Spotify) — DNS cannot split them.
  Only patched clients (ReVanced) or hooking (LSPosed/MinMinGuard) can.
- DoH/DoT bypass: user must set **Private DNS = Off** in Android settings.
- Dozens of embedded SDKs share parent domains; blocking parents breaks apps, so the
  app blocks ad-specific subdomains and keeps parents whitelisted.
- Auto-detection can hit telemetry hosts (e.g. `app-measurement.com`); the builtin
  whitelist protects push/sign-in/update infrastructure (`googleapis.com`, `gstatic.com`,
  `firebase`, `crashlytics`, …).

---

## 10. State & sensible next steps

Shipped and working (v19):
auto-detection engine, curated provider list, real notification with toggle,
`MaterialSwitch`, entrance/crossfade animations, new shield animation (no background
plate), «تحقق من التحديثات», stable signing, release pipeline.

Good next candidates (pick with the user, verify each via CI):
1. On-device test report: unknown-domain detection rate, battery, false positives.
2. Per-app bypass (`Builder.addDisallowedApplication`) for banking apps.
3. Tap the last-blocked domain in the notification/app to whitelist it in one step.
4. Block DoH/DoT endpoints at the DNS layer as a fallback (still not a full fix).
5. Quick-settings tile (`TileService`) for on/off without opening the app.
6. Unit tests for `BlocklistManager.isAdLike` / parent matching (needs a test source set).

---

## 11. Quick sanity checks before every push

- [ ] Braces/parens balanced in every edited `.kt`
- [ ] Every `R.string.*` exists in `strings.xml`
- [ ] Every `R.id.*` exists in a layout
- [ ] All XML files well-formed
- [ ] New Kotlin APIs are minSdk-21 safe
- [ ] No token, keystore or secret added to the repo

Current release: **v19** · signature `45CBAF40…` · version code = Actions run number.
