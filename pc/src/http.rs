use crate::state::Shared;
use crate::util::{form_get, UI_PORT};
use crate::{assets, cam, settings, update, util, watch};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub fn serve(sh: Arc<Shared>, l: TcpListener) {
    for conn in l.incoming().flatten() {
        let sh = sh.clone();
        thread::spawn(move || {
            let _ = handle(&sh, conn);
        });
    }
}

struct Req {
    method: String,
    path: String,
    host: String,
    origin: Option<String>,
    x_pc: bool,
    body: String,
}

fn read_req(s: &TcpStream) -> io::Result<Req> {
    let mut r = BufReader::new(s.try_clone()?);
    let mut line = String::new();
    r.by_ref().take(4096).read_line(&mut line)?;
    let mut it = line.split_whitespace();
    let (method, path) = (it.next().unwrap_or("").to_string(), it.next().unwrap_or("/").to_string());
    let (mut host, mut origin, mut x_pc, mut len) = (String::new(), None, false, 0usize);
    loop {
        let mut h = String::new();
        r.by_ref().take(8192).read_line(&mut h)?;
        let h = h.trim_end();
        if h.is_empty() {
            break;
        }
        if let Some((k, v)) = h.split_once(':') {
            let v = v.trim();
            match k.to_ascii_lowercase().as_str() {
                "host" => host = v.to_string(),
                "origin" => origin = Some(v.to_string()),
                "x-pc" => x_pc = v == "1",
                "content-length" => len = v.parse().unwrap_or(0).min(8192),
                _ => {}
            }
        }
    }
    let mut body = vec![0u8; len];
    r.read_exact(&mut body)?;
    Ok(Req { method, path, host, origin, x_pc, body: String::from_utf8_lossy(&body).into_owned() })
}

fn send(s: &mut TcpStream, status: &str, ctype: &str, body: &[u8]) -> io::Result<()> {
    write!(
        s,
        "HTTP/1.1 {status}\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n",
        body.len()
    )?;
    s.write_all(body)
}

fn handle(sh: &Arc<Shared>, mut s: TcpStream) -> io::Result<()> {
    s.set_read_timeout(Some(Duration::from_secs(10)))?;
    let req = read_req(&s)?;
    // DNS-rebinding guard: only our own loopback names.
    if req.host != format!("127.0.0.1:{UI_PORT}") && req.host != format!("localhost:{UI_PORT}") {
        return send(&mut s, "403 Forbidden", "text/plain", b"forbidden");
    }
    let path = req.path.split('?').next().unwrap_or("/");
    if req.method == "POST" {
        let own = |o: &str| o == format!("http://127.0.0.1:{UI_PORT}") || o == format!("http://localhost:{UI_PORT}");
        if !req.x_pc || req.origin.as_deref().is_some_and(|o| !own(o)) {
            return send(&mut s, "403 Forbidden", "text/plain", b"forbidden");
        }
        return post(sh, &mut s, path, &req.body);
    }
    match path {
        "/api/state" => send(&mut s, "200 OK", "application/json", sh.to_json().as_bytes()),
        "/api/events" => events(sh, s),
        "/preview.mjpeg" => preview(sh, s),
        p => match assets::get(p) {
            Some((ctype, body)) => send(&mut s, "200 OK", ctype, body),
            None => send(&mut s, "404 Not Found", "text/plain", b"not found"),
        },
    }
}

fn post(sh: &Arc<Shared>, s: &mut TcpStream, path: &str, body: &str) -> io::Result<()> {
    let ok = |s: &mut TcpStream| send(s, "200 OK", "application/json", b"{\"ok\":true}");
    match path {
        "/api/settings" => {
            sh.update(|st| {
                if let Some(n) = form_get(body, "name").map(|n| n.trim().chars().take(40).collect::<String>()).filter(|n| !n.is_empty()) {
                    st.name = n;
                }
                if let Some(r) = form_get(body, "rotation").and_then(|r| r.parse::<u16>().ok()).filter(|r| [0, 90, 180, 270].contains(r)) {
                    st.rotation = r;
                }
                if let Some(m) = form_get(body, "mirror") {
                    st.mirror = m == "1";
                }
                settings::save(st);
            });
            ok(s)
        }
        "/api/pin/new" => {
            sh.update(|st| {
                st.pin = util::random_pin();
                settings::save(st);
            });
            ok(s)
        }
        "/api/disconnect" => {
            cam::disconnect();
            ok(s)
        }
        "/api/vcam/setup" => {
            let persist = form_get(body, "persist").as_deref() == Some("1");
            let sh = sh.clone();
            thread::spawn(move || watch::setup_vcam(&sh, persist));
            ok(s)
        }
        "/api/usb/launch" => {
            let serial = form_get(body, "serial").unwrap_or_default();
            let known = sh.read(|st| st.usb.iter().any(|u| u.serial == serial));
            if known {
                thread::spawn(move || {
                    watch::launch_phone_app(&serial);
                });
            }
            ok(s)
        }
        "/api/update/install" => {
            let url = form_get(body, "url").unwrap_or_default();
            let size = form_get(body, "size").and_then(|v| v.parse().ok()).unwrap_or(0);
            let digest = form_get(body, "digest").unwrap_or_default();
            let sh = sh.clone();
            thread::spawn(move || update::install(&sh, url, size, digest));
            ok(s)
        }
        "/api/quit" => {
            sh.update(|st| st.quit = true);
            ok(s)
        }
        _ => send(s, "404 Not Found", "text/plain", b"not found"),
    }
}

fn events(sh: &Arc<Shared>, mut s: TcpStream) -> io::Result<()> {
    s.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")?;
    let mut seen = 0;
    loop {
        let v = sh.wait_version(seen, 15000);
        if sh.read(|st| st.quit) {
            return Ok(());
        }
        if v != seen {
            seen = v;
            write!(s, "data: {}\n\n", sh.to_json())?;
        } else {
            s.write_all(b": ping\n\n")?;
        }
    }
}

fn preview(sh: &Arc<Shared>, mut s: TcpStream) -> io::Result<()> {
    s.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=f\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")?;
    let (mut seq, mut idle) = (0, 0);
    loop {
        match sh.wait_jpeg(seq, 3000) {
            Some((n, jpg)) => {
                seq = n;
                idle = 0;
                write!(s, "--f\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n", jpg.len())?;
                s.write_all(&jpg)?;
                s.write_all(b"\r\n")?;
            }
            None => {
                idle += 1;
                if idle >= 2 && sh.read(|st| st.session.is_none() || st.quit) {
                    return Ok(());
                }
            }
        }
    }
}
