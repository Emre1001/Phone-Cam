//! Link encryption. Each TCP link runs its own handshake:
//! phone and PC exchange ephemeral P-256 keys (ECDH), the PC signs the transcript with its
//! long-term identity key (the phone pins it), HKDF-SHA256 derives one AES-256-GCM key per
//! direction. Records: u32 length + ciphertext; the nonce is a per-direction counter, never sent.
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hkdf::Hkdf;
use p256::ecdh::EphemeralSecret;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::PublicKey;
use rand_core::OsRng;
use sha2::{Digest, Sha256};
use std::io::{self, Read, Write};
use std::path::Path;

pub const MAX_RECORD: usize = 16 << 20;
const POINT: usize = 65;

pub struct Identity {
    key: SigningKey,
    public: Vec<u8>,
}

impl Identity {
    /// Loads the PC identity key, or creates one on first start.
    pub fn load_or_create(path: &Path) -> Identity {
        let key = std::fs::read(path)
            .ok()
            .and_then(|b| SigningKey::from_slice(&b).ok())
            .unwrap_or_else(|| {
                let k = SigningKey::random(&mut OsRng);
                let _ = std::fs::write(path, k.to_bytes());
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
                }
                k
            });
        let public = key.verifying_key().to_encoded_point(false).as_bytes().to_vec();
        Identity { key, public }
    }

    /// "AB12 CD34 EF56 7890": first 8 bytes of SHA-256 over the public key. The phone shows the same.
    pub fn fingerprint(&self) -> String {
        fingerprint(&self.public)
    }
}

pub fn fingerprint(public: &[u8]) -> String {
    let h = Sha256::digest(public);
    h[..8].chunks(2).map(|c| format!("{:02X}{:02X}", c[0], c[1])).collect::<Vec<_>>().join(" ")
}

pub struct Sealer {
    aead: Aes256Gcm,
    ctr: u64,
}
pub struct Opener {
    aead: Aes256Gcm,
    ctr: u64,
}

fn nonce(ctr: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[4..].copy_from_slice(&ctr.to_be_bytes());
    n
}

impl Sealer {
    pub fn write(&mut self, w: &mut impl Write, plain: &[u8]) -> io::Result<()> {
        let ct = self
            .aead
            .encrypt(&Nonce::from(nonce(self.ctr)), plain)
            .map_err(|_| io::Error::other("encrypt"))?;
        self.ctr += 1;
        let mut buf = Vec::with_capacity(4 + ct.len());
        buf.extend_from_slice(&(ct.len() as u32).to_be_bytes());
        buf.extend_from_slice(&ct);
        w.write_all(&buf)
    }
}

impl Opener {
    pub fn read(&mut self, r: &mut impl Read) -> io::Result<Vec<u8>> {
        let mut len = [0u8; 4];
        r.read_exact(&mut len)?;
        let len = u32::from_be_bytes(len) as usize;
        if !(16..=MAX_RECORD + 16).contains(&len) {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "bad record length"));
        }
        let mut ct = vec![0u8; len];
        r.read_exact(&mut ct)?;
        let plain = self
            .aead
            .decrypt(&Nonce::from(nonce(self.ctr)), ct.as_slice())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "record authentication failed"))?;
        self.ctr += 1;
        Ok(plain)
    }
}

/// Derives (phone->pc, pc->phone) keys from the ECDH secret and the transcript.
pub fn derive(shared: &[u8], transcript: &[u8]) -> ([u8; 32], [u8; 32]) {
    let salt = Sha256::digest(transcript);
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared);
    let (mut a, mut b) = ([0u8; 32], [0u8; 32]);
    hk.expand(b"phonecam v2 phone->pc", &mut a).expect("hkdf");
    hk.expand(b"phonecam v2 pc->phone", &mut b).expect("hkdf");
    (a, b)
}

fn cipher(k: &[u8; 32]) -> Aes256Gcm {
    Aes256Gcm::new_from_slice(k).expect("key length")
}

/// PC side. Phone sends `"PCAM" 2 eph(65)`; PC answers `eph(65) identity(65) siglen u8 sig(DER)`.
/// Returns `Ok(None)` for a client that does not speak protocol 2.
pub fn server_handshake(s: &mut (impl Read + Write), id: &Identity) -> io::Result<Option<(Opener, Sealer)>> {
    let mut head = [0u8; 5];
    s.read_exact(&mut head)?;
    if &head[..4] != b"PCAM" || head[4] != 2 {
        return Ok(None);
    }
    let mut phone_eph = [0u8; POINT];
    s.read_exact(&mut phone_eph)?;
    let phone_pub = PublicKey::from_sec1_bytes(&phone_eph).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad key"))?;

    let eph = EphemeralSecret::random(&mut OsRng);
    let pc_eph = eph.public_key().to_encoded_point(false);
    let mut transcript = b"PCAM2".to_vec();
    transcript.extend_from_slice(&phone_eph);
    transcript.extend_from_slice(pc_eph.as_bytes());
    transcript.extend_from_slice(&id.public);
    let sig: Signature = id.key.sign(&transcript);
    let der = sig.to_der();

    let mut out = Vec::with_capacity(POINT * 2 + 80);
    out.extend_from_slice(pc_eph.as_bytes());
    out.extend_from_slice(&id.public);
    out.push(der.as_bytes().len() as u8);
    out.extend_from_slice(der.as_bytes());
    s.write_all(&out)?;

    let shared = eph.diffie_hellman(&phone_pub);
    let (p2c, c2p) = derive(&shared.raw_secret_bytes()[..], &transcript);
    Ok(Some((Opener { aead: cipher(&p2c), ctr: 0 }, Sealer { aead: cipher(&c2p), ctr: 0 })))
}

#[cfg(test)]
pub mod tests {
    use super::*;
    use p256::ecdsa::signature::Verifier;
    use p256::ecdsa::VerifyingKey;

    /// Minimal phone-side handshake, mirrors android/.../Crypto.kt.
    pub fn client(s: &mut (impl Read + Write)) -> io::Result<(Sealer, Opener, Vec<u8>)> {
        let eph = EphemeralSecret::random(&mut OsRng);
        let me = eph.public_key().to_encoded_point(false);
        let mut hello = b"PCAM\x02".to_vec();
        hello.extend_from_slice(me.as_bytes());
        s.write_all(&hello)?;
        let mut pc_eph = [0u8; POINT];
        let mut pc_id = [0u8; POINT];
        s.read_exact(&mut pc_eph)?;
        s.read_exact(&mut pc_id)?;
        let mut n = [0u8; 1];
        s.read_exact(&mut n)?;
        let mut der = vec![0u8; n[0] as usize];
        s.read_exact(&mut der)?;
        let mut transcript = b"PCAM2".to_vec();
        transcript.extend_from_slice(me.as_bytes());
        transcript.extend_from_slice(&pc_eph);
        transcript.extend_from_slice(&pc_id);
        let vk = VerifyingKey::from_sec1_bytes(&pc_id).map_err(|_| io::Error::other("key"))?;
        let sig = Signature::from_der(&der).map_err(|_| io::Error::other("sig"))?;
        vk.verify(&transcript, &sig).map_err(|_| io::Error::other("signature"))?;
        let shared = eph.diffie_hellman(&PublicKey::from_sec1_bytes(&pc_eph).unwrap());
        let (p2c, c2p) = derive(&shared.raw_secret_bytes()[..], &transcript);
        Ok((Sealer { aead: cipher(&p2c), ctr: 0 }, Opener { aead: cipher(&c2p), ctr: 0 }, pc_id.to_vec()))
    }

    #[test]
    fn handshake_and_records() {
        let dir = std::env::temp_dir().join(format!("pcam-test-{}", std::process::id()));
        let id = Identity::load_or_create(&dir);
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        let t = std::thread::spawn(move || {
            let mut c = std::net::TcpStream::connect(addr).unwrap();
            let (mut seal, mut open, pc_id) = client(&mut c).unwrap();
            seal.write(&mut c, b"hello pc").unwrap();
            assert_eq!(open.read(&mut c).unwrap(), b"hello phone");
            pc_id
        });
        let (mut s, _) = l.accept().unwrap();
        let (mut open, mut seal) = server_handshake(&mut s, &id).unwrap().unwrap();
        assert_eq!(open.read(&mut s).unwrap(), b"hello pc");
        seal.write(&mut s, b"hello phone").unwrap();
        assert_eq!(t.join().unwrap(), id.public);
        assert_eq!(id.fingerprint().len(), 19);
        let _ = std::fs::remove_file(dir);
    }

    #[test]
    fn tampered_record_is_rejected() {
        let (a, b) = derive(&[7u8; 32], b"t");
        let mut seal = Sealer { aead: cipher(&a), ctr: 0 };
        let mut open = Opener { aead: cipher(&a), ctr: 0 };
        let mut buf = vec![];
        seal.write(&mut buf, b"frame").unwrap();
        let last = buf.len() - 1;
        buf[last] ^= 1;
        assert!(open.read(&mut &buf[..]).is_err());
        assert_ne!(a, b);
    }

    #[test]
    fn replayed_record_is_rejected() {
        let (a, _) = derive(&[9u8; 32], b"t");
        let mut seal = Sealer { aead: cipher(&a), ctr: 0 };
        let mut open = Opener { aead: cipher(&a), ctr: 0 };
        let mut buf = vec![];
        seal.write(&mut buf, b"one").unwrap();
        let copy = buf.clone();
        assert_eq!(open.read(&mut &buf[..]).unwrap(), b"one");
        assert!(open.read(&mut &copy[..]).is_err());
    }
}
