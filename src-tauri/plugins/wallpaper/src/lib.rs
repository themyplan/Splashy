use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

#[cfg(target_os = "android")]
use serde::Serialize;
#[cfg(target_os = "android")]
use tauri::plugin::PluginHandle;

#[cfg(target_os = "android")]
const PLUGIN_IDENTIFIER: &str = "com.halfaxas.splashy.wallpaper";

#[cfg(target_os = "android")]
#[derive(Serialize)]
struct SetWallpaperArgs {
    path: String,
}

#[cfg(target_os = "android")]
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ScheduleArgs {
    interval_minutes: u32,
    settings_path: String,
    plan_json: String,
    replace: bool,
}

pub struct SplashyWallpaper<R: Runtime> {
    #[cfg(target_os = "android")]
    handle: PluginHandle<R>,
    #[cfg(not(target_os = "android"))]
    _marker: std::marker::PhantomData<fn() -> R>,
}

impl<R: Runtime> SplashyWallpaper<R> {
    /// Sets the image at `path` as home + lock screen wallpaper (Android only).
    pub fn set_wallpaper(&self, path: impl AsRef<std::path::Path>) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            let args = SetWallpaperArgs {
                path: path.as_ref().to_string_lossy().into_owned(),
            };
            self.handle
                .run_mobile_plugin::<serde_json::Value>("setWallpaper", args)
                .map(|_| ())
                .map_err(|e| e.to_string())
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = path;
            Err("splashy-wallpaper plugin is only available on Android".to_string())
        }
    }

    /// Starts/updates the WorkManager periodic job (min 15 minutes, enforced on the Kotlin side).
    /// `plan_json` is an opaque JSON plan (targets, time groups, fallback) built by the app.
    /// `replace=false` keeps an existing job's timer; `true` applies the new interval.
    pub fn schedule_auto(
        &self,
        interval_minutes: u32,
        settings_path: impl AsRef<std::path::Path>,
        plan_json: String,
        replace: bool,
    ) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            let args = ScheduleArgs {
                interval_minutes,
                settings_path: settings_path.as_ref().to_string_lossy().into_owned(),
                plan_json,
                replace,
            };
            self.handle
                .run_mobile_plugin::<serde_json::Value>("scheduleAutoWallpaper", args)
                .map(|_| ())
                .map_err(|e| e.to_string())
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (interval_minutes, settings_path, plan_json, replace);
            Err("splashy-wallpaper plugin is only available on Android".to_string())
        }
    }

    pub fn cancel_auto(&self) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            self.handle
                .run_mobile_plugin::<serde_json::Value>("cancelAutoWallpaper", serde_json::json!({}))
                .map(|_| ())
                .map_err(|e| e.to_string())
        }
        #[cfg(not(target_os = "android"))]
        {
            Err("splashy-wallpaper plugin is only available on Android".to_string())
        }
    }
}

pub trait SplashyWallpaperExt<R: Runtime> {
    fn splashy_wallpaper(&self) -> &SplashyWallpaper<R>;
}

impl<R: Runtime, T: Manager<R>> SplashyWallpaperExt<R> for T {
    fn splashy_wallpaper(&self) -> &SplashyWallpaper<R> {
        self.state::<SplashyWallpaper<R>>().inner()
    }
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("splashy-wallpaper")
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            {
                let handle = api.register_android_plugin(PLUGIN_IDENTIFIER, "WallpaperPlugin")?;
                app.manage(SplashyWallpaper { handle });
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = api;
                app.manage(SplashyWallpaper::<R> {
                    _marker: std::marker::PhantomData,
                });
            }
            Ok(())
        })
        .build()
}
