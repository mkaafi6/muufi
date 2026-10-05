# muufi

A tiny Android reader with a **built-in, network-level ad blocker** (powered by
[`adblock-rust`](https://github.com/brave/adblock-rust), the engine behind Brave).
Fullscreen-free UI: native **top app bar** + **bottom navigation bar**, with the
Android **status bar** (clock / battery / camera cutout) and **navigation bar**
(back / home / recents) left visible.

- **Language:** Kotlin (native Android) + Rust (ad-blocking core)
- **Size target:** ~5–10 MB (single ABI, arm64-v8a, R8 release build)
- **Built in the cloud:** GitHub Actions compiles the Rust `.so` + APK, so nothing
  heavy runs on the phone.

## How the ad blocker works

- Filter lists (EasyList, EasyPrivacy, uBO filters/privacy/badware, Peter Lowe's)
  are bundled gzipped in `app/src/main/assets/filters/`.
- On first launch they're decompressed into `filesDir/filters/` and compiled into
  an `adblock-rust` `Engine` (in a background thread).
- `WebViewClient.shouldInterceptRequest()` runs every subresource through the
  engine — blocked requests get an empty response (real network blocking).
- `url_cosmetic_resources()` supplies element-hiding selectors + scriptlets, which
  are injected into each page.

## Source list (developer-managed)

Edit `app/src/main/assets/sites.json`, commit, push to `main`, and the APK rebuilds.
End users never edit anything. Sources currently: BBC News, Reuters, AP News, NPR,
Al Jazeera.

## Build / download

Push to `main` → GitHub Actions **Build Android APK** → download the
**`muufi-release-apk`** artifact and install it (enable "Install unknown apps").

## Layout

```
app/src/main/
  java/com/mkaafi6/muufi/  MainActivity.kt, AdBlocker.kt   (Kotlin + JNI)
  assets/filters/          gzipped filter lists
  assets/sites.json        source list
  assets/launcher.html     home screen
  res/                     layout, theme, vector icons, launcher icon (TV)
rust/adblock/              Rust cdylib (adblock-rust + JNI)
.github/workflows/         cloud APK build
```

## Notes

- **v1 is cosmetic + network blocking.** `$redirect`/scriptlet resources are not
  bundled yet, so `##+js(...)` scriptlets are limited; network + cosmetic rules
  cover the large majority of ads.
- Release APK is signed with the Android **debug** key (installable; not store-ready).
- **Filter list licenses:** EasyList (GPLv3 / CC-BY-SA), uBlock Origin filters
  (GPLv3), Peter Lowe's list. See their upstream repos for terms.
