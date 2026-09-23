# GramStream

Updated after decompiling the real GramStream v1.0.5 APK (strings/class
names only - no method bodies were extracted, since that needs full
decompilation tooling this environment can't run). Everything below marked
"confirmed" comes from that; everything else is my own implementation
filling the gaps.

## Fixed from the decompile
- **TDLib namespace corrected**: `org.drinkless.tdlib.TdApi` / `Client`
  (confirmed) - the earlier scaffold used the older
  `org.drinkless.td.libcore.telegram` path. Update `BUILD_TDLIB.md`'s
  output copy step accordingly if you already built TDLib once.

## What's implemented now

**Login** - phone → code → 2FA, driven by TDLib's auth state machine
(`LoginActivity.kt`, `TdLibManager.kt`)

**Manifest link UI** - shown on the main screen with a Copy button, LAN IP
auto-detected (`MainActivity.kt`)

**Catalog** (`CatalogRepository.buildCatalogJson`) - lists + searches your
channel's video messages via TDLib's own `SearchChatMessages`. Split
`.zip.NNN` volumes are grouped into a single catalog entry
(`ZipVolumeResolver.groupVolumes`), matching the real app's behavior of
not showing each part as its own row.

**IMDB-id stream resolution** (`CatalogRepository.buildStreamJsonForImdb`)
- this is the flow your actual VPS server's logs showed in production:
  GramStream calls `/stream/movie/tt0398286.json` using an id from Cinemeta,
  not our own catalog id. So:
  1. `TmdbClient.findByImdbId` resolves `tt0398286` → title + year, using
     the exact `api.themoviedb.org/3/find/...` endpoint confirmed in the
     APK's strings
  2. `RemoteConfig`'s query templates (`"{title} {year}"`, `"{title}"` -
     same structure as the real `remote_config.json` we extracted) build
     search strings, tried in order
  3. `Fuzzy.similarity` filters results against `title_threshold_fuzzy`
     (the actual algorithm in the real `Fuzzy.kt` wasn't extracted - this
     is a standard Levenshtein-ratio implementation filling that gap, not
     a decompiled one)
  4. Matches route to `/file/...` or `/zipfile/...` depending on whether
     they're a split archive

**Progressive streaming + seeking** (`ProgressiveFileInputStream.kt`) -
unchanged from before: polls TDLib's download progress, redirects on seek.

**Multi-volume zip streaming with prefetch** (`ZipVolumeResolver.kt`,
`MultiVolumeProgressiveInputStream.kt`) - this is the piece built
specifically for the stall bug you hit on the VPS server. It:
1. Downloads just the first ~64 bytes of volume 1 to read the zip local
   file header and find where video data actually starts (skipping past
   the header)
2. Presents all volumes as one continuous byte range to the HTTP response
3. **Proactively calls `TdLibManager.startDownload()` on the next volume
   once playback gets within 8MB of the current volume's end** - so by
   the time the player crosses the seam, the next volume has often
   already started downloading, instead of cold-starting right at the
   boundary (which is the likely cause of the stall you saw on the VPS
   version, and this Android version reimplements the fix rather than
   just diagnosing it)

**Boot resilience** (`BootReceiver.kt`) - confirmed from the manifest:
the real app restarts its service after a reboot via
`BOOT_COMPLETED`/`QUICKBOOT_POWERON`. Implemented the same way here.

## Still not built (confirmed present in the real app, not here)
- **Zip-entry-aware `StreamingProxy`** - the real app has a dedicated
  class for this (`com.GramStream.stream.StreamingProxy` with a
  `streamZipEntry` method); `MultiVolumeProgressiveInputStream` here is my
  own implementation of the same idea, not a port of their code
- **`AnimeResolver` / Kitsu matching** - confirmed present (`KITSU_THRESHOLD`
  constant), not implemented here
- **OTA self-updater** (`OtaUpdater`, `REQUEST_INSTALL_PACKAGES` permission)
  - the real app downloads and installs its own APK updates directly; not
    built here
- **Android TV / Leanback launcher support** - confirmed in the manifest
  (`LEANBACK_LAUNCHER` category, `android.software.leanback`); not added
  here, this scaffold is phone-only

## Simplifications worth knowing about
- `ZipVolumeResolver` assumes a standard (non-ZIP64) local file header and
  a single entry per archive - true for "one split movie file zipped for
  size", not general-purpose zip handling
- If the zip's compressed-size field wasn't known at write time (data
  descriptor used), entry length falls back to "everything after the
  header" - may include a few trailing zip bytes after the real video
  data, which most players tolerate
- `Fuzzy.similarity` is a standard Levenshtein-ratio implementation, not
  necessarily what the real app's `Fuzzy.kt` does internally
