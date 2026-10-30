use crate::state::Shared;
use crate::util::{command, config_dir, find_exe, run_out};
use std::path::PathBuf;
use std::sync::Arc;

const RELEASE_PREFIX: &str = "https://github.com/Emre1001/Phone-Cam/releases/download/";

fn tmp_dir() -> PathBuf {
    config_dir().join("update")
}

/// Installer files are never kept: sweep at every start.
pub fn sweep() {
    let _ = std::fs::remove_dir_all(tmp_dir());
    if let Ok(exe) = std::env::current_exe() {
        let _ = std::fs::remove_file(exe.with_extension("old"));
    }
}

fn sha256(file: &PathBuf) -> Option<String> {
    let s = file.to_string_lossy().into_owned();
    if cfg!(windows) {
        let out = run_out(&find_exe("certutil", &[PathBuf::from("C:/Windows/System32")])?, &["-hashfile", &s, "SHA256"])?;
        out.lines().nth(1).map(|l| l.replace(' ', "").to_lowercase())
    } else {
        let out = run_out(&find_exe("sha256sum", &["/usr/bin".into()])?, &[&s])?;
        out.split_whitespace().next().map(|h| h.to_lowercase())
    }
}

/// Downloads a release binary, verifies its SHA-256, swaps it in and restarts.
pub fn install(sh: &Arc<Shared>, url: String, size: u64, digest: String) {
    let fail = |sh: &Arc<Shared>, msg: &str| {
        let _ = std::fs::remove_dir_all(tmp_dir());
        sh.update(|s| {
            s.update_pct = -1;
            s.update_error = msg.to_string();
        });
    };
    if !url.starts_with(RELEASE_PREFIX) || url.contains("..") {
        return fail(sh, "url");
    }
    let Some(curl) = find_exe("curl", &["C:/Windows/System32".into()]) else { return fail(sh, "curl") };
    let Ok(exe) = std::env::current_exe() else { return fail(sh, "exe") };
    let dir = tmp_dir();
    let _ = std::fs::remove_dir_all(&dir);
    if std::fs::create_dir_all(&dir).is_err() {
        return fail(sh, "dir");
    }
    let file = dir.join("new");
    sh.update(|s| {
        s.update_pct = 0;
        s.update_error.clear();
    });
    let mut child = match command(&curl).args(["-fsSL", "--max-time", "600", "-o"]).arg(&file).arg(&url).spawn() {
        Ok(c) => c,
        Err(_) => return fail(sh, "curl"),
    };
    let status = loop {
        match child.try_wait() {
            Ok(Some(st)) => break st,
            Ok(None) => {
                if size > 0 {
                    let done = std::fs::metadata(&file).map(|m| m.len()).unwrap_or(0);
                    let pct = (done * 100 / size).min(99) as i32;
                    sh.update(|s| s.update_pct = pct);
                }
                std::thread::sleep(std::time::Duration::from_millis(250));
            }
            Err(_) => return fail(sh, "curl"),
        }
    };
    if !status.success() {
        return fail(sh, "download");
    }
    let want = digest.trim_start_matches("sha256:").to_lowercase();
    if want.len() != 64 || sha256(&file).as_deref() != Some(want.as_str()) {
        return fail(sh, "checksum");
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o755));
    }
    let old = exe.with_extension("old");
    let _ = std::fs::remove_file(&old);
    if std::fs::rename(&exe, &old).is_err() || std::fs::copy(&file, &exe).is_err() {
        let _ = std::fs::rename(&old, &exe);
        return fail(sh, "replace");
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&exe, std::fs::Permissions::from_mode(0o755));
    }
    let _ = std::fs::remove_dir_all(&dir);
    sh.update(|s| s.update_pct = 100);
    let _ = std::process::Command::new(&exe).arg("--after-update").spawn();
    sh.update(|s| s.quit = true);
}
