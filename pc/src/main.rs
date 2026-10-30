mod assets;
mod cam;
mod crypto;
mod discovery;
mod http;
mod merge;
mod settings;
mod state;
mod update;
mod util;
mod watch;

use std::net::TcpListener;
use std::path::PathBuf;
use std::process::Child;
use std::sync::Arc;
use std::time::{Duration, Instant};
use util::{command, config_dir, find_exe, UI_PORT};

fn browser() -> Option<PathBuf> {
    let mut extra: Vec<PathBuf> = vec![];
    if cfg!(windows) {
        for var in ["ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"] {
            if let Some(base) = std::env::var_os(var).map(PathBuf::from) {
                extra.push(base.join("Microsoft/Edge/Application"));
                extra.push(base.join("Google/Chrome/Application"));
                extra.push(base.join("BraveSoftware/Brave-Browser/Application"));
            }
        }
    }
    let names: &[&str] = if cfg!(windows) {
        &["msedge", "chrome", "brave"]
    } else {
        &["google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "brave-browser"]
    };
    names.iter().find_map(|n| find_exe(n, &extra))
}

/// Opens the UI as an app window (Chromium family). Returns the child when we can watch its lifetime.
fn open_ui(url: &str) -> Option<Child> {
    if let Some(b) = browser() {
        let profile = config_dir().join("profile");
        return command(&b)
            .arg(format!("--app={url}"))
            .arg(format!("--user-data-dir={}", profile.display()))
            .args(["--window-size=1080,760", "--no-first-run", "--no-default-browser-check"])
            .spawn()
            .ok();
    }
    let _ = if cfg!(windows) {
        command(&PathBuf::from("cmd")).args(["/c", "start", "", url]).spawn()
    } else {
        command(&PathBuf::from("xdg-open")).arg(url).spawn()
    };
    None
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let no_window = args.iter().any(|a| a == "--no-window");
    let after_update = args.iter().any(|a| a == "--after-update");
    let url = format!("http://127.0.0.1:{UI_PORT}/");

    update::sweep();

    let mut bound = TcpListener::bind(("127.0.0.1", UI_PORT));
    for _ in 0..if after_update { 20 } else { 0 } {
        if bound.is_ok() {
            break;
        }
        std::thread::sleep(Duration::from_millis(300));
        bound = TcpListener::bind(("127.0.0.1", UI_PORT));
    }
    let Ok(listener) = bound else {
        // Already running: just show its window.
        if !no_window {
            open_ui(&url);
        }
        return;
    };

    let sh = Arc::new(state::Shared::new());
    let id = Arc::new(crypto::Identity::load_or_create(&config_dir().join("identity.key")));
    sh.update(|s| s.fingerprint = id.fingerprint());
    {
        let sh = sh.clone();
        std::thread::spawn(move || cam::listen(sh, id));
    }
    {
        let sh = sh.clone();
        std::thread::spawn(move || discovery::run(sh));
    }
    {
        let sh = sh.clone();
        std::thread::spawn(move || watch::run(sh));
    }
    {
        let sh = sh.clone();
        std::thread::spawn(move || http::serve(sh, listener));
    }

    let mut child = if no_window { None } else { open_ui(&url) };
    let started = Instant::now();
    loop {
        std::thread::sleep(Duration::from_millis(500));
        if sh.read(|s| s.quit) {
            break;
        }
        // Window closed: quit. Browsers that hand off to a running instance exit at once; then keep running.
        if let Some(c) = child.as_mut() {
            if c.try_wait().ok().flatten().is_some() {
                if started.elapsed() > Duration::from_secs(3) {
                    break;
                }
                child = None;
            }
        }
    }
    cam::disconnect();
    std::thread::sleep(Duration::from_millis(300));
}
