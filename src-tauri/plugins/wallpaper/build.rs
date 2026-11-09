// No JS-facing commands: the plugin is only called from Rust,
// so no permissions are needed in capabilities/default.json.
const COMMANDS: &[&str] = &[];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
