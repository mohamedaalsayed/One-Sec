# one sec — browser extension

The Android app's pause, for Chromium-based browsers (Chrome, Edge, Brave,
Opera, Vivaldi...) and Firefox-based ones. Same features, same data rules:
no tracking, no network requests, everything stays in the browser's local
storage.

## How it maps from the app

| App | Extension |
|---|---|
| Guarded apps | Guarded sites (Instagram, Facebook, TikTok, X, Reddit by default; edit them in the toolbar popup) |
| Opening an app | Opening a guarded site, or switching to a tab showing one |
| Go back | Back to the site you came from, or close the tab |
| Link from WhatsApp etc. | Arriving from another site (e.g. WhatsApp Web), or a post/reel link opened in a fresh tab by a desktop app |
| Swiping to the next reel | The address changing to another reel/post, or scrolling 1.2 screens past it |
| Switching apps ends a quick check | No tab of the site visible for 15 seconds |

Everything else is the same: quick check (2–5 min) with growing friction,
"I just want to scroll" (30 min shared by all guarded sites, your own list of
up to 5 alternatives first, cooldowns of 1h, 3h, 5h...), limits reset at 4 AM.

## Build

```bash
./build.sh
```

This writes `dist/chromium/` and `dist/firefox/` (unpacked) plus a zip of each
for the stores. Both come from the same `src/`; only the manifest differs
(`manifests/`).

## Install

**Chromium-based browsers**
1. Open `chrome://extensions` (or `edge://extensions`, `brave://extensions`...)
2. Turn on **Developer mode**
3. **Load unpacked** → pick `dist/chromium`

**Firefox**
- To try it: open `about:debugging#/runtime/this-firefox` → **Load Temporary
  Add-on** → pick `dist/firefox/manifest.json`. It stays until Firefox restarts.
- To keep it: release Firefox only installs signed add-ons. Upload
  `dist/one-sec-firefox.zip` to [addons.mozilla.org](https://addons.mozilla.org/developers/)
  as an unlisted add-on (signed automatically, not public), or use Firefox
  Developer Edition / Nightly with `xpinstall.signatures.required` set to
  `false` in `about:config`.
- If the popup shows **Allow One Sec on your sites**, click it: Firefox grants
  site access separately from installing.
