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
| AndroidX Media3 | 1.8.0 | video playback | Apache-2.0 |

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
