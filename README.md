# Phone-Cam

Handy als echte Webcam für den PC. Eine App am Handy, eine am PC. Verbindung über **USB-Kabel**, **WLAN** oder **beides gleichzeitig** für maximale Bandbreite. Jede Verbindung ist **verschlüsselt** (ECDH P-256, AES-256-GCM). Bis **4K bei 60 fps**, wenn Kamera und Hardware-Encoder des Handys es können.

- **PC-App**: eine einzige Datei (Rust, ~700 KB, nur RustCrypto-Krates). Kein Electron. Die Oberfläche läuft in einem Chrome-/Edge-App-Fenster, das schon installiert ist.
- **Handy-App**: native Android-App (Kotlin, Camera2 + MediaCodec), APK unter 200 KB.
- **Virtuelle Kamera**: auf Linux als `Phone-Cam` (v4l2loopback), in jeder Video-App wählbar.

## Status

| | Linux | Windows |
|---|---|---|
| Stream, Vorschau, USB, WLAN, USB+WLAN, Verschlüsselung, PIN | getestet | gebaut in CI, nicht getestet |
| Virtuelle Kamera | ja (v4l2loopback) | **nicht enthalten**: ein DirectShow-/Media-Foundation-Treiber fehlt noch |

Getestet: PC-Kern mit simuliertem 4K@60-Sender und verschlüsseltem USB+LAN-Bonding (1080p60, 0 Drops), Android-Emulator mit USB+WLAN gleichzeitig inkl. WLAN-Ausfall und Wiederverbinden, Schlüssel-Abfrage bei unbekanntem PC, PC-Oberfläche ohne Layoutfehler in 320 bis 1920 px, Hell/Dunkel, Deutsch/Türkisch. **Nicht getestet**: echtes Handy, 4K60 auf echter Hardware, echtes WLAN, echte Video-App am v4l2loopback-Gerät.

## Benutzen

**PC** (Linux): `ffmpeg` installieren, `phone-cam` starten. Unter „Virtuelle Kamera“ einmal *Einrichten* klicken (lädt `v4l2loopback`, fragt per polkit nach dem Passwort). Von Hand:

```bash
sudo modprobe v4l2loopback exclusive_caps=1 card_label=Phone-Cam video_nr=42
```

**USB**: USB-Debugging am Handy an, Kabel stecken, `adb` (Android platform-tools) auf dem PC. Die PC-App richtet `adb reverse tcp:47800 tcp:47800` selbst ein und kann die Handy-App per Knopf starten. Kein WLAN nötig, keine PIN.

**WLAN**: Handy-App auf *WLAN*, PC wird per Broadcast gefunden (oder Adresse eintragen), PIN aus der PC-App eingeben. Beim ersten Verbinden zeigt das Handy den Schlüssel des PCs: Er muss mit dem *Schlüssel* in der PC-App übereinstimmen. Über USB wird der Schlüssel automatisch gemerkt.

**USB + WLAN**: Kabel und WLAN gleichzeitig. Das Handy verteilt die Bilder auf beide Wege (der weniger volle bekommt das nächste Bild), der PC setzt sie in der richtigen Reihenfolge zusammen. Fällt ein Weg aus, läuft der Stream über den anderen weiter, der ausgefallene verbindet sich von selbst neu.

Handy so halten, wie du es nutzt, dann *Starten*. Die Drehung wird beim Start gelesen. Feinkorrektur und Spiegeln in den PC-Einstellungen.

## Bauen

```bash
cd pc && cargo build --release          # Tests: cargo test --release
cd android && ./gradlew assembleRelease # braucht Android SDK 36, JDK 17
```

Release: Tag `vX.Y.Z` pushen, `.github/workflows/release.yml` hängt `phone-cam-pc-linux-x86_64`, `phone-cam-pc-windows-x86_64.exe` und `phone-cam-android.apk` an. Beide Apps prüfen GitHub Releases und bieten Updates an. APKs müssen immer mit demselben Schlüssel signiert sein (Secrets `PHONECAM_KEYSTORE_B64`, `PHONECAM_KEYSTORE_PASSWORD`, `PHONECAM_KEY_ALIAS`, `PHONECAM_KEY_PASSWORD`).

## Aufbau

`pc/src`: `cam.rs` (Sessions, Links, ffmpeg-Pipeline), `crypto.rs` (Handshake, AES-GCM), `merge.rs` (Reihenfolge über mehrere Links), `watch.rs` (adb, v4l2loopback, IPs), `http.rs` (Oberfläche auf `127.0.0.1:47810`), `discovery.rs`, `update.rs`. Protokoll: [docs/PROTOCOL.md](docs/PROTOCOL.md). `android/app/src/main/java/com/phonecam`: `StreamService.kt` (Kamera, Encoder, Session), `Bond.kt` (Verteilung auf Links), `Crypto.kt`, `Link.kt`, `Caps.kt` (bester Modus), `MainActivity.kt`.

## Grenzen

- Kein Ton.
- Verschlüsselung: Wer beim ersten WLAN-Verbinden den Schlüssel nicht vergleicht, ist gegen einen aktiven Angreifer im selben Netz nicht geschützt. Erste Kopplung per USB ist sicher. Lokale Programme auf dem PC (Loopback) brauchen keine PIN.
- USB+WLAN: Weil WLAN langsamer antwortet, kann die Verzögerung etwas steigen.
- Ein Handy gleichzeitig.
- Eingebauter Softwarefall: ohne Hardware-Encoder nur bis 1080p.

MIT-Lizenz.
