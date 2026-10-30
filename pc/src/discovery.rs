use crate::state::Shared;
use crate::util::{CAM_PORT, DISCOVERY_PORT};
use std::net::UdpSocket;
use std::sync::Arc;

/// Answers `PCAM?` broadcasts from phones with the PC name and the camera port.
pub fn run(sh: Arc<Shared>) {
    let Ok(sock) = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) else { return };
    let mut buf = [0u8; 64];
    while let Ok((n, from)) = sock.recv_from(&mut buf) {
        if &buf[..n] == b"PCAM?" {
            let name = sh.read(|s| s.name.clone());
            let _ = sock.send_to(format!("PCAM!\n{name}\n{CAM_PORT}").as_bytes(), from);
        }
    }
}
