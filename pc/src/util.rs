use std::env;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

pub const CAM_PORT: u16 = 47800;
pub const DISCOVERY_PORT: u16 = 47801;
pub const UI_PORT: u16 = 47810;
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

pub fn config_dir() -> PathBuf {
    let base = if cfg!(windows) {
        env::var_os("APPDATA").map(PathBuf::from)
    } else {
        env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .or_else(|| env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
    };
    let dir = base.unwrap_or_else(env::temp_dir).join("phone-cam");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

pub fn json_str(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

/// Finds an executable on PATH or in `extra` directories.
pub fn find_exe(name: &str, extra: &[PathBuf]) -> Option<PathBuf> {
    let file = if cfg!(windows) { format!("{name}.exe") } else { name.to_string() };
    let path_dirs = env::var_os("PATH").map(|p| env::split_paths(&p).collect::<Vec<_>>()).unwrap_or_default();
    path_dirs.iter().chain(extra.iter()).map(|d| d.join(&file)).find(|p| p.is_file())
}

pub fn command(exe: &Path) -> Command {
    #[cfg_attr(not(windows), allow(unused_mut))]
    let mut c = Command::new(exe);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    c
}

/// Runs a command, returns stdout when it exits successfully.
pub fn run_out(exe: &Path, args: &[&str]) -> Option<String> {
    let out = command(exe).args(args).stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

pub fn url_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < b.len() => {
                let h = std::str::from_utf8(&b[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok());
                match h {
                    Some(v) => {
                        out.push(v);
                        i += 2;
                    }
                    None => out.push(b'%'),
                }
            }
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn form_get(body: &str, key: &str) -> Option<String> {
    body.split('&').find_map(|kv| {
        let (k, v) = kv.split_once('=')?;
        (k == key).then(|| url_decode(v))
    })
}

pub fn now_secs() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

pub fn random_pin() -> String {
    let mut buf = [0u8; 4];
    if let Ok(mut f) = std::fs::File::open("/dev/urandom") {
        use std::io::Read;
        let _ = f.read_exact(&mut buf);
    } else {
        let n = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(7);
        buf = n.to_le_bytes();
    }
    format!("{:06}", u32::from_le_bytes(buf) % 1_000_000)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn form_decoding() {
        assert_eq!(form_get("a=1&name=Pixel%207+Pro", "name").as_deref(), Some("Pixel 7 Pro"));
        assert_eq!(form_get("a=1", "b"), None);
        assert_eq!(url_decode("100%"), "100%");
    }

    #[test]
    fn json_escape() {
        assert_eq!(json_str("a\"b\\c\n"), "\"a\\\"b\\\\c\\n\"");
    }
}
