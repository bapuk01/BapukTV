package com.bapuk.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Лёгкий загрузчик логотипов каналов: память (LRU) + диск, уменьшение до нужного размера.
 * Без сторонних библиотек, чтобы приложение оставалось маленьким и быстрым.
 */
object LogoLoader {
    private const val TARGET_PX = 160

    private lateinit var diskDir: File
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(24 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun init(context: Context) {
        diskDir = File(context.cacheDir, "logos").apply { mkdirs() }
    }

    fun load(view: ImageView, url: String?) {
        view.tag = url
        if (url == null || url in failed) {
            view.setImageDrawable(null)
            return
        }
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            if (view.tag != url) return@execute // строку уже переиспользовали
            val bmp = runCatching { fetch(url) }.getOrNull()
            if (bmp == null) {
                failed += url
                return@execute
            }
            memory.put(url, bmp)
            main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val file = File(diskDir, Integer.toHexString(url.hashCode()) + "_" + url.length)
        if (!file.exists() || file.length() == 0L) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode != 200) return null
                val tmp = File(file.path + ".tmp")
                conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(file)
            } finally {
                conn.disconnect()
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        if (opts.outWidth <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= TARGET_PX && opts.outHeight / (sample * 2) >= TARGET_PX / 2) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
