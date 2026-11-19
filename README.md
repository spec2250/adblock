# AdBlocker

Android ad blocker for apps (system-wide). Two modes:
- **Root mode**: edits `/system/etc/hosts` (requires root).
- **Non-root mode**: local VPN DNS proxy (no root).

## Build (no local Android SDK)
Push to GitHub and use the included GitHub Actions workflow:
1. Push files to repo root (`.github`, `app`, `build.gradle.kts`, ...).
2. Actions tab → "Build APK" → Run workflow.
3. Download `app-debug` artifact → unzip → `app-debug.apk`.

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
- Creamy gradient UI: dark default + light secondary.
- Motion graphics: on START the button transforms into a rising shield with expanding protection rings; on STOP the bubble collapses and the shield cracks with a particle burst.
- Animated counter, status dot, pulsing glow.
- Live info: last blocked domain, uptime, blocklist size.
- Blocklist update from internet (StevenBlack hosts) with bundled fallback.
- Min SDK 21 (Android 5.0).
- App icon included (adaptive + PNG fallback).
- All heavy work (root check, blocklist parse, list update) runs off the main thread.
- Auto-update: on launch the app checks the latest GitHub Release and shows an in-app dialog; downloading installs automatically (needs "install unknown apps" permission).

## Auto-update setup
The workflow creates a GitHub Release on every build with tag `v<run_number>` and attaches the APK. `versionCode` is set from the build number, so the in-app check detects newer releases.
