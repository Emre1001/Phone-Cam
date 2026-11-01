//! Phone connections. A session starts on one link and other links can join it (USB + Wi-Fi bonding).
//! Every link is encrypted (crypto.rs); frames carry a sequence number and merge.rs restores order.
use crate::crypto::{self, Identity, Opener, Sealer};
use crate::merge::Merger;
use crate::state::{LinkStat, Session, Shared};
use crate::util::{command, now_secs, CAM_PORT};
use std::io::{self, Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream};
use std::process::Stdio;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

const MAX_PIN_FAILS: u32 = 5;
const PIN_LOCK_SECS: u64 = 30;
/// Frame record type the phone sends on an idle link to keep it alive (no sequence number, never merged).
const KEEPALIVE: u8 = 0;
/// A link that delivers nothing (not even a keepalive) for this long is dead.
const LINK_TIMEOUT: Duration = Duration::from_secs(10);
/// Frames waiting for ffmpeg. More means more delay, fewer means drops on short hiccups.
const FEED_FRAMES: usize = 24;
/// Key frames are expensive: ask for one at most this often.
const KEY_REQUEST_GAP: Duration = Duration::from_millis(400);
/// A session that received nothing for this long gives way to a new phone.
const TAKEOVER_IDLE: Duration = Duration::from_secs(2);

/// One encrypted link of a session.
struct Link {
    via: &'static str,
    writer: Mutex<(TcpStream, Sealer)>,
    bytes: AtomicU64,
}

impl Link {
    fn send(&self, msg: &[u8]) -> io::Result<()> {
        let mut g = self.writer.lock().unwrap_or_else(|e| e.into_inner());
        let (s, seal) = &mut *g;
        seal.write(s, msg)
    }
    fn close(&self) {
        let g = self.writer.lock().unwrap_or_else(|e| e.into_inner());
        let _ = g.0.shutdown(std::net::Shutdown::Both);
    }
}

struct Sess {
    id: [u8; 16],
    /// Frames to the running ffmpeg. `None` while ffmpeg (re)starts.
    feed: Mutex<Option<SyncSender<Vec<u8>>>>,
    merger: Mutex<Merger>,
    links: Mutex<Vec<Arc<Link>>>,
    frames: AtomicU64,
    pictures: AtomicU64,
    closed: AtomicBool,
    last_key_request: Mutex<Option<Instant>>,
    /// Phone name from the hello, to recognise the same phone coming back.
    phone: String,
    /// When the last record arrived on any link.
    last_rx: Mutex<Instant>,
    /// ffmpeg has stopped and the session is cleaned up.
    done: AtomicBool,
}

impl Sess {
    fn links(&self) -> MutexGuard<'_, Vec<Arc<Link>>> {
        self.links.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn merger(&self) -> MutexGuard<'_, Merger> {
        self.merger.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn feed(&self) -> MutexGuard<'_, Option<SyncSender<Vec<u8>>>> {
        self.feed.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Relaxed)
    }
    fn idle(&self) -> Duration {
        self.last_rx.lock().unwrap_or_else(|e| e.into_inner()).elapsed()
    }
    /// Ends the session: links close, the pipeline notices within a moment and cleans up.
    fn close(&self) {
        self.closed.store(true, Ordering::Relaxed);
        let links: Vec<Arc<Link>> = self.links().clone();
        links.iter().for_each(|l| l.close());
    }

    /// Asks the phone for a key frame, over `via` if given. Rate limited: every key frame costs bandwidth,
    /// and asking again while the last one is on its way only makes congestion worse.
    fn request_key(&self, via: Option<&Link>) {
        {
            let mut last = self.last_key_request.lock().unwrap_or_else(|e| e.into_inner());
            if last.is_some_and(|t| t.elapsed() < KEY_REQUEST_GAP) {
                return;
            }
            *last = Some(Instant::now());
        }
        match via {
            Some(l) => {
                let _ = l.send(b"K");
            }
            None => {
                if let Some(l) = self.links().first().cloned() {
                    let _ = l.send(b"K");
                }
            }
        }
    }
}

static CURRENT: Mutex<Option<Arc<Sess>>> = Mutex::new(None);

fn current() -> MutexGuard<'static, Option<Arc<Sess>>> {
    CURRENT.lock().unwrap_or_else(|e| e.into_inner())
}

/// Closes the running session and all its links.
pub fn disconnect() {
    let sess = current().clone();
    if let Some(s) = sess {
        s.close();
    }
}

/// A new session may replace the running one when it is the same phone coming back (app restarted, Wi-Fi
/// dropped and the PC has not noticed yet) or when the running one has gone quiet. Else the PC is busy.
fn may_take_over(old: &Sess, phone: &str) -> bool {
    old.phone == phone || old.idle() > TAKEOVER_IDLE
}

pub fn listen(sh: Arc<Shared>, id: Arc<Identity>) {
    let l = match TcpListener::bind(("0.0.0.0", CAM_PORT)) {
        Ok(l) => l,
        Err(e) => {
            sh.update(|s| s.last_error = format!("port {CAM_PORT}: {e}"));
            return;
        }
    };
    for conn in l.incoming().flatten() {
        let (sh, id) = (sh.clone(), id.clone());
        thread::spawn(move || {
            let _ = handle(&sh, &id, conn);
        });
    }
}

struct Hello {
    pin: String,
    name: String,
    codec: u8,
    width: u16,
    height: u16,
    fps: u8,
    rotation: u16,
    mirror: bool,
}

struct Cursor<'a>(&'a [u8]);
impl Cursor<'_> {
    fn take(&mut self, n: usize) -> io::Result<&[u8]> {
        if self.0.len() < n {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "short message"));
        }
        let (a, b) = self.0.split_at(n);
        self.0 = b;
        Ok(a)
    }
    fn u8(&mut self) -> io::Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> io::Result<u16> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }
    fn str(&mut self) -> io::Result<String> {
        let n = self.u8()? as usize;
        Ok(String::from_utf8_lossy(self.take(n)?).into_owned())
    }
}

fn parse_hello(c: &mut Cursor) -> io::Result<Hello> {
    Ok(Hello {
        pin: c.str()?,
        name: c.str()?,
        codec: c.u8()?,
        width: c.u16()?,
        height: c.u16()?,
        fps: c.u8()?,
        rotation: c.u16()?,
        mirror: c.u8()? == 1,
    })
}

fn via(ip: IpAddr) -> &'static str {
    if ip.is_loopback() {
        "usb"
    } else {
        "wifi"
    }
}

fn handle(sh: &Arc<Shared>, id: &Identity, mut s: TcpStream) -> io::Result<()> {
    s.set_nodelay(true)?;
    s.set_read_timeout(Some(Duration::from_secs(8)))?;
    s.set_write_timeout(Some(Duration::from_secs(5)))?;
    let peer = s.peer_addr()?.ip();
    let Some((mut open, mut seal)) = crypto::server_handshake(&mut s, id)? else {
        return s.write_all(&[3]); // protocol 1 or garbage: "bad version"
    };
    let msg = open.read(&mut s)?;
    let mut c = Cursor(&msg);
    match c.u8()? {
        0 => new_session(sh, s, open, seal, peer, parse_hello(&mut c)?),
        1 => {
            let sid = c.take(16)?;
            let sess = current().as_ref().filter(|x| x.id == sid && !x.is_closed()).cloned();
            match sess {
                Some(sess) => {
                    seal.write(&mut s, &[0])?;
                    run_link(sh, &sess, s, open, seal, via(peer))
                }
                None => seal.write(&mut s, &[5]),
            }
        }
        _ => seal.write(&mut s, &[3]),
    }
}

fn new_session(sh: &Arc<Shared>, mut s: TcpStream, open: Opener, mut seal: Sealer, peer: IpAddr, h: Hello) -> io::Result<()> {
    if h.codec > 1 || h.width == 0 || h.height == 0 || h.fps == 0 {
        return seal.write(&mut s, &[3]);
    }
    let (ffmpeg, vdev, user_rot, user_mirror, ips) = sh.read(|st| (st.ffmpeg.clone(), st.vcam_dev.clone(), st.rotation, st.mirror, st.ips.clone()));
    if !peer.is_loopback() {
        // PIN check with lockout, so the 6 digits cannot be guessed online.
        let verdict = sh.update(|st| {
            if now_secs() < st.pin_lock_until {
                return 6;
            }
            if h.pin != st.pin {
                st.pin_fails += 1;
                if st.pin_fails >= MAX_PIN_FAILS {
                    st.pin_fails = 0;
                    st.pin_lock_until = now_secs() + PIN_LOCK_SECS;
                }
                return 1;
            }
            st.pin_fails = 0;
            0
        });
        if verdict != 0 {
            return seal.write(&mut s, &[verdict]);
        }
    }
    let Some(ffmpeg) = ffmpeg else { return seal.write(&mut s, &[4]) };

    let vcam_max = sh.read(|st| st.vcam_max);
    let pipe = Pipe {
        codec: h.codec,
        fps: h.fps,
        width: h.width as u32,
        height: h.height as u32,
        rot: (h.rotation % 360 + user_rot) % 360,
        mirror: h.mirror ^ user_mirror,
        vcam_max: vcam_max as u32,
    };
    let (ow, oh) = pipe.out_size(vdev.is_some());
    let mut sid = [0u8; 16];
    rand_core::RngCore::fill_bytes(&mut rand_core::OsRng, &mut sid);
    let sess = Arc::new(Sess {
        id: sid,
        feed: Mutex::new(None),
        merger: Mutex::new(Merger::default()),
        links: Mutex::new(vec![]),
        frames: AtomicU64::new(0),
        pictures: AtomicU64::new(0),
        closed: AtomicBool::new(false),
        last_key_request: Mutex::new(None),
        phone: h.name.chars().take(40).collect(),
        last_rx: Mutex::new(Instant::now()),
        done: AtomicBool::new(false),
    });
    let replaced = {
        let mut cur = current();
        let old = cur.clone();
        match old {
            Some(old) if !old.is_closed() && !may_take_over(&old, &sess.phone) => return seal.write(&mut s, &[2]),
            _ => {}
        }
        cur.replace(sess.clone())
    };
    if let Some(old) = replaced {
        // Let the old ffmpeg release the virtual camera before the new one opens it.
        old.close();
        let t = Instant::now();
        while !old.done.load(Ordering::Relaxed) && t.elapsed() < Duration::from_secs(3) {
            thread::sleep(Duration::from_millis(20));
        }
    }
    sh.update(|st| {
        st.session = Some(Session {
            phone: h.name.chars().take(40).collect(),
            codec: if h.codec == 0 { "H.264" } else { "HEVC" },
            width: h.width as u32,
            height: h.height as u32,
            fps: h.fps as u32,
            out_w: ow,
            out_h: oh,
            since: now_secs(),
            vcam: vdev.is_some(),
            links: vec![LinkStat { via: via(peer), kbps: 0 }],
            ..Default::default()
        });
        st.last_error.clear();
        st.vcam_stream_error.clear();
    });

    // Reply: ok, session id, and the PC's IPv4 addresses so the phone can add a Wi-Fi link.
    let v4: Vec<[u8; 4]> = ips.iter().filter_map(|i| i.parse::<std::net::Ipv4Addr>().ok()).map(|i| i.octets()).take(8).collect();
    let mut reply = vec![0u8];
    reply.extend_from_slice(&sid);
    reply.push(v4.len() as u8);
    v4.iter().for_each(|a| reply.extend_from_slice(a));
    if let Err(e) = seal.write(&mut s, &reply) {
        sess.closed.store(true, Ordering::Relaxed);
        finish(sh, &sess);
        return Err(e);
    }

    let pipeline = {
        let (sh, sess) = (sh.clone(), sess.clone());
        thread::spawn(move || run_pipeline(&sh, &sess, &ffmpeg, pipe, vdev))
    };
    let r = run_link(sh, &sess, s, open, seal, via(peer));
    let _ = pipeline.join();
    r
}

/// Reads frames from one link until it ends. The session lives while at least one link does.
fn run_link(sh: &Arc<Shared>, sess: &Arc<Sess>, s: TcpStream, mut open: Opener, seal: Sealer, via: &'static str) -> io::Result<()> {
    s.set_read_timeout(Some(LINK_TIMEOUT))?;
    let mut reader = s.try_clone()?;
    let link = Arc::new(Link { via, writer: Mutex::new((s, seal)), bytes: AtomicU64::new(0) });
    sess.links().push(link.clone());
    // A session closed while this link was joining must not keep it open.
    if sess.is_closed() {
        link.close();
    }
    let result = (|| -> io::Result<()> {
        loop {
            let rec = open.read(&mut reader)?;
            link.bytes.fetch_add(rec.len() as u64 + 20, Ordering::Relaxed);
            *sess.last_rx.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now();
            if sess.is_closed() {
                return Ok(());
            }
            if rec.first() == Some(&KEEPALIVE) {
                continue;
            }
            if rec.len() < 13 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "short frame"));
            }
            let ty = rec[0];
            let seq = u32::from_be_bytes([rec[1], rec[2], rec[3], rec[4]]);
            let mut need_key = false;
            {
                // Merging and handing over happen under one lock, so a restarting ffmpeg never gets frames out of order.
                let mut merger = sess.merger();
                let out = merger.push(seq, ty, rec[13..].to_vec(), Instant::now());
                need_key |= out.need_key;
                let feed = sess.feed().clone();
                match feed {
                    // ffmpeg is (re)starting: it resyncs at the next key frame.
                    None => need_key |= !out.frames.is_empty(),
                    Some(feed) => {
                        for (ty, data) in out.frames {
                            match feed.try_send(data) {
                                Ok(()) => {
                                    if ty != crate::merge::CONFIG {
                                        sess.frames.fetch_add(1, Ordering::Relaxed);
                                    }
                                }
                                // Decoder is behind (or gone): drop until the next key frame.
                                Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {
                                    merger.resync();
                                    need_key = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            if need_key {
                sess.request_key(Some(&link));
            }
        }
    })();
    let empty = {
        let mut links = sess.links();
        links.retain(|l| !Arc::ptr_eq(l, &link));
        links.is_empty()
    };
    link.close();
    if empty && !sess.closed.swap(true, Ordering::Relaxed) {
        if let Err(e) = &result {
            let quiet = matches!(
                e.kind(),
                io::ErrorKind::UnexpectedEof | io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionAborted | io::ErrorKind::BrokenPipe
            );
            if !quiet {
                let msg = match e.kind() {
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut => "timeout".to_string(),
                    _ => e.to_string(),
                };
                sh.update(|st| st.last_error = msg);
            }
        }
    }
    result
}

/// What ffmpeg gets: the stream as the phone sends it, and how to turn it into the picture the PC shows.
#[derive(Clone, Copy)]
struct Pipe {
    codec: u8,
    fps: u8,
    width: u32,
    height: u32,
    /// Degrees clockwise.
    rot: u16,
    mirror: bool,
    /// Largest short side for the virtual camera, 0 = as received.
    vcam_max: u32,
}

impl Pipe {
    /// Size after the optional down-scale (before rotation).
    fn scaled(&self, vcam: bool) -> (u32, u32) {
        let (w, h) = (self.width, self.height);
        let short = w.min(h);
        if !vcam || self.vcam_max == 0 || short <= self.vcam_max {
            return (w, h);
        }
        let even = |v: f64| ((v / 2.0).round() as u32) * 2;
        if w >= h {
            (even(w as f64 * self.vcam_max as f64 / h as f64), self.vcam_max)
        } else {
            (self.vcam_max, even(h as f64 * self.vcam_max as f64 / w as f64))
        }
    }

    /// Size of the picture the PC shows and gives to the virtual camera.
    fn out_size(&self, vcam: bool) -> (u32, u32) {
        let (w, h) = self.scaled(vcam);
        if self.rot == 90 || self.rot == 270 {
            (h, w)
        } else {
            (w, h)
        }
    }

    /// Big streams decode on several threads; small ones on one, which keeps the delay at a single frame.
    fn big(&self) -> bool {
        self.width as u64 * self.height as u64 > 1920 * 1080
    }
}

/// The last lines ffmpeg wrote to stderr, for the error shown in the UI.
fn stderr_tail(lines: &Mutex<VecDeque<String>>) -> String {
    let l = lines.lock().unwrap_or_else(|e| e.into_inner());
    // "[out#0/v4l2 @ 0x55d0] Could not write header" -> "Could not write header"
    let clean = |s: &str| match s.trim().strip_prefix('[').and_then(|r| r.split_once("] ")) {
        Some((_, rest)) => rest.trim().to_string(),
        None => s.trim().to_string(),
    };
    let mut parts: Vec<String> = vec![];
    for p in l.iter().map(|s| clean(s)).filter(|s| !s.is_empty()) {
        if !parts.contains(&p) {
            parts.push(p);
        }
    }
    parts.join(" · ").chars().take(300).collect()
}

fn run_pipeline(sh: &Arc<Shared>, sess: &Arc<Sess>, ffmpeg: &str, pipe: Pipe, mut vdev: Option<String>) {
    let mut last_frames = 0;
    let mut last_bytes: Vec<(usize, u64)> = vec![];
    let mut tick = Instant::now();
    // ffmpeg exits when it cannot write to the virtual camera. Then try once more without it, so the preview works.
    let mut fallback_from: Option<String> = None;
    loop {
        let args = ffmpeg_args(&pipe, vdev.as_deref());
        let spawned = command(std::path::Path::new(ffmpeg))
            .args(&args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn();
        let mut child = match spawned {
            Ok(c) => c,
            Err(e) => {
                sh.update(|st| st.last_error = format!("ffmpeg: {e}"));
                break;
            }
        };
        let (tx, rx) = sync_channel::<Vec<u8>>(FEED_FRAMES);
        {
            let mut m = sess.merger();
            m.resync();
            *sess.feed() = Some(tx);
        }
        sess.request_key(None);
        let (ow, oh) = pipe.out_size(vdev.is_some());
        let vcam = vdev.is_some();
        sh.update(|st| {
            if let Some(x) = st.session.as_mut().filter(|_| !sess.is_closed()) {
                x.out_w = ow;
                x.out_h = oh;
                x.vcam = vcam;
            }
        });

        let writer = {
            let ff_in = child.stdin.take().expect("stdin piped");
            thread::spawn(move || feed_ffmpeg(ff_in, rx))
        };
        let preview = {
            let ff_out = child.stdout.take().expect("stdout piped");
            let (sh, sess) = (sh.clone(), sess.clone());
            thread::spawn(move || split_jpegs(ff_out, |jpg| {
                sess.pictures.fetch_add(1, Ordering::Relaxed);
                sh.set_jpeg(Some(jpg));
            }))
        };
        let lines = Arc::new(Mutex::new(VecDeque::new()));
        let errors = {
            let ff_err = child.stderr.take().expect("stderr piped");
            let lines = lines.clone();
            thread::spawn(move || {
                use std::io::BufRead;
                for line in std::io::BufReader::new(ff_err).lines().map_while(Result::ok) {
                    let mut l = lines.lock().unwrap_or_else(|e| e.into_inner());
                    l.push_back(line);
                    if l.len() > 4 {
                        l.pop_front();
                    }
                }
            })
        };

        let exit = loop {
            if sess.is_closed() {
                break None;
            }
            if let Ok(Some(status)) = child.try_wait() {
                break Some(status);
            }
            thread::sleep(Duration::from_millis(200));
            if tick.elapsed() < Duration::from_secs(1) {
                continue;
            }
            let secs = tick.elapsed().as_secs_f64();
            tick = Instant::now();
            let f = sess.frames.load(Ordering::Relaxed);
            let dropped = sess.merger().dropped;
            let picture = sess.pictures.load(Ordering::Relaxed) > 0;
            let links: Vec<Arc<Link>> = sess.links().clone();
            let stats: Vec<LinkStat> = links
                .iter()
                .map(|l| {
                    // Keepalive towards the phone: it drops a link that stays silent.
                    let _ = l.send(b"P");
                    let key = Arc::as_ptr(l) as usize;
                    let b = l.bytes.load(Ordering::Relaxed);
                    let prev = last_bytes.iter().find(|(k, _)| *k == key).map(|(_, v)| *v).unwrap_or(b);
                    last_bytes.retain(|(k, _)| *k != key);
                    last_bytes.push((key, b));
                    LinkStat { via: l.via, kbps: (b.saturating_sub(prev) as f64 * 8.0 / 1000.0 / secs) as u32 }
                })
                .collect();
            last_bytes.retain(|(k, _)| links.iter().any(|l| Arc::as_ptr(l) as usize == *k));
            if picture {
                if let Some(e) = fallback_from.take() {
                    sh.update(|st| st.vcam_stream_error = e);
                }
            }
            // A replaced session must not write its numbers into the new one.
            if sess.is_closed() {
                continue;
            }
            sh.update(|st| {
                if let Some(x) = st.session.as_mut() {
                    x.live_fps = ((f.saturating_sub(last_frames)) as f64 / secs).round() as u32;
                    x.kbps = stats.iter().map(|l| l.kbps).sum();
                    x.frames = f;
                    x.dropped = dropped;
                    x.picture = picture;
                    x.links = stats;
                }
            });
            last_frames = f;
        };

        sess.feed().take();
        let _ = child.kill();
        let _ = child.wait();
        let _ = writer.join();
        let _ = preview.join();
        let _ = errors.join();
        let Some(status) = exit else { break };
        let why = match stderr_tail(&lines) {
            t if t.is_empty() => format!("{status}"),
            t => t,
        };
        if vdev.is_some() && fallback_from.is_none() {
            vdev = None;
            sess.pictures.store(0, Ordering::Relaxed);
            fallback_from = Some(why);
            continue;
        }
        sh.update(|st| st.last_error = format!("ffmpeg: {why}"));
        break;
    }
    sess.closed.store(true, Ordering::Relaxed);
    finish(sh, sess);
}

fn feed_ffmpeg(mut ff_in: impl Write, rx: Receiver<Vec<u8>>) {
    for pkt in rx {
        if ff_in.write_all(&pkt).is_err() {
            break;
        }
    }
}

/// Splits ffmpeg's MJPEG output into pictures. Baseline JPEG holds 0xFFD9 only as its end marker.
fn split_jpegs(mut r: impl Read, mut each: impl FnMut(Vec<u8>)) {
    let mut buf: Vec<u8> = Vec::with_capacity(1 << 18);
    let mut chunk = vec![0u8; 1 << 16];
    // Bytes before `from` are already known to hold no end marker.
    let mut from: usize = 0;
    while let Ok(n) = r.read(&mut chunk) {
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
        loop {
            // Start one byte back: a marker can straddle two reads.
            let start = from.saturating_sub(1);
            match buf[start..].windows(2).position(|w| w == [0xFF, 0xD9]) {
                Some(p) => {
                    let frame: Vec<u8> = buf.drain(..start + p + 2).collect();
                    from = 0;
                    if frame.starts_with(&[0xFF, 0xD8]) {
                        each(frame);
                    }
                }
                None => {
                    from = buf.len();
                    break;
                }
            }
        }
        if buf.len() > 16 << 20 {
            buf.clear();
            from = 0;
        }
    }
}

fn finish(sh: &Arc<Shared>, sess: &Arc<Sess>) {
    sess.close();
    let was_current = {
        let mut cur = current();
        let mine = cur.as_ref().is_some_and(|x| Arc::ptr_eq(x, sess));
        if mine {
            *cur = None;
        }
        mine
    };
    // A session that was replaced must not wipe the state of the one that replaced it.
    if was_current {
        sh.update(|st| st.session = None);
        sh.set_jpeg(None);
    }
    sess.done.store(true, Ordering::Relaxed);
}

fn ffmpeg_args(p: &Pipe, vdev: Option<&str>) -> Vec<String> {
    let mut chain: Vec<String> = vec![];
    let (sw, sh) = p.scaled(vdev.is_some());
    if (sw, sh) != (p.width, p.height) {
        chain.push(format!("scale={sw}:{sh}:flags=bilinear"));
    }
    match p.rot {
        90 => chain.push("transpose=1".into()),
        180 => chain.extend(["hflip".into(), "vflip".into()]),
        270 => chain.push("transpose=2".into()),
        _ => {}
    }
    if p.mirror {
        chain.push("hflip".into());
    }
    let pre = if chain.is_empty() { "null".to_string() } else { chain.join(",") };
    let (ow, oh) = p.out_size(vdev.is_some());
    // Preview: 15 pictures a second, 640 pixels on the long side.
    let preview = if ow >= oh { "fps=15,scale=640:-2:flags=bilinear" } else { "fps=15,scale=-2:640:flags=bilinear" };
    let fc = if vdev.is_some() {
        format!("[0:v]{pre},format=yuv420p,split=2[a][b];[b]{preview}[p]")
    } else {
        format!("[0:v]{pre},{preview}[p]")
    };
    let mut a: Vec<String> = ["-hide_banner", "-loglevel", "error", "-nostdin", "-fflags", "nobuffer"].map(String::from).to_vec();
    if p.big() {
        a.extend(["-threads", "3"].map(String::from));
    } else {
        a.extend(["-flags", "low_delay"].map(String::from));
    }
    a.extend(["-probesize", "32", "-analyzeduration", "0", "-framerate"].map(String::from));
    a.push(p.fps.to_string());
    a.extend(["-f".into(), if p.codec == 0 { "h264" } else { "hevc" }.into(), "-i".into(), "pipe:0".into()]);
    a.extend(["-filter_complex".into(), fc]);
    if let Some(dev) = vdev {
        a.extend(["-map", "[a]", "-c:v", "rawvideo", "-pix_fmt", "yuv420p", "-f", "v4l2"].map(String::from));
        a.push(dev.to_string());
    }
    a.extend(["-map", "[p]", "-c:v", "mjpeg", "-q:v", "6", "-f", "image2pipe", "pipe:1"].map(String::from));
    a
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pipe(codec: u8, w: u32, h: u32, fps: u8, rot: u16, mirror: bool, vcam_max: u32) -> Pipe {
        Pipe { codec, fps, width: w, height: h, rot, mirror, vcam_max }
    }

    #[test]
    fn args_virtual_cam_and_preview() {
        let a = ffmpeg_args(&pipe(0, 1920, 1080, 60, 90, true, 1080), Some("/dev/video42")).join(" ");
        assert!(a.contains("[0:v]transpose=1,hflip,format=yuv420p,split=2"), "{a}");
        assert!(a.contains("-f v4l2 /dev/video42"));
        assert!(a.contains("-framerate 60 -f h264"));
        assert!(a.contains("-flags low_delay"));
        assert!(a.contains("scale=-2:640"), "portrait preview: {a}");
    }

    #[test]
    fn args_preview_only() {
        let a = ffmpeg_args(&pipe(1, 1280, 720, 30, 0, false, 1080), None).join(" ");
        assert!(a.contains("[0:v]null,fps=15,scale=640:-2"), "{a}");
        assert!(a.contains("-f hevc"));
        assert!(!a.contains("v4l2"));
    }

    #[test]
    fn big_streams_are_scaled_for_the_virtual_camera() {
        let p = pipe(1, 3840, 2160, 60, 90, false, 1080);
        assert_eq!(p.out_size(true), (1080, 1920));
        assert_eq!(p.out_size(false), (2160, 3840));
        let a = ffmpeg_args(&p, Some("/dev/video42")).join(" ");
        assert!(a.contains("[0:v]scale=1920:1080:flags=bilinear,transpose=1,format=yuv420p"), "{a}");
        assert!(a.contains("-threads 3") && !a.contains("low_delay"), "{a}");
        // No cap: as received.
        assert_eq!(pipe(1, 3840, 2160, 60, 0, false, 0).out_size(true), (3840, 2160));
        // Odd aspect ratios stay even.
        assert_eq!(pipe(0, 2560, 1440, 30, 0, false, 720).scaled(true), (1280, 720));
        assert_eq!(pipe(0, 1440, 1080, 30, 0, false, 720).scaled(true), (960, 720));
    }

    #[test]
    fn ffmpeg_errors_are_readable() {
        let l = Mutex::new(VecDeque::from(vec![
            "[out#0/video4linux2,v4l2 @ 0x5639] Could not write header: Inappropriate ioctl".to_string(),
            "".to_string(),
            "Error while filtering: Inappropriate ioctl".to_string(),
            "[vost#0:0 @ 0x1] Could not write header: Inappropriate ioctl".to_string(),
        ]));
        assert_eq!(stderr_tail(&l), "Could not write header: Inappropriate ioctl · Error while filtering: Inappropriate ioctl");
    }

    #[test]
    fn jpegs_are_split_across_reads() {
        let mut stream = vec![];
        for i in 0..3u8 {
            stream.extend_from_slice(&[0xFF, 0xD8, i, i, 0xFF, 0xD9]);
        }
        // A reader that returns one byte at a time splits every marker.
        struct Slow<'a>(&'a [u8]);
        impl Read for Slow<'_> {
            fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
                if self.0.is_empty() || b.is_empty() {
                    return Ok(0);
                }
                b[0] = self.0[0];
                self.0 = &self.0[1..];
                Ok(1)
            }
        }
        let mut got = vec![];
        split_jpegs(Slow(&stream), |j| got.push(j));
        assert_eq!(got.len(), 3);
        assert_eq!(got[2], [0xFF, 0xD8, 2, 2, 0xFF, 0xD9]);
        let mut got = vec![];
        split_jpegs(&stream[..], |j| got.push(j));
        assert_eq!(got.len(), 3);
    }

    #[test]
    fn hello_parses() {
        let mut b = vec![3, b'1', b'2', b'3', 2, b'P', b'x', 1, 0x0F, 0x00, 0x08, 0x70, 60, 0, 90, 1];
        let h = parse_hello(&mut Cursor(&b)).unwrap();
        assert_eq!((h.pin.as_str(), h.name.as_str(), h.codec, h.width, h.height, h.fps, h.rotation, h.mirror), ("123", "Px", 1, 3840, 2160, 60, 90, true));
        b.truncate(5);
        assert!(parse_hello(&mut Cursor(&b)).is_err());
    }
}
