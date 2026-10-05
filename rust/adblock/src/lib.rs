//! JNI bridge between the muufi Android app and adblock-rust.
//!
//! Exposed to Kotlin via `com.mkaafi6.muufi.AdBlocker`:
//!   nativeInit(filterDir, resourcesPath) -> String  (JSON status)
//!   nativeCheck(url, sourceUrl, requestType, method) -> String  ("B"|"R<body>"|"N")
//!   nativeRewrite(url, sourceUrl, requestType, method) -> String  (rewritten url or "")
//!   nativeCosmetics(url) -> String  (JSON: hide/script/generichide)
//!   nativeGenericSelectors(url, classesJson, idsJson) -> String (JSON array)

use std::fs;
use std::sync::OnceLock;

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;

use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;

static ENGINE: OnceLock<Engine> = OnceLock::new();

/// Builds an Engine from every `*.txt` file inside `dir`, then loads the
/// `$redirect` / scriptlet resources from the JSON file at `resources_path`.
///
/// Returns `(engine, list_count, resource_count)`.
fn build_engine(dir: &str, resources_path: &str) -> Result<(Engine, usize, usize), String> {
    let mut set = FilterSet::new(false);
    let mut lists = 0usize;

    let entries = fs::read_dir(dir).map_err(|e| format!("filter dir unreadable: {e}"))?;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|s| s.to_str()) == Some("txt") {
            if let Ok(text) = fs::read_to_string(&path) {
                set.add_filter_list(text, ParseOptions::default());
                lists += 1;
            }
        }
    }

    if lists == 0 {
        return Err("no filter lists found".into());
    }

    let mut engine = Engine::new_with_filter_set(set);

    // Resources power `$redirect` replacements (e.g. noop.js, stubs) and
    // `##+js(...)` scriptlets. Missing/empty resources are not fatal.
    let raw = fs::read_to_string(resources_path).unwrap_or_default();
    let resources: Vec<Resource> = if raw.trim().is_empty() {
        Vec::new()
    } else {
        serde_json::from_str(&raw).map_err(|e| format!("resources.json invalid: {e}"))?
    };
    let resource_count = resources.len();
    if resource_count > 0 {
        engine.use_resources(resources);
    }

    Ok((engine, lists, resource_count))
}

fn new_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(String::from)
}

fn status_json(ok: bool, error: &str, lists: usize, resources: usize) -> String {
    serde_json::json!({
        "ok": ok,
        "error": error,
        "lists": lists,
        "resources": resources,
    })
    .to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeInit<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    filter_dir: JString<'local>,
    resources_path: JString<'local>,
) -> jstring {
    let dir = match read_string(&mut env, &filter_dir) {
        Some(v) => v,
        None => return new_jstring(&mut env, &status_json(false, "filter dir not provided", 0, 0)),
    };
    let res = read_string(&mut env, &resources_path).unwrap_or_default();

    match build_engine(&dir, &res) {
        Ok((engine, lists, resources)) => {
            // A failed `set` only means the engine was already initialized (e.g.
            // the user toggled the blocker off and back on) — keep the existing one.
            let _ = ENGINE.set(engine);
            new_jstring(&mut env, &status_json(true, "", lists, resources))
        }
        Err(e) => new_jstring(&mut env, &status_json(false, &e, 0, 0)),
    }
}

/// Network check. Returns a compact result:
///   "B"        -> block the request
///   "R" + body -> serve this replacement body (redirect resource)
///   "N"        -> do nothing
///
/// A `$redirect` result is preferred over an outright block so that sites keep
/// working (Brave does the same).
#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeCheck<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
    source: JString<'local>,
    request_type: JString<'local>,
    method: JString<'local>,
) -> jstring {
    let url = match read_string(&mut env, &url) {
        Some(v) => v,
        None => return new_jstring(&mut env, "N"),
    };
    let source = read_string(&mut env, &source).unwrap_or_default();
    let request_type = read_string(&mut env, &request_type).unwrap_or_else(|| "other".to_string());
    let method = read_string(&mut env, &method).unwrap_or_else(|| "GET".to_string());

    let engine = match ENGINE.get() {
        Some(e) => e,
        None => return new_jstring(&mut env, "N"),
    };

    match Request::new(&url, &source, &request_type, &method) {
        Ok(req) => {
            let result = engine.check_network_request(&req);
            if let Some(redirect) = result.redirect {
                new_jstring(&mut env, &format!("R{redirect}"))
            } else if result.should_block() {
                new_jstring(&mut env, "B")
            } else {
                new_jstring(&mut env, "N")
            }
        }
        Err(_) => new_jstring(&mut env, "N"),
    }
}

/// `$removeparam` support: returns the rewritten URL if any query parameters
/// should be stripped for a top-level navigation, or an empty string.
#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeRewrite<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
    source: JString<'local>,
    request_type: JString<'local>,
    method: JString<'local>,
) -> jstring {
    let url = match read_string(&mut env, &url) {
        Some(v) => v,
        None => return new_jstring(&mut env, ""),
    };
    let source = read_string(&mut env, &source).unwrap_or_default();
    let request_type = read_string(&mut env, &request_type).unwrap_or_else(|| "document".to_string());
    let method = read_string(&mut env, &method).unwrap_or_else(|| "GET".to_string());

    let engine = match ENGINE.get() {
        Some(e) => e,
        None => return new_jstring(&mut env, ""),
    };

    match Request::new(&url, &source, &request_type, &method) {
        Ok(req) => {
            let rewritten = engine.check_network_request(&req).rewritten_url;
            new_jstring(&mut env, rewritten.as_deref().unwrap_or(""))
        }
        Err(_) => new_jstring(&mut env, ""),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeCosmetics<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
) -> jstring {
    let url = match read_string(&mut env, &url) {
        Some(v) => v,
        None => return new_jstring(&mut env, "{}"),
    };

    let engine = match ENGINE.get() {
        Some(e) => e,
        None => return new_jstring(&mut env, "{}"),
    };

    let resources = engine.url_cosmetic_resources(&url);
    let hide: Vec<String> = resources.hide_selectors.into_iter().collect();

    let json = serde_json::json!({
        "hide": hide,
        "script": resources.injected_script,
        "generichide": resources.generichide,
    })
    .to_string();

    new_jstring(&mut env, &json)
}

/// Returns additional generic selectors for newly-seen classes/ids (uBO-style
/// dynamic element hiding). Inputs are JSON arrays of strings; output is a JSON
/// array of CSS selectors.
#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeGenericSelectors<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
    classes: JString<'local>,
    ids: JString<'local>,
) -> jstring {
    let url = match read_string(&mut env, &url) {
        Some(v) => v,
        None => return new_jstring(&mut env, "[]"),
    };
    let classes_json = read_string(&mut env, &classes).unwrap_or_else(|| "[]".to_string());
    let ids_json = read_string(&mut env, &ids).unwrap_or_else(|| "[]".to_string());

    let engine = match ENGINE.get() {
        Some(e) => e,
        None => return new_jstring(&mut env, "[]"),
    };

    let class_list: Vec<String> = serde_json::from_str(&classes_json).unwrap_or_default();
    let id_list: Vec<String> = serde_json::from_str(&ids_json).unwrap_or_default();

    let resources = engine.url_cosmetic_resources(&url);
    let selectors = engine.hidden_class_id_selectors(
        class_list.iter().map(|s| s.as_str()),
        id_list.iter().map(|s| s.as_str()),
        &resources.exceptions,
    );

    let json = serde_json::to_string(&selectors).unwrap_or_else(|_| "[]".to_string());
    new_jstring(&mut env, &json)
}
