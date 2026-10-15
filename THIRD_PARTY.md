# Third-party software

choifile bundles the libraries below. Versions are the ones pinned in `app/build.gradle.kts`.
Licenses are listed as published by each project; check the upstream repositories for the authoritative text.

## Shipped in the app

| Library | Version | Used for | License |
|---|---|---|---|
| Apache Commons Compress | 1.28.0 | zip, 7z, tar, gzip, bzip2 reading and zip/7z/tar.gz writing | Apache-2.0 |
| XZ for Java (org.tukaani:xz) | 1.10 | xz / 7z LZMA support | 0BSD (public domain equivalent) |
| junrar | 7.5.5 | rar reading (read-only) | UnRAR license (see note) |
| sshj | 0.40.0 | SFTP | Apache-2.0 |
| Bouncy Castle (bcprov, bcpkix) | 1.80 | cryptography for sshj / smbj | MIT-style (Bouncy Castle License) |
| smbj | 0.14.0 | SMB2/3 | Apache-2.0 |
| Apache Commons Net | 3.11.1 | FTP / FTPS | Apache-2.0 |
| OkHttp | 4.12.0 | WebDAV client, Tailscale API calls | Apache-2.0 |
| SLF4J (slf4j-nop) | 2.0.17 | silences library logging (no log output at all) | MIT |
| AndroidX (Compose, WorkManager, Biometric, Lifecycle, Core, Fragment, Activity) | see build file | UI, background index refresh | Apache-2.0 |
| Coil | 3.3.0 | image thumbnails | Apache-2.0 |
| AndroidX Media3 (exoplayer, ui, session) | 1.8.0 | video and music playback, container extractors, subtitle parsers, media notification | Apache-2.0 |
| NextLib media3ext (io.github.anilbeesetti:nextlib-media3ext) | 1.8.0-0.9.0 | FFmpeg audio/video decoders for Media3 | **GPL-3.0** (see note) |
| ↳ FFmpeg (bundled native libavcodec, libavutil, libswresample, libswscale) | 6.0 | software decoding | LGPL-3.0-or-later as built (`--enable-version3`, no `--enable-gpl`) |
| ↳ libvpx (bundled native) | 1.13.0 | VP8 / VP9 decoding | BSD-3-Clause |
| ↳ Mbed TLS (bundled native) | 3.4.1 | linked into the FFmpeg build (unused by choifile) | Apache-2.0 |

Note on the FFmpeg decoders (added in v0.4.3): Media3 does not publish its FFmpeg extension as a binary, so a
prebuilt one is used. Two exist on Maven Central for Media3 1.8.0, and both are published under GPL-3.0:
`org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` (audio only: FLAC, ALAC, MP3, AAC, AC3, E-AC3, DTS, TrueHD/MLP,
PCM A-law/µ-law) and `io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0` (the one choifile ships: the same audio
set plus Vorbis, Opus and AMR, and video decoders for H.264, HEVC, MPEG-2, VP8 and VP9). FFmpeg itself is built
LGPL-3.0, but the Java/JNI wrapper around it is GPL-3.0, so **an APK that contains it is a combined work under
GPL-3.0**: whoever distributes that APK must offer the complete corresponding source of the whole app under
GPL-3.0-compatible terms. If that is not acceptable for a release, remove the dependency from `app/build.gradle.kts`
and replace `NextRenderersFactory` with `DefaultRenderersFactory` in `ui/VideoPlayer.kt`; playback then falls back to
the device's own decoders only. Not covered by either build: APE (Monkey's Audio), WMA/WMV, RealMedia, and AV1 in
software (AV1 still plays where the device has a hardware decoder).

Added in v0.3.0: no new third-party library. The PDF viewer uses the platform `android.graphics.pdf.PdfRenderer`,
the Markdown viewer, the HTTP file server and the FTP file server are small implementations written for this app
(NanoHTTPD and Apache FtpServer were considered and not used to keep the APK and attack surface small).
Scheduled automation rules use AndroidX WorkManager, which is already listed above; the file-server foreground
service uses AndroidX Core (NotificationCompat). The FTP server round-trip tests use Apache Commons Net, already listed.

Note on junrar: it is derived from the freely redistributable UnRAR source, whose license allows decompression
code to be used and distributed but forbids using that source to re-create the RAR compression algorithm.
choifile only reads rar archives and never creates them. The terms are not a standard OSI license; review them
before redistributing the app commercially.

## Test-only (not in the APK)

| Library | Used for | License |
|---|---|---|
| JUnit 4 | unit tests | EPL-1.0 |
| Apache MINA SSHD (sshd-sftp) | in-process SFTP server for round-trip tests | Apache-2.0 |
| MockFtpServer | in-process FTP server for round-trip tests | Apache-2.0 |
| OkHttp MockWebServer | scripted WebDAV / Tailscale API server | Apache-2.0 |
| sqlite-jdbc (xerial) | runs the real search-index SQL on a JVM | Apache-2.0 |
| org.json | JSON on the JVM test classpath | JSON license (public domain style) |

Added in v0.6.0: AndroidX Media3 session (above) for the music player's notification and lock screen controls. The
built-in e-reader (`ebook/`) and its page-turn sounds (`res/raw/page_flip_*.ogg`) come from the author's own
choireader project; the two serif fonts that project bundles are not included.
