# TikTok HD Downloader (Android)

Share a TikTok link -> the app grabs the HD MP4 and saves it to `Downloads/TikTok/`
as `username_videoID.mp4`. No pasting, no second button.

## Getting the APK (no Android Studio needed)

1. Create a new **empty** GitHub repository (private is fine).
2. Upload every file in this folder, keeping the folder structure. The web UI
   works: **Add file -> Upload files**, then drag the whole unzipped folder in.
   Make sure `.github/workflows/build.yml` comes along (it is a hidden folder;
   if the browser skips it, create the file manually via **Add file -> Create new file**
   and name it `.github/workflows/build.yml`).
3. Go to the **Actions** tab. The build starts on push; otherwise pick
   **Build APK -> Run workflow**.
4. When it finishes (~3-5 min), open the run and download the artifact
   **TikTokHDDownloader-debug-apk**. Unzip it to get `app-debug.apk`.
5. Copy it to your phone, open it, and allow "install unknown apps" for your
   file manager or browser when prompted.

This is a debug-signed APK, which installs normally on any phone. It just can't
go on the Play Store as-is.

## Building locally instead

There is deliberately no `gradle-wrapper.jar` in this repo (it is a binary that
could not be fetched in the environment where the project was generated), so
`./gradlew` will not work until you create it. With Gradle 8.7+ installed:

    gradle wrapper          # optional, generates the wrapper
    gradle assembleDebug

APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## How it works

`Downloader.kt` is a direct port of the Python script:

- `POST https://tikdownloader.io/api/ajaxSearch` with `q` and `lang=en`, plus the
  same four headers (User-Agent, Referer, Origin, X-Requested-With).
- HTML-unescape the JSON `data` field.
- Find the `<a>` whose text is exactly `Download MP4 HD`, take its `href`
  (entity-decoded, so `&amp;` in the query string survives).
- `GET` that URL and stream it to disk.

Additions that mobile requires:

- **Short-link resolution.** TikTok's share sheet usually hands over
  `vt.tiktok.com/...`, which contains no username or video id. The app follows
  the redirect to the canonical URL first, then extracts both.
- **MediaStore.** On Android 10+ files are written through `MediaStore.Downloads`
  with `RELATIVE_PATH = Download/TikTok/` and `IS_PENDING`, so no storage
  permission is requested at all. `WRITE_EXTERNAL_STORAGE` is declared only for
  API <= 28.
- **Foreground service.** The download runs in `DownloadService` with a progress
  notification, so leaving the app does not kill it.

## Files

    app/src/main/java/com/tiktokhd/downloader/
      Downloader.kt       HTTP + parsing (the ported logic)
      Storage.kt          MediaStore / legacy file saving, duplicate check
      DownloadService.kt  foreground service + notifications
      DownloadState.kt    status shared with the UI
      MainActivity.kt     share-intent handling + status screen
    app/src/main/AndroidManifest.xml
    .github/workflows/build.yml

## Behaviour notes

- Duplicate file: the app stops and shows "File already exists"; nothing is
  overwritten. Delete the old file to re-download.
- Errors shown instead of crashing: invalid/missing TikTok URL, no username or
  video id, TikDownloader error or non-200, missing HD link, timeout, no
  connection, truncated download, unwritable destination. A partly written file
  is deleted on failure.
- If tikdownloader.io changes its markup or goes down, the app reports
  "Download MP4 HD link not found" - that is the one part no app-side fix covers.
