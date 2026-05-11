// Tauri 2 prevents the console window from showing on Windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    shadow_client_launcher_lib::run()
}
