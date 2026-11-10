package com.meterproof.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** هل يوجد اتصال بالإنترنت الآن (الجهاز متصل بشبكة تصل إلى الإنترنت). */
object Net {
    fun online(context: Context): Boolean = try {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val c = if (n != null) cm.getNetworkCapabilities(n) else null
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        false
    }
}

/** جلب بلاطة واحدة عبر HTTPS. لا يُسجَّل الرابط أبدًا (قد يحوي مفتاحًا). */
class HttpTileFetcher : TileFetcher {
    override fun fetch(url: String): FetchResult {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code != 200) FetchResult(code)
            else FetchResult(200, c.inputStream.use { readLimited(it, MAX_TILE_BYTES) })
        } catch (e: IOException) {
            FetchResult(0, null, e.message ?: e.javaClass.simpleName)
        } finally {
            c?.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return ByteArray(0) // ضخم جدًا: ليس بلاطة
        }
        return out.toByteArray()
    }

    companion object {
        const val USER_AGENT = "MeterProof/0.1 (Android)"
        private const val MAX_TILE_BYTES = 2_000_000
    }
}
