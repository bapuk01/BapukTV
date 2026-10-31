package com.bapuk.tv

import android.content.Context
import android.net.Uri

/** Встроенный плейлист: основной адрес и необязательное зеркало (если основной недоступен). */
data class Preset(val name: String, val url: String, val mirror: String?)

/**
 * Встроенные плейлисты (экран «Плейлисты» → «Готовые плейлисты»).
 * Берутся из ресурса R.array.presets: строки вида «Название|адрес|зеркало».
 * В публичной сборке (open) список пуст и раздел не показывается;
 * сборка full подставляет свой app/src/full/res/values/presets.xml.
 */
object Presets {
    var all: List<Preset> = emptyList()
        private set

    fun init(context: Context) {
        all = context.resources.getStringArray(R.array.presets).mapNotNull { line ->
            val parts = line.split('|').map { it.trim() }
            val name = parts.getOrNull(0).orEmpty()
            val url = parts.getOrNull(1).orEmpty()
            if (name.isEmpty() || url.isEmpty()) null
            else Preset(name, encode(url), parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(::encode))
        }
    }

    fun mirrorFor(url: String): String? = all.firstOrNull { it.url == url }?.mirror

    /** Кодирует не-ASCII символы (например, кириллицу в имени файла), не трогая структуру ссылки */
    private fun encode(url: String) = Uri.encode(url, ":/?#[]@!$&'()*+,;=%-._~")
}
