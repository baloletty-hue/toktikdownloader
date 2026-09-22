

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
