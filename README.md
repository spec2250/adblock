# AdBlocker

Android ad blocker for apps (system-wide). Two modes:
- **Root mode**: edits `/system/etc/hosts` (requires root).
- **Non-root mode**: local VPN DNS proxy (no root).

## Build (no local Android SDK)
Push to GitHub and use the included GitHub Actions workflow to build the APK:
1. Create a GitHub repo, push this folder.
2. Actions tab -> "Build APK" runs on push.
3. Download the `app-debug` artifact (APK).

Install APK on device (allow unknown sources).

## Features
- Creamy theme, dark by default, light secondary.
- Toggle + blocked counter.
- Blocklist update from internet (StevenBlack hosts) with bundled fallback.
- Min SDK 21 (Android 5.0).

## Notes
- Root mode counter shows blocklist size; VPN mode shows live blocked DNS count.
- Rewarded ads may break rewards in some games.
- Hardcoded-IP ads are not blocked.