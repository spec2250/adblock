# ADBlock

Android ad blocker for apps (system-wide). Two modes:
- **Root mode**: edits `/system/etc/hosts` (requires root).
- **Non-root mode**: local VPN DNS proxy (no root).

## Build (no local Android SDK)
Push to GitHub and use the included GitHub Actions workflow:
1. Push files to repo root (`.github`, `app`, `build.gradle.kts`, ...).
2. Actions tab → "Build APK" → Run workflow.
3. Download the `ADBlock.apk` artifact (or the release asset with the same name) and install it directly — no unzip step.

## Install warning "Scan app / suspicious"
Normal for side-loaded APKs (self-signed key, no Play Store reputation).
- Tap **Install anyway** / **Continue**.
- Or: Play Store → Play Protect → settings → temporarily disable "Scan apps with Play Protect", install, then re-enable.
- A release APK with your own signing key reduces (not removes) the warning.

## Why ads may still appear
- **Private DNS (DoT)** must be OFF: Settings → Network & Internet → Private DNS → Off/Automatic.
- Some apps use **DoH / hardcoded IPs** — DNS blocking cannot stop these.
- Rewarded ads may break rewards in some games.

## Features
- Fixed cream + black + gold design taken from the reference layout (no system/theme colors, single light theme).
- Motion graphics: on START the shield rises with expanding protection rings and then **stays on screen** (gold shield + check); on STOP it cracks with a particle burst and settles as a grey shield. It never disappears or leaves only a glow.
- Animated counter, status dot, pulsing glow.
- Live info: last blocked domain, uptime, blocklist size.
- Blocklist update from internet (StevenBlack hosts) with bundled fallback.
- Min SDK 21 (Android 5.0).
- App icon included (adaptive + PNG fallback).
- All heavy work (root check, blocklist parse, list update) runs off the main thread.
- Auto-update: on launch the app checks the latest GitHub Release and shows an in-app dialog; downloading installs automatically (needs "install unknown apps" permission).

## Auto-update setup
The workflow creates a GitHub Release on every build with tag `v<run_number>` and attaches the APK. `versionCode` is set from the build number, so the in-app check detects newer releases.
