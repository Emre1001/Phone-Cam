# Phone-Cam protocol v2

TCP, phone to PC, port **47800**. Integers big-endian. Every link is encrypted. One session can use several links at once (USB + Wi-Fi).

## Transport

- **USB (adb)**: PC runs `adb reverse tcp:47800 tcp:47800`; phone connects to `127.0.0.1:47800`. Loopback peers need no PIN.
- **Wi-Fi / LAN**: phone connects to `PC-IP:47800` (socket bound to the Wi-Fi network). PIN required.
- **USB + Wi-Fi**: first link starts the session, the second link joins it. Frames are spread over both.
- **Discovery**: phone sends UDP `PCAM?` to broadcast `:47801`; PC answers `PCAM!\n<pc name>\n47800`.

## Handshake (per link)

1. Phone: `"PCAM"`, `0x02`, ephemeral P-256 public key (65 bytes, uncompressed SEC1).
2. PC: ephemeral key (65), identity key (65), `u8` length + ECDSA-SHA256 signature (DER) over
   `transcript = "PCAM2" || phone_eph || pc_eph || pc_identity`.
3. Both: `shared = ECDH(eph, eph)`, `salt = SHA-256(transcript)`,
   `k_p2c = HKDF-SHA256(salt, shared, "phonecam v2 phone->pc")`, `k_c2p = … "phonecam v2 pc->phone"` (32 bytes each).

The phone verifies the signature and **pins the PC identity key**: keys seen over USB are trusted automatically, an unknown key over Wi-Fi needs the user to compare the fingerprint (`SHA-256(identity)[0..8]`, shown as `AB12 CD34 EF56 7890` in both apps). The PIN is only sent after that.

## Records

After the handshake everything is `u32 length` + AES-256-GCM ciphertext (16-byte tag). Nonce = 4 zero bytes + `u64` record counter, one counter per direction per link, never sent. A replayed, reordered or modified record fails authentication and closes the link.

## Session messages (inside records)

Phone, first record on a link:

- New session: `0`, pin (`u8` len + UTF-8), name (`u8` len + UTF-8), codec `u8` (0 H.264, 1 HEVC), width `u16`, height `u16`, fps `u8`, rotation `u16` (degrees clockwise to upright), mirror `u8`.
- Join: `1`, session id (16 bytes).

PC answer: status `u8`: `0` ok, `1` wrong PIN, `2` busy, `3` bad request, `4` no ffmpeg, `5` unknown session, `6` PIN locked (5 wrong PINs: 30 s). On a new session `0` is followed by the session id (16) and `u8` count + IPv4 addresses (4 bytes each) for joining over Wi-Fi.

Frames, phone to PC: `type u8` (1 config, 2 key, 3 delta), `seq u32`, `pts_us u64`, Annex-B payload. `seq` counts across all links; the PC reorders, waits at most 150 ms for a missing frame, then continues at the next key frame and sends `K`. Key frames carry SPS/PPS(/VPS).

Control, PC to phone: `K` key frame request, `B` + `u32` bitrate in kbit/s.
