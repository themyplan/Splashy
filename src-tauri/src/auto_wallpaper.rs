//! Android auto-rotation via WorkManager (see plugins/wallpaper).
//! On Android the Rust cron scheduler must NOT run; WorkManager owns the schedule.

use tauri::{AppHandle, Manager};

#[cfg(target_os = "android")]
use tauri_plugin_splashy_wallpaper::SplashyWallpaperExt;

pub const DEFAULT_INTERVAL_MINUTES: u32 = 15;

/// Percent-encode everything except RFC 3986 unreserved characters.
#[cfg(target_os = "android")]
fn enc(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// Same URL shapes as getter.rs.
#[cfg(target_os = "android")]
fn endpoint_for(kind: &str, value: &str, orientation: &str) -> Option<String> {
    let v = enc(value);
    let o = match orientation {
        "landscape" | "portrait" | "squarish" => format!("&orientation={}", orientation),
        _ => String::new(),
    };
    match kind {
        "collection" => Some(format!("/photos/random?collections={v}{o}&count=30")),
        "user" => Some(format!("/photos/random?username={v}{o}&count=30")),
        "topic" => Some(format!("/photos/random?topics={v}{o}&count=30")),
        // "color" uses query= like "search": getter.rs does the same
        // (/photos/random has no color= param).
        "search" | "color" => Some(format!("/photos/random?query={v}{o}&count=30")),
        "related" => Some(format!("/photos/{v}/related")),
        _ => None,
    }
}

/// Snapshot of the user's sources for the worker (it runs with no Rust alive).
/// The time-group choice is made by the worker at run time, using the current hour.
#[cfg(target_os = "android")]
fn build_plan(settings: &serde_json::Value) -> serde_json::Value {
    use serde_json::{json, Value};

    let orientation = settings["orientation"].as_str().unwrap_or("");

    let targets: Vec<Value> = settings["getter_targets"]
        .as_array()
        .map(|a| a.as_slice())
        .unwrap_or(&[])
        .iter()
        .filter(|t| t["enabled"].as_bool().unwrap_or(false))
        .filter_map(|t| {
            let id = t["id"].as_str()?;
            let kind = t["type"].as_str()?; // serde renames GetterTarget.kind to "type" in settings.json
            let value = t["value"].as_str()?;
            let endpoint = endpoint_for(kind, value, orientation)?;
            Some(json!({
                "id": id,
                "endpoint": endpoint,
                "weight": t["weight"].as_u64().unwrap_or(1),
            }))
        })
        .collect();

    let groups: Vec<Value> = settings["time_groups"]
        .as_array()
        .map(|a| a.as_slice())
        .unwrap_or(&[])
        .iter()
        .map(|g| {
            json!({
                "start_hour": g["start_hour"].as_u64().unwrap_or(0),
                "end_hour": g["end_hour"].as_u64().unwrap_or(0),
                "target_ids": g["target_ids"].clone(),
            })
        })
        .collect();

    // Used only when the user has no enabled targets.
    let fallback = endpoint_for("search", "nature", orientation)
        .unwrap_or_else(|| "/photos/random?count=30".to_string());

    json!({ "fallback": fallback, "targets": targets, "groups": groups })
}

fn settings_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    Ok(app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("settings.json"))
}

/// `replace=false`: keep the existing job's timer (used at app start).
/// `replace=true`: apply the new interval (used when the user changes it).
#[cfg(target_os = "android")]
pub fn enable(app: &AppHandle, minutes: u32, replace: bool) -> Result<(), String> {
    let path = settings_path(app)?;
    let settings: serde_json::Value = std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(serde_json::Value::Null);

    let plan = build_plan(&settings).to_string();
    app.splashy_wallpaper()
        .schedule_auto(minutes, &path, plan, replace)
}

// async => runs off the main thread (the plugin call blocks until Kotlin answers).
#[tauri::command]
pub async fn schedule_auto_wallpaper(app: AppHandle, minutes: Option<u32>) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        enable(&app, minutes.unwrap_or(DEFAULT_INTERVAL_MINUTES), true)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (app, minutes);
        Err("Auto wallpaper via WorkManager is only available on Android".to_string())
    }
}

#[tauri::command]
pub async fn cancel_auto_wallpaper(app: AppHandle) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        app.splashy_wallpaper().cancel_auto()
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        Err("Auto wallpaper via WorkManager is only available on Android".to_string())
    }
}
