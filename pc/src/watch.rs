use crate::state::{Shared, UsbDev};
use crate::util::{command, find_exe, now_secs, run_out, CAM_PORT};
use std::net::UdpSocket;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

pub const APP_ID: &str = "com.phonecam";

fn adb_extra_dirs() -> Vec<PathBuf> {
    let mut v = vec![];
    for var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(p) = std::env::var_os(var) {
            v.push(PathBuf::from(p).join("platform-tools"));
        }
    }
    if let Some(h) = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")) {
        let h = PathBuf::from(h);
        v.push(h.join("Android/Sdk/platform-tools"));
        v.push(h.join("AppData/Local/Android/Sdk/platform-tools"));
    }
    if let Some(app) = std::env::current_exe().ok().and_then(|e| e.parent().map(|p| p.join("platform-tools"))) {
        v.push(app);
    }
    v
}

pub fn adb_path() -> Option<PathBuf> {
    find_exe("adb", &adb_extra_dirs())
}

/// Polls tools, USB devices, virtual camera and network addresses. Runs forever.
pub fn run(sh: Arc<Shared>) {
    let mut tick = 0u64;
    loop {
        let ffmpeg = find_exe("ffmpeg", &[std::env::current_exe().ok().and_then(|e| e.parent().map(|p| p.to_path_buf())).unwrap_or_default()])
            .map(|p| p.to_string_lossy().into_owned());
        let adb = adb_path();
        let usb = adb.as_ref().map(|a| scan_usb(a)).unwrap_or_default();
        let (vdev, vmodule) = scan_vcam();
        let ips = if tick % 3 == 0 { local_ips() } else { sh.read(|s| s.ips.clone()) };
        let adb_s = adb.map(|p| p.to_string_lossy().into_owned());

        let changed = sh.read(|s| {
            s.ffmpeg != ffmpeg
                || s.adb != adb_s
                || s.vcam_dev != vdev
                || s.vcam_module != vmodule
                || s.ips != ips
                || s.usb.len() != usb.len()
                || s.usb.iter().zip(&usb).any(|(a, b)| a.serial != b.serial || a.state != b.state || a.reversed != b.reversed)
        });
        if changed {
            sh.update(|s| {
                s.ffmpeg = ffmpeg;
                s.adb = adb_s;
                s.vcam_dev = vdev;
                s.vcam_module = vmodule;
                s.ips = ips;
                s.usb = usb;
            });
        }
        tick += 1;
        std::thread::sleep(Duration::from_secs(2));
        if sh.read(|s| s.quit) {
            return;
        }
    }
}

fn scan_usb(adb: &PathBuf) -> Vec<UsbDev> {
    let Some(list) = run_out(adb, &["devices", "-l"]) else { return vec![] };
    let mut out = vec![];
    for line in list.lines().skip(1) {
        let mut it = line.split_whitespace();
        let (Some(serial), Some(state)) = (it.next(), it.next()) else { continue };
        let model = line.split("model:").nth(1).and_then(|m| m.split_whitespace().next()).unwrap_or("").replace('_', " ");
        let mut reversed = false;
        if state == "device" {
            let rev = run_out(adb, &["-s", serial, "reverse", "--list"]).unwrap_or_default();
            reversed = rev.contains(&format!("tcp:{CAM_PORT}"));
            if !reversed {
                let p = format!("tcp:{CAM_PORT}");
                reversed = run_out(adb, &["-s", serial, "reverse", &p, &p]).is_some();
            }
        }
        out.push(UsbDev { serial: serial.into(), state: state.into(), model, reversed });
    }
    out
}

/// Starts the phone app over adb; the app then connects through the reverse tunnel.
pub fn launch_phone_app(serial: &str) -> bool {
    let Some(adb) = adb_path() else { return false };
    run_out(&adb, &["-s", serial, "shell", "am", "start", "-n", &format!("{APP_ID}/.MainActivity"), "--es", "autostart", "usb"]).is_some()
}

/// Finds the v4l2loopback device labelled "Phone-Cam" (Linux only).
fn scan_vcam() -> (Option<String>, bool) {
    if !cfg!(target_os = "linux") {
        return (None, false);
    }
    let module = std::path::Path::new("/sys/module/v4l2loopback").exists();
    let mut dev = None;
    if let Ok(rd) = std::fs::read_dir("/sys/class/video4linux") {
        for e in rd.flatten() {
            let name = std::fs::read_to_string(e.path().join("name")).unwrap_or_default();
            if name.trim() == "Phone-Cam" {
                dev = Some(format!("/dev/{}", e.file_name().to_string_lossy()));
            }
        }
    }
    (dev, module)
}

/// Loads v4l2loopback through polkit. `persist` also writes boot configuration.
pub fn setup_vcam(sh: &Arc<Shared>, persist: bool) {
    sh.update(|s| {
        s.vcam_busy = true;
        s.vcam_error.clear();
    });
    let mut script = String::from(
        "modprobe -r v4l2loopback 2>/dev/null; modprobe v4l2loopback exclusive_caps=1 card_label=Phone-Cam video_nr=42",
    );
    if persist {
        script.push_str(" && echo v4l2loopback > /etc/modules-load.d/phone-cam.conf && echo 'options v4l2loopback exclusive_caps=1 card_label=Phone-Cam video_nr=42' > /etc/modprobe.d/phone-cam.conf");
    }
    let res = find_exe("pkexec", &[]).map(|p| {
        command(&p).args(["sh", "-c", &script]).output()
    });
    let err = match res {
        None => "pkexec fehlt".to_string(),
        Some(Ok(o)) if o.status.success() => String::new(),
        Some(Ok(o)) => String::from_utf8_lossy(&o.stderr).trim().chars().take(300).collect(),
        Some(Err(e)) => e.to_string(),
    };
    sh.update(|s| {
        s.vcam_busy = false;
        s.vcam_error = err;
    });
}

fn local_ips() -> Vec<String> {
    let mut v: Vec<String> = vec![];
    if let Ok(s) = UdpSocket::bind("0.0.0.0:0") {
        if s.connect("192.0.2.1:9").is_ok() {
            if let Ok(a) = s.local_addr() {
                v.push(a.ip().to_string());
            }
        }
    }
    let extra = if cfg!(windows) {
        None
    } else {
        find_exe("hostname", &["/bin".into(), "/usr/bin".into()]).and_then(|h| run_out(&h, &["-I"]))
    };
    for ip in extra.unwrap_or_default().split_whitespace() {
        if ip.contains('.') && !v.iter().any(|x| x == ip) && !ip.starts_with("169.254") {
            v.push(ip.to_string());
        }
    }
    let _ = now_secs();
    v
}
