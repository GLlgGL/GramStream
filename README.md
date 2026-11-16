# GramStream

GramStream is an Android app (phone and Android TV) that turns your Telegram
channels into a **Stremio addon** that runs on your own device. You log in with
your Telegram account, pick the channels that hold your videos, and the app runs
a small HTTP server that Stremio can install from a manifest link. Videos stream
directly from Telegram and support seeking. You don't need a VPS or a PC.

To use it outside your home network, the app has a built-in **Cloudflare
Tunnel** that gives you a public `https://….trycloudflare.com` URL with one tap.

---

## Features

### Telegram login
- On first launch you enter your own **API ID / API Hash** from
  [my.telegram.org](https://my.telegram.org). They are stored on the device,
  never hard-coded (`ApiCredentialsActivity.kt`).
- Phone number → login code → 2FA password, driven by TDLib's auth state
  machine (`LoginActivity.kt`, `TdLibManager.kt`).
- When you're already logged in, the app goes straight to the main screen.

### Stremio addon server
- An embedded [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) server
  (`AddonServer.kt`) runs inside a foreground service
  (`AddonForegroundService.kt`) on port **11470** by default. The port can be
  changed.
- Endpoints:

  | Path | Purpose |
  |---|---|
  | `/manifest.json` | Addon manifest (catalog, stream, meta; id prefixes `tg_`, `tgz:`, `tt`) |
  | `/catalog/movie/latest[/search=…].json` | "Telegram" catalog: latest videos, with search and paging |
  | `/meta/movie/<id>.json` | Metadata, enriched with TMDB when a key is set |
  | `/stream/movie/<id>.json` | Stream links for Telegram ids **and** IMDb ids (`tt…`) |
  | `/file/<chatId>_<messageId>` | Streams a single video with HTTP Range support |
  | `/zipfile/<chatId>/<base>` | Streams a video stored inside split `.zip.001/.002/…` archives |

- CORS headers are set on every response, so Stremio Web works too.

### Catalog and search (`CatalogRepository.kt`)
- Lists and searches video messages in your channels through TDLib.
- **Multi-channel:** you add channels by numeric chat ID on the *Channels* tab.
- **Global Search** (optional): searches every chat you've joined instead of
  only the channels you added.
- Split `.zip.NNN` volumes are grouped into one catalog entry
  (`ZipVolumeResolver.groupVolumes`).
- Posters and metadata come from **TMDB** when you set an API key. Enrichment
  runs in the background, and the catalog is cached for 15 minutes.

### IMDb-id stream resolution
When Stremio asks for streams for a Cinemeta item such as
`/stream/movie/tt0398286.json`:
1. `TmdbClient.findByImdbId` resolves the IMDb id to a title and year.
2. Query templates from `assets/remote_config.json` (`"{title} {year}"`,
   `"{title}"`) build the Telegram search strings, which are tried in order.
3. `Fuzzy.similarity` (token overlap plus Levenshtein ratio) filters the
   results using `title_threshold_fuzzy`.
4. Each match is returned as a `/file/...` or `/zipfile/...` stream (up to
   `max_streams`).

### Streaming engine
- **Progressive streaming with seeking** (`ProgressiveFileInputStream.kt`):
  it starts the TDLib download at the requested byte offset and serves bytes
  as they arrive. A seek starts a new download at the new offset.
- **Multi-volume zip streaming** (`ZipVolumeResolver.kt`,
  `MultiVolumeProgressiveInputStream.kt`):
  - It reads the zip local file header of volume 1 to find where the video
    data starts. Resolved plans are cached.
  - It presents all volumes to the player as one continuous byte range.
  - It **prefetches the next volume** once playback gets within about 8 MB of
    the end of the current one, so playback doesn't stall at the volume
    boundary.

### Cloudflare Tunnel (`CloudflaredTunnel.kt`)
- The `cloudflared` binary is bundled as `jniLibs/<abi>/libcloudflared.so`.
  The `.so` name and `useLegacyPackaging true` make Android extract it to
  `nativeLibraryDir`, where it is allowed to run.
- Tapping **Start Cloudflare Tunnel** on the Home tab runs
  `cloudflared tunnel --url http://127.0.0.1:<port>` (IPv4, http2). The app
  reads the `*.trycloudflare.com` URL from its output and saves it as the
  public host.
- While the tunnel runs, the manifest link and QR code switch to
  `https://<tunnel>/manifest.json`, so you can install the addon from
  anywhere. Stopping the tunnel switches back to the LAN address.
- Quick tunnels need no Cloudflare account, but the URL **changes every time
  the tunnel starts**. Reinstall the addon in Stremio after a restart.

### Home tab
- Server on/off switch with a status indicator.
- Manifest link with a **Copy** button and a **QR code**. When no tunnel is
  running, the LAN IP is detected automatically. The link and QR code refresh
  when the network changes, for example on a Wi-Fi ↔ mobile data handover.
- Start/stop the Cloudflare Tunnel.
- **Allow running in background**: asks Android to exempt the app from
  battery optimization (on TV this is already allowed).
- Clear the catalog cache and TDLib storage.
- Shows the Telegram account that is logged in.

### Config tab
- **Global Search**, **Wi-Fi only** mode and **Auto-start on boot** switches.
- Max cache size (MB, default 2048), buffer size (MB, default 20), server
  port and TMDB API key.

### Settings tab
- In-app debug log viewer (last 500 entries) with **Copy logs**. Long-press
  the log window to open a storage diagnostics report.
- **Backup settings** to a JSON file (API credentials, TMDB key, channels and
  config). To restore it on a new install, tap **Restore** on the first-launch
  screen. That fills in your API ID/Hash and brings back your channels and
  config. You still log in to Telegram again, because the session isn't part
  of the backup.
- Log out of Telegram.

### Reliability
- **Foreground service** (`dataSync`) keeps the server alive in the
  background.
- **Network awareness:** the service watches the default network. In Wi-Fi
  only mode it stops itself when Wi-Fi is lost. Handovers are debounced, so a
  brief gap while switching networks doesn't stop it.
- **Boot resilience** (`BootReceiver.kt`): when auto-start is on, the service
  restarts after `BOOT_COMPLETED` / `QUICKBOOT_POWERON`. The service also
  starts TDLib itself on a cold start, so catalogs aren't empty after a reboot.
- **Storage limits:** a 60-second timer plus read-based cleanup keep the TDLib
  cache under the configured max size.

### Android TV
- Leanback launcher entry and TV banner. Touchscreen is not required.
- Landscape layout on TV, portrait on phones.
- Native libraries for `arm64-v8a` and `armeabi-v7a`, which covers 32-bit TV
  boxes.

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/remote_config.json          # search templates, thresholds, video extensions
├── jniLibs/<abi>/libtdjni.so          # TDLib native library
├── jniLibs/<abi>/libcloudflared.so    # cloudflared binary (Cloudflare Tunnel)
├── java/org/drinkless/tdlib/          # TDLib Java bindings (Client, TdApi)
└── java/com/example/GramStream/
    ├── ApiCredentialsActivity.kt      # first launch: API ID / Hash
    ├── LoginActivity.kt               # phone / code / 2FA
    ├── MainActivity.kt                # bottom nav host
    ├── HomeFragment.kt  ChannelsFragment.kt  ConfigFragment.kt  SettingsFragment.kt
    ├── AddonForegroundService.kt      # runs the server, network watcher, storage timer
    ├── AddonServer.kt                 # Stremio addon HTTP endpoints
    ├── CatalogRepository.kt           # catalog / meta / stream JSON
    ├── TdLibManager.kt                # TDLib client, auth, downloads, storage
    ├── ProgressiveFileInputStream.kt
    ├── MultiVolumeProgressiveInputStream.kt
    ├── ZipVolumeResolver.kt
    ├── CloudflaredTunnel.kt
    ├── TmdbClient.kt  Fuzzy.kt  RemoteConfig.kt
    ├── Settings.kt  DebugLog.kt  NetworkStateNotifier.kt  BootReceiver.kt
    └── ChannelAdapter.kt
```

---

## Building

Requirements: Android Studio, JDK 17, Android SDK 34 (`minSdk 24`).

1. **TDLib:** `libtdjni.so` and the `org.drinkless.tdlib` Java sources are
   already in the repo. To rebuild them, follow [BUILD_TDLIB.md](BUILD_TDLIB.md).
2. **cloudflared:** `libcloudflared.so` is the Linux/Android `cloudflared`
   binary for each ABI (`linux-arm64` → `arm64-v8a`, `linux-arm` →
   `armeabi-v7a`), renamed with a `.so` extension. To update it, download a
   newer release from
   [cloudflare/cloudflared releases](https://github.com/cloudflare/cloudflared/releases)
   and replace the files.
3. Open the project in Android Studio, let Gradle sync, then use
   **Build → Build APK(s)**.

## Usage

1. Install the APK and enter your Telegram **API ID / API Hash**.
2. Log in with your phone number, the login code and your 2FA password if you
   have one.
3. **Channels** tab: add the numeric chat ID of each channel with videos (for
   example `-100…`), or turn on Global Search in **Config**.
4. Optional: add a **TMDB API key** in **Config** for posters and IMDb-id
   matching. IMDb-id matching needs it.
5. **Home** tab: turn the server on, and start the Cloudflare Tunnel if you
   want access from outside your network.
6. In Stremio, open **Addons**, paste the manifest link (or scan the QR code)
   and install.

---

## Known limitations
- Only the `movie` type is exposed. Series templates exist in
  `remote_config.json`, but series/episode streams aren't wired up yet.
- Restore needs a system file picker. Some Android TV boxes don't have one,
  so on those, restore on a phone or install a file manager.
- Quick-tunnel URLs change on every start. A named Cloudflare tunnel with a
  fixed hostname isn't supported yet.
- `ZipVolumeResolver` assumes a standard (non-ZIP64) local file header and a
  single entry per archive, i.e. one movie split and zipped for size.
- If the zip used a data descriptor (compressed size unknown at write time),
  the entry length falls back to "everything after the header". The stream
  may then include a few trailing zip bytes, which most players tolerate.
- Stream responses are always sent as `video/x-matroska`.
- Not built yet: anime/Kitsu matching and an OTA self-updater.

---

Vibe coded by [GLlgGL](https://github.com/GLlgGL)
