//! JNI bridge between the muufi Android app and adblock-rust.
//!
//! Exposed to Kotlin via `com.mkaafi6.muufi.AdBlocker`:
//!   nativeInit(filterDir) -> Boolean
//!   nativeShouldBlock(url, sourceUrl, requestType, method) -> Boolean
//!   nativeCosmetics(url) -> String (JSON)

use std::fs;
use std::sync::OnceLock;

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

static ENGINE: OnceLock<Engine> = OnceLock::new();

/// Builds an Engine from every `*.txt` file inside `dir`.
fn build_engine(dir: &str) -> Result<Engine, String> {
    let mut set = FilterSet::new(false);
    let mut lists = 0usize;

    let entries = fs::read_dir(dir).map_err(|e| e.to_string())?;
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
    Ok(Engine::new_with_filter_set(set))
}

#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeInit<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    filter_dir: JString<'local>,
) -> jboolean {
    let dir: String = match env.get_string(&filter_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    match build_engine(&dir) {
        Ok(engine) => {
            let _ = ENGINE.set(engine);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeShouldBlock<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
    source: JString<'local>,
    request_type: JString<'local>,
    method: JString<'local>,
) -> jboolean {
    let url: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let source: String = env
        .get_string(&source)
        .map(String::from)
        .unwrap_or_default();
    let request_type: String = env
        .get_string(&request_type)
        .map(String::from)
        .unwrap_or_else(|_| "other".to_string());
    let method: String = env
        .get_string(&method)
        .map(String::from)
        .unwrap_or_else(|_| "GET".to_string());

    let engine = match ENGINE.get() {
        Some(e) => e,
        None => return JNI_FALSE,
    };

    match Request::new(&url, &source, &request_type, &method) {
        Ok(req) => {
            if engine.check_network_request(&req).should_block() {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mkaafi6_muufi_AdBlocker_nativeCosmetics<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
) -> jstring {
    let url: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let engine = match ENGINE.get() {
        Some(e) => e,
        None => return std::ptr::null_mut(),
    };

    let resources = engine.url_cosmetic_resources(&url);
    let hide: Vec<String> = resources.hide_selectors.into_iter().collect();

    let json = serde_json::json!({
        "hide": hide,
        "script": resources.injected_script,
        "generichide": resources.generichide,
    })
    .to_string();

    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
