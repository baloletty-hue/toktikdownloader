package com.tiktokhd.downloader

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Port of the original Python script:
 *   POST https://tikdownloader.io/api/ajaxSearch  (q, lang=en)
 *   -> unescape JSON "data" HTML
 *   -> find the <a> whose text is exactly "Download MP4 HD"
 *   -> GET its href and stream the MP4.
 */
object Downloader {

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Safari/537.36"

    private const val API = "https://tikdownloader.io/api/ajaxSearch"

    class DownloadError(message: String) : Exception(message)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // ---------------------------------------------------------------- URL

    private val ANY_URL = Regex("""https?://[^\s"'<>\\]+""")

    private val TIKTOK_HOST = Regex(
        """https?://([a-z0-9-]+\.)?(tiktok\.com|tiktokv\.com)/""",
        RegexOption.IGNORE_CASE
    )

    /** Pulls the first TikTok link out of whatever TikTok put on the clipboard/share. */
    fun extractTikTokUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (m in ANY_URL.findAll(text)) {
            val candidate = m.value.trimEnd('.', ',', ')', ']', '"', '\'')
            if (TIKTOK_HOST.containsMatchIn(candidate)) return candidate
        }
        return null
    }

    /**
     * TikTok's share sheet usually hands over a short link (vt./vm.tiktok.com).
     * Follow it so username and video id can be read from the canonical URL.
     */
    fun resolveUrl(url: String): String {
        if (Regex("""/video/\d+""").containsMatchIn(url)) return url
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (Regex("""/video/\d+""").containsMatchIn(finalUrl)) finalUrl else url
            }
        } catch (e: Exception) {
            url
        }
    }

    data class VideoInfo(val username: String, val videoId: String) {
        val fileName: String get() = "${username}_${videoId}.mp4"
    }

    fun videoInfo(url: String): VideoInfo {
        val username = Regex("""tiktok\.com/@([^/?&#]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: throw DownloadError("Cannot detect username from this URL.")

        val videoId = Regex("""/video/(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: throw DownloadError("Cannot detect TikTok video ID from this URL.")

        return VideoInfo(username, videoId)
    }

    // ------------------------------------------------------------- HD link

    fun hdUrl(tiktokUrl: String): String {
        val body = FormBody.Builder()
            .add("q", tiktokUrl)
            .add("lang", "en")
            .build()

        val req = Request.Builder()
            .url(API)
            .post(body)
            .header("User-Agent", UA)
            .header("Referer", "https://tikdownloader.io/en")
            .header("Origin", "https://tikdownloader.io")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val raw = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw DownloadError("TikDownloader HTTP ${resp.code}.")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: SocketTimeoutException) {
            throw DownloadError("Network timeout while contacting TikDownloader.")
        } catch (e: UnknownHostException) {
            throw DownloadError("No internet connection.")
        } catch (e: DownloadError) {
            throw e
        } catch (e: Exception) {
            throw DownloadError("Connection failed: ${e.message ?: e.javaClass.simpleName}")
        }

        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw DownloadError("TikDownloader sent an unreadable response.")
        }

        if (json.optString("status") != "ok") {
            throw DownloadError("TikDownloader returned an error.")
        }

        val html = decodeHtml(json.optString("data"))
        return findHdHref(html) ?: throw DownloadError("\"Download MP4 HD\" link not found.")
    }

    private val ANCHOR = Regex(
        """<a\s+([^>]+)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TAGS = Regex("""<.*?>""", RegexOption.DOT_MATCHES_ALL)
    private val HREF = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun findHdHref(html: String): String? {
        val anchors = ANCHOR.findAll(html).map { m ->
            val attrs = m.groupValues[1]
            val text = TAGS.replace(m.groupValues[2], "").split(Regex("\\s+"))
                .filter { it.isNotEmpty() }.joinToString(" ")
            attrs to text
        }.toList()

        // Exact match, as in the original script.
        anchors.firstOrNull { it.second.equals("Download MP4 HD", ignoreCase = true) }
            ?.let { pair -> HREF.find(pair.first)?.groupValues?.get(1)?.let { return decodeHtml(it) } }

        // Defensive fallback if the site relabels the button slightly.
        anchors.firstOrNull { it.second.contains("MP4 HD", ignoreCase = true) }
            ?.let { pair -> HREF.find(pair.first)?.groupValues?.get(1)?.let { return decodeHtml(it) } }

        return null
    }

    private fun decodeHtml(s: String): String {
        if (s.isEmpty()) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { out.append(c); i++; continue }
            val end = s.indexOf(';', i + 1)
            if (end == -1 || end - i > 10) { out.append(c); i++; continue }
            val entity = s.substring(i + 1, end)
            val replacement: String? = when {
                entity.startsWith("#x", true) ->
                    entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> when (entity.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> null
                }
            }
            if (replacement == null) { out.append(c); i++ } else { out.append(replacement); i = end + 1 }
        }
        return out.toString()
    }

    // ------------------------------------------------------------ Transfer

    /** Streams the MP4 into [out]. Returns bytes written. */
    fun fetchTo(
        url: String,
        out: OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://tikdownloader.io/en")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw DownloadError("Download failed: HTTP ${resp.code}.")
                val bodyStream = resp.body?.byteStream()
                    ?: throw DownloadError("Empty response from the video server.")

                val total = resp.body?.contentLength() ?: -1L
                onProgress(0L, total)

                val buf = ByteArray(256 * 1024)
                var downloaded = 0L
                var lastTick = 0L

                bodyStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 200) {
                            lastTick = now
                            onProgress(downloaded, total)
                        }
                    }
                }
                out.flush()
                onProgress(downloaded, total)

                if (downloaded == 0L) throw DownloadError("Download interrupted: no data received.")
                if (total > 0 && downloaded < total) {
                    throw DownloadError("Download interrupted before the file was complete.")
                }
                return downloaded
            }
        } catch (e: SocketTimeoutException) {
            throw DownloadError("Network timeout while downloading.")
        } catch (e: UnknownHostException) {
            throw DownloadError("No internet connection.")
        } catch (e: DownloadError) {
            throw e
        } catch (e: Exception) {
            throw DownloadError("Download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
