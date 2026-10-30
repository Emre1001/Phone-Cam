//! Phone connections. A session starts on one link and other links can join it (USB + Wi-Fi bonding).
//! Every link is encrypted (crypto.rs); frames carry a sequence number and merge.rs restores order.
use crate::crypto::{self, Identity, Opener, Sealer};
use crate::merge::Merger;
use crate::state::{LinkStat, Session, Shared};
use crate::util::{command, now_secs, CAM_PORT};
use std::io::{self, Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{sync_channel, SyncSender, TrySendError};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

const MAX_PIN_FAILS: u32 = 5;
const PIN_LOCK_SECS: u64 = 30;

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
    feed: Mutex<Option<SyncSender<Vec<u8>>>>,
    merger: Mutex<Merger>,
    links: Mutex<Vec<Arc<Link>>>,
    frames: AtomicU64,
    closed: AtomicBool,
}

impl Sess {
    fn links(&self) -> MutexGuard<'_, Vec<Arc<Link>>> {
        self.links.lock().unwrap_or_else(|e| e.into_inner())
    }
}

static CURRENT: Mutex<Option<Arc<Sess>>> = Mutex::new(None);

fn current() -> MutexGuard<'static, Option<Arc<Sess>>> {
    CURRENT.lock().unwrap_or_else(|e| e.into_inner())
}

/// Closes the running session and all its links.
pub fn disconnect() {
    if let Some(s) = current().as_ref() {
        s.closed.store(true, Ordering::Relaxed);
        s.links().iter().for_each(|l| l.close());
    }
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
            let sess = current().as_ref().filter(|x| x.id == sid && !x.closed.load(Ordering::Relaxed)).cloned();
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

    let total_rot = (h.rotation + user_rot) % 360;
    let (ow, oh) = if total_rot == 90 || total_rot == 270 { (h.height, h.width) } else { (h.width, h.height) };
    let mut sid = [0u8; 16];
    rand_core::RngCore::fill_bytes(&mut rand_core::OsRng, &mut sid);
    let (tx, rx) = sync_channel::<Vec<u8>>(24);
    let sess = Arc::new(Sess {
        id: sid,
        feed: Mutex::new(Some(tx)),
        merger: Mutex::new(Merger::default()),
        links: Mutex::new(vec![]),
        frames: AtomicU64::new(0),
        closed: AtomicBool::new(false),
    });
    {
        let mut cur = current();
        if cur.as_ref().is_some_and(|x| !x.closed.load(Ordering::Relaxed)) {
            return seal.write(&mut s, &[2]);
        }
        *cur = Some(sess.clone());
    }
    sh.update(|st| {
        st.session = Some(Session {
            phone: h.name.chars().take(40).collect(),
            codec: if h.codec == 0 { "H.264" } else { "HEVC" },
            width: h.width as u32,
            height: h.height as u32,
            fps: h.fps as u32,
            out_w: ow as u32,
            out_h: oh as u32,
            since: now_secs(),
            ..Default::default()
        });
        st.last_error.clear();
    });

    // Reply: ok, session id, and the PC's IPv4 addresses so the phone can add a Wi-Fi link.
    let v4: Vec<[u8; 4]> = ips.iter().filter_map(|i| i.parse::<std::net::Ipv4Addr>().ok()).map(|i| i.octets()).take(8).collect();
    let mut reply = vec![0u8];
    reply.extend_from_slice(&sid);
    reply.push(v4.len() as u8);
    v4.iter().for_each(|a| reply.extend_from_slice(a));
    seal.write(&mut s, &reply)?;

    let pipeline = {
        let (sh, sess) = (sh.clone(), sess.clone());
        let args = ffmpeg_args(h.codec, h.fps, total_rot, h.mirror ^ user_mirror, vdev.as_deref());
        thread::spawn(move || run_pipeline(&sh, &sess, &ffmpeg, args, rx))
    };
    let r = run_link(sh, &sess, s, open, seal, via(peer));
    let _ = pipeline.join();
    r
}

/// Reads frames from one link until it ends. The session lives while at least one link does.
fn run_link(sh: &Arc<Shared>, sess: &Arc<Sess>, s: TcpStream, mut open: Opener, seal: Sealer, via: &'static str) -> io::Result<()> {
    let mut reader = s.try_clone()?;
    let link = Arc::new(Link { via, writer: Mutex::new((s, seal)), bytes: AtomicU64::new(0) });
    sess.links().push(link.clone());
    let result = (|| -> io::Result<()> {
        loop {
            let rec = open.read(&mut reader)?;
            if rec.len() < 13 {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "short frame"));
            }
            let ty = rec[0];
            let seq = u32::from_be_bytes([rec[1], rec[2], rec[3], rec[4]]);
            link.bytes.fetch_add(rec.len() as u64, Ordering::Relaxed);
            let out = sess.merger.lock().unwrap_or_else(|e| e.into_inner()).push(seq, ty, rec[13..].to_vec(), Instant::now());
            let mut need_key = out.need_key;
            let feed = sess.feed.lock().unwrap_or_else(|e| e.into_inner()).clone();
            let Some(feed) = feed else { return Ok(()) };
            for (ty, data) in out.frames {
                match feed.try_send(data) {
                    Ok(()) => {
                        if ty != crate::merge::CONFIG {
                            sess.frames.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                    Err(TrySendError::Full(_)) => {
                        // Decoder is behind: drop until the next key frame.
                        sess.merger.lock().unwrap_or_else(|e| e.into_inner()).resync();
                        need_key = true;
                        break;
                    }
                    Err(TrySendError::Disconnected(_)) => return Err(io::Error::other("ffmpeg stopped")),
                }
            }
            if need_key {
                let _ = link.send(b"K");
            }
        }
    })();
    let empty = {
        let mut links = sess.links();
        links.retain(|l| !Arc::ptr_eq(l, &link));
        links.is_empty()
    };
    if empty {
        sess.closed.store(true, Ordering::Relaxed);
        if let Err(e) = &result {
            if e.kind() != io::ErrorKind::UnexpectedEof && e.kind() != io::ErrorKind::ConnectionReset {
                let msg = e.to_string();
                sh.update(|st| st.last_error = msg);
            }
        }
    }
    result
}

fn run_pipeline(sh: &Arc<Shared>, sess: &Arc<Sess>, ffmpeg: &str, args: Vec<String>, rx: std::sync::mpsc::Receiver<Vec<u8>>) {
    let spawned = command(std::path::Path::new(ffmpeg)).args(args).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn();
    let mut child = match spawned {
        Ok(c) => c,
        Err(e) => {
            sh.update(|st| st.last_error = format!("ffmpeg: {e}"));
            disconnect();
            return finish(sh, sess);
        }
    };
    let mut ff_in = child.stdin.take().expect("stdin piped");
    let mut ff_out = child.stdout.take().expect("stdout piped");
    let writer = thread::spawn(move || {
        for pkt in rx {
            if ff_in.write_all(&pkt).is_err() {
                break;
            }
        }
    });
    // JPEG preview splitter: JPEG data never holds 0xFFD9 except as end marker.
    let sh2 = sh.clone();
    let preview = thread::spawn(move || {
        let mut buf: Vec<u8> = Vec::with_capacity(1 << 17);
        let mut chunk = [0u8; 32768];
        while let Ok(n) = ff_out.read(&mut chunk) {
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..n]);
            while let Some(end) = buf.windows(2).position(|w| w == [0xFF, 0xD9]) {
                let frame: Vec<u8> = buf.drain(..end + 2).collect();
                sh2.update(|st| {
                    st.jpeg = frame;
                    st.jpeg_seq += 1;
                });
            }
        }
    });

    let mut last_frames = 0;
    let mut last_bytes: Vec<(usize, u64)> = vec![];
    let mut tick = Instant::now();
    while !sess.closed.load(Ordering::Relaxed) {
        thread::sleep(Duration::from_millis(250));
        if tick.elapsed() < Duration::from_secs(1) {
            continue;
        }
        let secs = tick.elapsed().as_secs_f64();
        tick = Instant::now();
        let f = sess.frames.load(Ordering::Relaxed);
        let dropped = sess.merger.lock().unwrap_or_else(|e| e.into_inner()).dropped;
        let links: Vec<LinkStat> = sess
            .links()
            .iter()
            .map(|l| {
                let key = Arc::as_ptr(l) as usize;
                let b = l.bytes.load(Ordering::Relaxed);
                let prev = last_bytes.iter().find(|(k, _)| *k == key).map(|(_, v)| *v).unwrap_or(b);
                last_bytes.retain(|(k, _)| *k != key);
                last_bytes.push((key, b));
                LinkStat { via: l.via, kbps: ((b - prev) as f64 * 8.0 / 1000.0 / secs) as u32 }
            })
            .collect();
        sh.update(|st| {
            if let Some(x) = st.session.as_mut() {
                x.live_fps = ((f - last_frames) as f64 / secs).round() as u32;
                x.kbps = links.iter().map(|l| l.kbps).sum();
                x.frames = f;
                x.dropped = dropped;
                x.links = links;
            }
        });
        last_frames = f;
    }
    sess.feed.lock().unwrap_or_else(|e| e.into_inner()).take();
    let _ = child.kill();
    let _ = child.wait();
    let _ = writer.join();
    let _ = preview.join();
    finish(sh, sess);
}

fn finish(sh: &Arc<Shared>, sess: &Arc<Sess>) {
    sess.links().iter().for_each(|l| l.close());
    {
        let mut cur = current();
        if cur.as_ref().is_some_and(|x| Arc::ptr_eq(x, sess)) {
            *cur = None;
        }
    }
    sh.update(|st| {
        st.session = None;
        st.jpeg.clear();
        st.jpeg_seq += 1;
    });
}

fn ffmpeg_args(codec: u8, fps: u8, rot: u16, mirror: bool, vdev: Option<&str>) -> Vec<String> {
    let mut chain: Vec<&str> = vec![];
    match rot {
        90 => chain.push("transpose=1"),
        180 => chain.extend(["hflip", "vflip"]),
        270 => chain.push("transpose=2"),
        _ => {}
    }
    if mirror {
        chain.push("hflip");
    }
    let pre = if chain.is_empty() { "null".to_string() } else { chain.join(",") };
    let preview = "fps=10,scale=480:-2";
    let fc = if vdev.is_some() {
        format!("[0:v]{pre},format=yuv420p,split=2[a][b];[b]{preview}[p]")
    } else {
        format!("[0:v]{pre},{preview}[p]")
    };
    let mut a: Vec<String> = [
        "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay", "-probesize", "32",
        "-analyzeduration", "0", "-framerate",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    a.push(fps.to_string());
    a.extend(["-f".into(), if codec == 0 { "h264" } else { "hevc" }.into(), "-i".into(), "pipe:0".into()]);
    a.extend(["-filter_complex".into(), fc]);
    if let Some(dev) = vdev {
        a.extend(["-map", "[a]", "-c:v", "rawvideo", "-pix_fmt", "yuv420p", "-f", "v4l2"].map(String::from));
        a.push(dev.to_string());
    }
    a.extend(["-map", "[p]", "-c:v", "mjpeg", "-q:v", "7", "-f", "image2pipe", "pipe:1"].map(String::from));
    a
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn args_virtual_cam_and_preview() {
        let a = ffmpeg_args(0, 60, 90, true, Some("/dev/video42")).join(" ");
        assert!(a.contains("transpose=1,hflip,format=yuv420p,split=2"));
        assert!(a.contains("-f v4l2 /dev/video42"));
        assert!(a.contains("-framerate 60 -f h264"));
    }

    #[test]
    fn args_preview_only() {
        let a = ffmpeg_args(1, 30, 0, false, None).join(" ");
        assert!(a.contains("[0:v]null,fps=10"));
        assert!(a.contains("-f hevc"));
        assert!(!a.contains("v4l2"));
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
