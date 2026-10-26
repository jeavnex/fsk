package net.tenyls.kinotv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновления:
 *  • «тихие» — remote/config.json из репозитория (списки блокировки, правила скрытия) — без переустановки;
 *  • новая версия APK — из GitHub Releases, ставится поверх с сохранением входа и данных.
 */
object Updater {

    const val ACTION_INSTALL = "net.tenyls.kinotv.INSTALL_STATUS"

    val configUrl: String get() = "https://raw.githubusercontent.com/${BuildConfig.REPO}/main/remote/config.json"
    private val releaseApi: String get() = "https://api.github.com/repos/${BuildConfig.REPO}/releases/latest"

    class Release(val code: Int, val name: String, val apkUrl: String, val notes: String)

    private fun open(url: String, timeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "KinoTV/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    fun fetchText(url: String, timeout: Int = 10000): String? = try {
        val c = open(url, timeout)
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    fun latestRelease(): Release? {
        val txt = fetchText(releaseApi) ?: return null
        return try {
            val o = JSONObject(txt)
            val tag = o.optString("tag_name")
            val code = tag.filter { it.isDigit() }.toIntOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            var apk = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apk = a.optString("browser_download_url"); break
                }
            }
            if (apk.isEmpty()) null else Release(code, o.optString("name", tag), apk, o.optString("body"))
        } catch (_: Exception) {
            null
        }
    }

    /** Скачать файл; onProgress(0..100). Ручной переход по редиректам (github → objects.githubusercontent). */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        return try {
            var current = url
            var conn: HttpURLConnection? = null
            var hops = 0
            while (conn == null) {
                val t = open(current, 30000)
                t.instanceFollowRedirects = false
                val code = t.responseCode
                if (code in 300..399) {
                    val loc = t.getHeaderField("Location") ?: return false
                    t.disconnect()
                    current = URL(URL(current), loc).toString()
                    if (++hops > 6) return false
                    continue
                }
                if (code !in 200..299) { t.disconnect(); return false }
                conn = t
            }
            val c: HttpURLConnection = conn!!
            val total = c.contentLength.toLong()
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            c.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("kino.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, MainActivity::class.java).setAction(ACTION_INSTALL)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getActivity(ctx, 7, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
