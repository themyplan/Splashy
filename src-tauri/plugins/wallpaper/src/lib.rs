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
