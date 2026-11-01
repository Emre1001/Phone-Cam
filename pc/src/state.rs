use crate::util::{json_str, now_secs, random_pin, VERSION};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::Duration;

#[derive(Clone, Default)]
pub struct LinkStat {
    pub via: &'static str,
    pub kbps: u32,
}

#[derive(Clone, Default)]
pub struct Session {
    pub phone: String,
    pub links: Vec<LinkStat>,
    pub codec: &'static str,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub out_w: u32,
    pub out_h: u32,
    pub kbps: u32,
    pub live_fps: u32,
    pub frames: u64,
    pub dropped: u64,
    pub since: u64,
    /// The PC decoded at least one picture of this session.
    pub picture: bool,
    /// Frames go to the virtual camera (false: preview only).
    pub vcam: bool,
}

#[derive(Clone)]
pub struct UsbDev {
    pub serial: String,
    pub state: String,
    pub model: String,
    pub reversed: bool,
}

pub struct State {
    pub version: u64,
    pub name: String,
    pub pin: String,
    pub rotation: u16,
    pub mirror: bool,
    pub session: Option<Session>,
    pub usb: Vec<UsbDev>,
    pub adb: Option<String>,
    pub ffmpeg: Option<String>,
    pub vcam_dev: Option<String>,
    pub vcam_module: bool,
    pub vcam_busy: bool,
    pub vcam_error: String,
    /// ffmpeg could not write to the virtual camera during the last session (the preview kept running).
    pub vcam_stream_error: String,
    pub ips: Vec<String>,
    /// Largest short side sent to the virtual camera (0 = as received).
    pub vcam_max: u16,
    pub update_pct: i32,
    pub update_error: String,
    pub last_error: String,
    pub quit: bool,
    pub fingerprint: String,
    pub pin_fails: u32,
    pub pin_lock_until: u64,
}

/// Latest preview picture. Kept apart from `State` so 15 pictures a second do not wake the UI event stream.
#[derive(Default)]
struct Preview {
    seq: u64,
    jpeg: Option<Arc<Vec<u8>>>,
    quit: bool,
}

pub struct Shared {
    st: Mutex<State>,
    cv: Condvar,
    preview: Mutex<Preview>,
    preview_cv: Condvar,
}

impl Shared {
    pub fn new() -> Self {
        let mut st = State {
            version: 1,
            name: hostname(),
            pin: random_pin(),
            rotation: 0,
            mirror: false,
            session: None,
            usb: vec![],
            adb: None,
            ffmpeg: None,
            vcam_dev: None,
            vcam_module: false,
            vcam_busy: false,
            vcam_error: String::new(),
            vcam_stream_error: String::new(),
            ips: vec![],
            vcam_max: 1080,
            update_pct: -1,
            update_error: String::new(),
            last_error: String::new(),
            quit: false,
            fingerprint: String::new(),
            pin_fails: 0,
            pin_lock_until: 0,
        };
        crate::settings::load(&mut st);
        Shared { st: Mutex::new(st), cv: Condvar::new(), preview: Mutex::new(Preview::default()), preview_cv: Condvar::new() }
    }

    pub fn lock(&self) -> MutexGuard<'_, State> {
        self.st.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Mutates the state and wakes every waiting UI stream.
    pub fn update<R>(&self, f: impl FnOnce(&mut State) -> R) -> R {
        let mut g = self.lock();
        let r = f(&mut g);
        g.version += 1;
        self.cv.notify_all();
        r
    }

    pub fn read<R>(&self, f: impl FnOnce(&State) -> R) -> R {
        f(&self.lock())
    }

    /// Blocks until `version` changes or the timeout passes. Returns the new version.
    pub fn wait_version(&self, seen: u64, ms: u64) -> u64 {
        let g = self.lock();
        let (g, _) = self
            .cv
            .wait_timeout_while(g, std::time::Duration::from_millis(ms), |s| s.version == seen && !s.quit)
            .unwrap_or_else(|e| e.into_inner());
        g.version
    }

    /// Asks every waiting thread to stop (UI streams, preview streams, the main loop).
    pub fn quit(&self) {
        self.update(|s| s.quit = true);
        self.preview.lock().unwrap_or_else(|e| e.into_inner()).quit = true;
        self.preview_cv.notify_all();
    }

    fn preview_lock(&self) -> MutexGuard<'_, Preview> {
        self.preview.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Publishes a preview picture (`None` clears it when a session ends).
    pub fn set_jpeg(&self, jpeg: Option<Vec<u8>>) {
        let mut p = self.preview_lock();
        p.seq += 1;
        p.jpeg = jpeg.map(Arc::new);
        self.preview_cv.notify_all();
    }

    /// Waits for a change after `seen`. Returns the current sequence number and the picture, if a new one is there
    /// (none on timeout, on quit, or when the picture was cleared).
    pub fn wait_jpeg(&self, seen: u64, ms: u64) -> (u64, Option<Arc<Vec<u8>>>) {
        let g = self.preview_lock();
        let (g, _) = self
            .preview_cv
            .wait_timeout_while(g, Duration::from_millis(ms), |p| p.seq == seen && !p.quit)
            .unwrap_or_else(|e| e.into_inner());
        let fresh = !g.quit && g.seq != seen;
        (g.seq, if fresh { g.jpeg.clone() } else { None })
    }

    pub fn to_json(&self) -> String {
        let s = self.lock();
        let sess = match &s.session {
            None => "null".to_string(),
            Some(x) => format!(
                "{{\"phone\":{},\"links\":[{}],\"codec\":\"{}\",\"width\":{},\"height\":{},\"fps\":{},\"outW\":{},\"outH\":{},\"kbps\":{},\"liveFps\":{},\"frames\":{},\"dropped\":{},\"seconds\":{},\"picture\":{},\"vcam\":{}}}",
                json_str(&x.phone), x.links.iter().map(|l| format!("{{\"via\":\"{}\",\"kbps\":{}}}", l.via, l.kbps)).collect::<Vec<_>>().join(","), x.codec, x.width, x.height, x.fps, x.out_w, x.out_h,
                x.kbps, x.live_fps, x.frames, x.dropped, now_secs().saturating_sub(x.since), x.picture, x.vcam
            ),
        };
        let usb: Vec<String> = s
            .usb
            .iter()
            .map(|u| {
                format!(
                    "{{\"serial\":{},\"state\":{},\"model\":{},\"reversed\":{}}}",
                    json_str(&u.serial), json_str(&u.state), json_str(&u.model), u.reversed
                )
            })
            .collect();
        let ips: Vec<String> = s.ips.iter().map(|i| json_str(i)).collect();
        let opt = |o: &Option<String>| o.as_deref().map(json_str).unwrap_or_else(|| "null".into());
        format!(
            "{{\"appVersion\":\"{VERSION}\",\"os\":\"{}\",\"name\":{},\"pin\":{},\"rotation\":{},\"mirror\":{},\"vcamMax\":{},\"session\":{},\"usb\":[{}],\"adb\":{},\"ffmpeg\":{},\"vcam\":{{\"device\":{},\"module\":{},\"busy\":{},\"error\":{},\"streamError\":{}}},\"ips\":[{}],\"camPort\":{},\"updatePct\":{},\"updateError\":{},\"lastError\":{},\"fingerprint\":{},\"pinLocked\":{}}}",
            std::env::consts::OS,
            json_str(&s.name), json_str(&s.pin), s.rotation, s.mirror, s.vcam_max, sess, usb.join(","), opt(&s.adb), opt(&s.ffmpeg),
            opt(&s.vcam_dev), s.vcam_module, s.vcam_busy, json_str(&s.vcam_error), json_str(&s.vcam_stream_error), ips.join(","),
            crate::util::CAM_PORT, s.update_pct, json_str(&s.update_error), json_str(&s.last_error), json_str(&s.fingerprint), now_secs() < s.pin_lock_until
        )
    }
}

fn hostname() -> String {
    std::env::var("HOSTNAME")
        .ok()
        .filter(|h| !h.is_empty())
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok().map(|h| h.trim().to_string()))
        .or_else(|| std::env::var("COMPUTERNAME").ok())
        .unwrap_or_else(|| "PC".into())
}
