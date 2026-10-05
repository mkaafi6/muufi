// Builds app/src/main/assets/adblock/resources.json for adblock-rust.
//
// It merges:
//   1. Brave's published resource bundle
//      (https://github.com/brave/adblock-resources -> dist/resources.json) — the
//      exact resources Brave's own ad blocker loads (scriptlets/redirect stubs).
//   2. uBlock Origin's `$redirect` resources, assembled from an extracted uBO
//      tree (web_accessible_resources/ + js/redirect-resources.js).
//
// The output is a JSON array of adblock-rust `Resource` objects, loaded at
// runtime with `Engine::use_resources`. Committing the generated file keeps the
// Cloud build deterministic; re-run this to refresh.
//
// Usage:
//   node tools/build-resources.mjs [ubo-extracted-dir] [output-file]
// Default ubo-extracted-dir: ../muufi-gecko/app/src/main/assets/ublock_origin
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const uboDir =
  process.argv[2] ||
  process.env.UBO_DIR ||
  path.resolve(here, "../../muufi-gecko/app/src/main/assets/ublock_origin");
const outFile =
  process.argv[3] || path.resolve(here, "../app/src/main/assets/adblock/resources.json");

const BRAVE_RESOURCES_URL =
  "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json";

// ---- 1. Brave resources -----------------------------------------------------
const braveRes = await fetch(BRAVE_RESOURCES_URL, { headers: { "User-Agent": "muufi-build" } });
if (!braveRes.ok) throw new Error(`Brave resources download failed: HTTP ${braveRes.status}`);
const brave = await braveRes.json();

// ---- 2. uBlock Origin redirect resources ------------------------------------
const redirectJs = fs.readFileSync(path.join(uboDir, "js", "redirect-resources.js"), "utf8");
// The file is `export default new Map([...])`. Flip it into a function body and
// evaluate it, which handles all the JS object-literal quirks for us.
const map = new Function(redirectJs.replace("export default", "return"))();
const warDir = path.join(uboDir, "web_accessible_resources");

const TEXTUAL = new Set(["js", "html", "json", "css", "txt", "xml"]);
function mimeFor(name) {
  const ext = name.slice(name.lastIndexOf(".") + 1);
  switch (ext) {
    case "css": return "text/css";
    case "gif": return "image/gif";
    case "html": return "text/html";
    case "js": return "application/javascript";
    case "json": return "application/json";
    case "mp3": return "audio/mp3";
    case "mp4": return "video/mp4";
    case "png": return "image/png";
    case "txt": return "text/plain";
    case "xml": return "text/xml";
    default: return "application/octet-stream";
  }
}
const b64 = (buf) => Buffer.from(buf).toString("base64");

const ubo = [];
let skipped = 0;
for (const [name, props] of map) {
  if (props && props.params) { skipped++; continue; } // not supported by adblock-rust
  const file = path.join(warDir, name);
  if (!fs.existsSync(file)) { skipped++; continue; }
  const alias = props && props.alias;
  const aliases = alias ? (Array.isArray(alias) ? alias : [alias]) : [];
  const ext = name.slice(name.lastIndexOf(".") + 1);
  const content = TEXTUAL.has(ext)
    ? b64(fs.readFileSync(file, "utf8").replace(/\r/g, ""))
    : b64(fs.readFileSync(file));
  ubo.push({ name, aliases, kind: { mime: mimeFor(name) }, content });
}

// ---- merge + write ----------------------------------------------------------
const merged = [...brave, ...ubo];
fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, JSON.stringify(merged));
console.log(
  `resources: brave=${brave.length} ubo=${ubo.length} skipped=${skipped} ` +
    `total=${merged.length} bytes=${fs.statSync(outFile).size} -> ${outFile}`
);
