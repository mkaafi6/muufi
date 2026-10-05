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
- `WebViewAssetLoader` serves only the bundled `offline.html` fallback (shown
  when the network is unavailable).
- Home screen = **GitHub Pages** (`frontend/index.html` + `frontend/sites.json`),
  loaded at `https://mkaafi6.github.io/muufi/`.

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

## Status

- [x] CI APK build green — run 37256364196 (4m34s). **APK ~4.2 MB** ✅
- [x] Native lib `lib/arm64-v8a/libmuufi_adblock.so` (2.16 MB); filter lists bundled.
- [ ] Install on device; verify ads are actually blocked.

## Gotchas learned

- **AAPT auto-decompresses `*.gz` assets** and strips the `.gz` extension, so
  filter files arrive in the APK as plain `.txt`. `prepareFilters()` sniffs the
  gzip magic bytes (`0x1f 0x8b`) to handle either form.
- jni 0.21: convert `JavaStr` with `String::from`/`.into()` — it has no `ToString`.

## v2 changes (bars + fullscreen + stronger blocking)

- **Compact bars:** toolbar + bottom bar now **44dp** (were 56dp / ~50dp).
- **Fullscreen video:** added a `WebChromeClient`
  (`onShowCustomView`/`onHideCustomView`) + fullscreen overlay + immersive system
  bars + landscape orientation. YouTube fullscreen now works.
- **Stronger ad blocking:**
  - Request typing now uses the **`Sec-Fetch-Dest` header** (exact resource type)
    instead of guessing from the URL/extension.
  - Added AdGuard Base, AdGuard Mobile, uBO quick-fixes, uBO unbreak, EasyList
    Cookie (dropped AdGuard Tracking to stay near the size budget).
- **Cromite parity is not possible here:** Cromite is a Chromium fork with a C++
  AdBlock Plus engine + CNAME uncloaking + native WebSocket blocking. A WebView
  app can't do that; we approximate with adblock-rust + uBO filter syntax.
- Known gap: uBO **scriptlet/`$redirect` resources** are not loaded (adblock-rust's
  assembler only supports uBO's deprecated format).

## v3 changes (Option A — stronger blocking)

- **Document-start cosmetic hiding** via `addDocumentStartJavaScript` + a JS
  bridge (`window.MuufiBridge`) — ads hidden before the page paints (no flash).
- **Dynamic generic hiding** via `hidden_class_id_selectors`: a MutationObserver
  batches new class/id names → native → extra CSS (uBO-style).
- **`nativeCheck`** now honors **`$redirect`** (serves the replacement resource
  instead of just blocking).
- **Scriptlet injection** wired at document start (`injected_script`); populated
  once uBO resources are bundled.
- Reality: this is the ceiling for a WebView app. Literal uBO needs GeckoView
  (~50–70 MB).

## TODO

- [ ] Install on device; verify ads are blocked and fullscreen video works.
- [ ] Optional: bundle uBO scriptlet/`$redirect` resources (biggest remaining gap).
- [ ] Optional: bundle uBO scriptlet/redirect resources (`use_resources`) for `##+js(...)`.
- [ ] Optional: real release keystore + `$redirect` support.
- [ ] User updates `app/src/main/assets/sites.json`; tell me when to change it.
