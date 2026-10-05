#!/usr/bin/env bash
#
# Downloads every ad-block filter list used by muufi and stores it gzipped in
# app/src/main/assets/filters/.
#
# The APK assets are committed to the repo so Cloud builds are deterministic.
# Re-run this script to refresh the lists, then commit the changed *.txt.gz.
#
# Usage: tools/fetch-filters.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/filters"
mkdir -p "$OUT"

# name|url  (name becomes <name>.txt.gz; the extension is sniffed at runtime)
LISTS='
easylist|https://easylist.to/easylist/easylist.txt
easyprivacy|https://easylist.to/easylist/easyprivacy.txt
easylist-cookie|https://secure.fanboy.co.nz/fanboy-cookiemonster_ubo.txt
peterlowe|https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext
ublock-filters|https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt
ublock-privacy|https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt
ublock-quickfixes|https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt
ublock-unbreak|https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt
ublock-badware|https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt
adguard-base|https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_2_Base/filter.txt
adguard-mobile|https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_11_Mobile/filter.txt
adguard-tracking|https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_3_Spyware/filter.txt
adguard-annoyances|https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_14_Annoyances/filter.txt
adguard-trackparam|https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_17_TrackParam/filter.txt
brave-firstparty|https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty.txt
brave-unbreak|https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt
brave-cookie|https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-cookie-specific.txt
brave-social|https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-social.txt
brave-android|https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-android-specific.txt
fanboy-annoyance|https://secure.fanboy.co.nz/fanboy-annoyance_ubo.txt
fanboy-social|https://secure.fanboy.co.nz/fanboy-social.txt
fanboy-newsletter|https://secure.fanboy.co.nz/fanboy-newsletter.txt
fanboy-mobile|https://secure.fanboy.co.nz/fanboy-mobile-notifications.txt
urlhaus|https://urlhaus-filter.pages.dev/urlhaus-filter-online.txt
'

echo "Downloading filter lists into $OUT"
while IFS='|' read -r name url; do
  [ -n "$name" ] || continue
  tmp="$(mktemp)"
  curl -m 60 -fsSL -o "$tmp" "$url"
  gzip -9 -n -c "$tmp" > "$OUT/$name.txt.gz"
  rm -f "$tmp"
  printf '  %-20s %8s bytes (gz)\n' "$name" "$(wc -c < "$OUT/$name.txt.gz")"
done <<< "$LISTS"

echo "Done."
