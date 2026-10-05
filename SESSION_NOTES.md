# Session Notes — muufi

> Project memory / worklog. Update as the project evolves.

**Created:** 2026-10-05
**Local path:** `/root/muufi`
**Repo:** https://github.com/mkaafi6/muufi
**Supersedes:** `newsphere` (Tauri — abandoned; Tauri can't block requests on Android)

## What it is

- Native **Android (Kotlin)** app named **muufi** with a **built-in ad blocker**
  using Rust [`adblock-rust`](https://github.com/brave/adblock-rust) via JNI.
- Generic **TV launcher icon**.
- Native **top app bar** + **bottom nav** (Home / Refresh / Back / Share / About).
  System status bar + navigation bar stay visible (not fullscreen / not edge-to-edge).
- Target: **≤10 MB**, **arm64-v8a** only, R8 release build.

## Why not Tauri / Flutter

- Tauri (Android) wraps the system WebView and gives Rust **no** way to intercept
  subresource requests → `adblock-rs` would do nothing.
- Flutter hosts web content in the same WebView; it can't run uBO (a WebExtension)
  and would only add ~10–15 MB.
- **Only a native WebView + request interception** can block ads like uBO.
  Literal uBO would require GeckoView + the uBO extension (~50–70 MB) — rejected.

## Architecture

- `app/src/main/assets/filters/*.txt.gz` → decompressed to `filesDir/filters/` at
  first run → compiled into `adblock::Engine` in a background thread.
- `WebViewClient.shouldInterceptRequest` → `AdBlocker.nativeShouldBlock(...)`.
- `url_cosmetic_resources` → hide-selectors CSS + scriptlets injected per page.
- `WebViewAssetLoader` serves the bundled `launcher.html` / `sites.json` over
  `https://appassets.androidplatform.net/`.
- Home screen = bundled `launcher.html` (also published nowhere; purely local).

## adblock-rust API pinned (v0.13.3)

- `FilterSet::new(false)` + `add_filter_list(text, ParseOptions::default())`
- `Engine::new_with_filter_set(set)`
- `Request::new(url, source_url, request_type, method)` — request_type strings:
  `document`, `subdocument`, `stylesheet`, `script`, `image`, `font`, `media`,
  `xhr`/`xmlhttprequest`, `websocket`, `ping`, `beacon`, `object`, `other`.
- `engine.check_network_request(&req).should_block()`
- `engine.url_cosmetic_resources(url)` → `{ hide_selectors, injected_script, generichide }`
- **Cargo features:** `default-features = false, features = ["embedded-domain-resolver",
  "full-regex-handling"]` — dropping default `single-thread` makes `Engine` `Send + Sync`.

## TODO

- [ ] Get the CI APK build green; confirm size ~5–10 MB.
- [ ] Install on device; verify ads are blocked on the sources.
- [ ] Optional: bundle uBO scriptlet/redirect resources (`use_resources`) for `##+js(...)`.
- [ ] Optional: real release keystore + `$redirect` support.
- [ ] User updates `app/src/main/assets/sites.json`; tell me when to change it.
