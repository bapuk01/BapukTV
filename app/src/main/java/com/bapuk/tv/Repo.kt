package com.bapuk.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Playlist(
    val id: String,
    val name: String,
    /** URL или исходное имя файла */
    val source: String,
    val isUrl: Boolean,
    val updatedAt: Long,
    val count: Int,
)

enum class StartMode { LIST, LAST, FAVORITE, CHANNEL }

/** Хранилище плейлистов, избранного и последнего канала. */
object Repo {
    private const val UA = "BapukTV/1.0 (Linux; Android)"
    private const val AUTO_REFRESH_MS = 12 * 60 * 60 * 1000L

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private var playlists = mutableListOf<Playlist>()

    // Кэш разобранного активного плейлиста
    private var cachedId: String? = null
    private var cachedChannels: List<Channel> = emptyList()

    fun init(context: Context) {
        dir = File(context.filesDir, "playlists").apply { mkdirs() }
        prefs = context.getSharedPreferences("bapuk", Context.MODE_PRIVATE)
        playlists = readIndex()
    }

    // ---------- Плейлисты ----------

    fun playlists(): List<Playlist> = playlists.toList()

    var activeId: String?
        get() = prefs.getString("active", null)?.takeIf { id -> playlists.any { it.id == id } }
            ?: playlists.firstOrNull()?.id
        set(value) = prefs.edit().putString("active", value).apply()

    fun active(): Playlist? = activeId?.let { id -> playlists.firstOrNull { it.id == id } }

    fun needsRefresh(p: Playlist) = p.isUrl && System.currentTimeMillis() - p.updatedAt > AUTO_REFRESH_MS

    /** Каналы активного плейлиста (из памяти, иначе из локальной копии). */
    suspend fun activeChannels(): List<Channel> {
        val id = activeId ?: return emptyList()
        if (id == cachedId) return cachedChannels
        val list = withContext(Dispatchers.IO) {
            val t = System.nanoTime()
            val f = file(id)
            val parsed = if (f.exists()) f.bufferedReader().use { M3uParser.parse(it) } else emptyList()
            Log.i("BapukTV", "Разобрано ${parsed.size} каналов за ${(System.nanoTime() - t) / 1_000_000} мс")
            parsed
        }
        cachedId = id
        cachedChannels = list
        return list
    }

    fun cachedActiveChannels(): List<Channel>? = if (cachedId == activeId) cachedChannels else null

    suspend fun addFromUrl(url: String, name: String?): Playlist = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val count = download(url, file(id))
        val p = Playlist(id, name?.takeIf { it.isNotBlank() } ?: nameFromUrl(url), url, true,
            System.currentTimeMillis(), count)
        withContext(Dispatchers.Main) { add(p) }
        p
    }

    suspend fun addFromUri(context: Context, uri: Uri, name: String?): Playlist = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val display = displayName(context, uri)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        val count = save(input, file(id))
        val p = Playlist(id, name?.takeIf { it.isNotBlank() } ?: display.substringBeforeLast('.'),
            display, false, System.currentTimeMillis(), count)
        withContext(Dispatchers.Main) { add(p) }
        p
    }

    suspend fun addFromFile(f: File, name: String?): Playlist = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val count = save(f.inputStream(), file(id))
        val p = Playlist(id, name?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
            f.name, false, System.currentTimeMillis(), count)
        withContext(Dispatchers.Main) { add(p) }
        p
    }

    /** Перезагружает плейлист по ссылке. */
    suspend fun refresh(id: String): Playlist = withContext(Dispatchers.IO) {
        val p = playlists.first { it.id == id }
        check(p.isUrl) { "Плейлист загружен из файла" }
        val count = download(p.source, file(id))
        val updated = p.copy(updatedAt = System.currentTimeMillis(), count = count)
        withContext(Dispatchers.Main) {
            playlists = playlists.mapTo(mutableListOf()) { if (it.id == id) updated else it }
            writeIndex()
            if (cachedId == id) cachedId = null
        }
        updated
    }

    fun rename(id: String, name: String) {
        playlists = playlists.mapTo(mutableListOf()) { if (it.id == id) it.copy(name = name) else it }
        writeIndex()
    }

    fun delete(id: String) {
        playlists.removeAll { it.id == id }
        file(id).delete()
        prefs.edit().remove("fav_$id").remove("last_$id").remove("group_$id").remove("start_ch_$id").apply()
        if (cachedId == id) cachedId = null
        writeIndex()
    }

    private fun add(p: Playlist) {
        playlists.add(p)
        writeIndex()
        activeId = p.id
    }

    // ---------- Избранное и последний канал ----------

    fun favorites(): Set<String> =
        activeId?.let { prefs.getStringSet("fav_$it", null) } ?: emptySet()

    /** @return true, если канал теперь в избранном */
    fun toggleFavorite(url: String): Boolean {
        val id = activeId ?: return false
        val set = HashSet(favorites())
        val added = set.add(url) || run { set.remove(url); false }
        prefs.edit().putStringSet("fav_$id", set).apply()
        return added
    }

    var lastChannelUrl: String?
        get() = activeId?.let { prefs.getString("last_$it", null) }
        set(value) { activeId?.let { prefs.edit().putString("last_$it", value).apply() } }

    // ---------- Настройки ----------

    /** Что делать при запуске приложения */
    var startMode: StartMode
        get() = runCatching { StartMode.valueOf(prefs.getString("start_mode", null)!!) }
            .getOrDefault(StartMode.LIST)
        set(value) = prefs.edit().putString("start_mode", value.name).apply()

    /** Яркость в плеере, заданная жестом (0..1); -1 — системная */
    var playerBrightness: Float
        get() = prefs.getFloat("player_brightness", -1f)
        set(value) = prefs.edit().putFloat("player_brightness", value).apply()

    /** Открывать приложение после загрузки устройства */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("boot_start", false)
        set(value) = prefs.edit().putBoolean("boot_start", value).apply()

    /** Канал для StartMode.CHANNEL — свой для каждого плейлиста */
    var startChannelUrl: String?
        get() = activeId?.let { prefs.getString("start_ch_$it", null) }
        set(value) { activeId?.let { prefs.edit().putString("start_ch_$it", value).apply() } }

    var lastGroup: String?
        get() = activeId?.let { prefs.getString("group_$it", null) }
        set(value) { activeId?.let { prefs.edit().putString("group_$it", value).apply() } }

    // ---------- Внутреннее ----------

    private fun file(id: String) = File(dir, "$id.m3u")

    /**
     * Скачивает плейлист. Для готовых плейлистов при недоступности основного адреса
     * пробует зеркало и запоминает, какой адрес сработал, чтобы в следующий раз начать с него.
     */
    private fun download(url: String, target: File): Int {
        val mirror = Presets.mirrorFor(url) ?: return downloadFrom(url, target, 15_000)
        val key = "mirror_ok_$url"
        val order = if (prefs.getBoolean(key, false)) listOf(mirror, url) else listOf(url, mirror)
        var error: IOException? = null
        for (u in order) {
            try {
                val count = downloadFrom(u, target, 7_000)
                prefs.edit().putBoolean(key, u == mirror).apply()
                return count
            } catch (e: IOException) {
                Log.w("BapukTV", "Не удалось скачать $u: ${e.message}")
                error = e
            }
        }
        throw error!!
    }

    private fun downloadFrom(url: String, target: File, connectTimeout: Int): Int {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeout
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", UA)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Пустое перенаправление")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code != 200) throw IOException("Сервер ответил: HTTP $code")
                return save(conn.inputStream, target)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Слишком много перенаправлений")
    }

    /** Сохраняет поток во временный файл, проверяет что это плейлист, затем заменяет target. */
    private fun save(input: InputStream, target: File): Int {
        val tmp = File(target.path + ".tmp")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        val count = tmp.bufferedReader().use { M3uParser.parse(it) }.size
        if (count == 0) {
            tmp.delete()
            throw IOException("В плейлисте не найдено ни одного канала")
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить плейлист")
        }
        return count
    }

    private fun nameFromUrl(url: String): String = runCatching {
        val u = URL(url)
        u.path.substringAfterLast('/').substringBeforeLast('.').takeIf { it.length in 2..40 } ?: u.host
    }.getOrDefault("Плейлист")

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Плейлист"
    }

    private fun readIndex(): MutableList<Playlist> = runCatching {
        val arr = JSONArray(File(dir, "index.json").readText())
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Playlist(
                o.getString("id"), o.getString("name"), o.getString("source"),
                o.getBoolean("isUrl"), o.getLong("updatedAt"), o.optInt("count"),
            )
        }
    }.getOrElse { mutableListOf() }

    private fun writeIndex() {
        val arr = JSONArray()
        playlists.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("source", it.source)
                put("isUrl", it.isUrl); put("updatedAt", it.updatedAt); put("count", it.count)
            })
        }
        val f = File(dir, "index.json")
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

/** Список, который сейчас «переключается» в плеере. */
object Session {
    var channels: List<Channel> = emptyList()
    var title: String = ""
    /** Категория, из которой взят список (Groups.FAV / Groups.ALL / название группы); null — результаты поиска */
    var groupKey: String? = null
}
