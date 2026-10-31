package com.bapuk.tv

import java.io.BufferedReader

data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val userAgent: String?,
    val referrer: String?,
)

/**
 * Потоковый парсер M3U/M3U8 (формат IPTV-плейлистов).
 * Понимает #EXTINF с атрибутами (tvg-logo, group-title, user-agent и т.д.),
 * #EXTGRP и #EXTVLCOPT:http-user-agent / http-referrer.
 */
object M3uParser {

    const val NO_GROUP = "Без группы"
    private val BOM = Char(0xFEFF)

    fun parse(reader: BufferedReader): List<Channel> {
        val result = ArrayList<Channel>(1024)
        var name: String? = null
        var group: String? = null
        var logo: String? = null
        var ua: String? = null
        var ref: String? = null
        var extGrp: String? = null

        reader.lineSequence().forEach { raw ->
            val line = raw.trim().trimStart(BOM)
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = HashMap<String, String>()
                    name = parseExtInf(line, attrs)
                    group = attrs["group-title"]
                    logo = attrs["tvg-logo"] ?: attrs["logo"]
                    ua = attrs["user-agent"] ?: attrs["http-user-agent"]
                    ref = attrs["referrer"] ?: attrs["http-referrer"]
                    if (name.isNullOrBlank()) name = attrs["tvg-name"]
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    extGrp = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11)
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val v = opt.substring(eq + 1).trim()
                        when (opt.substring(0, eq).trim().lowercase()) {
                            "http-user-agent" -> ua = v
                            "http-referrer", "http-referer" -> ref = v
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    // Строка с адресом потока
                    val g = group?.takeIf { it.isNotBlank() } ?: extGrp?.takeIf { it.isNotBlank() } ?: NO_GROUP
                    val n = name?.takeIf { it.isNotBlank() } ?: line.substringAfterLast('/').ifBlank { line }
                    result += Channel(
                        number = result.size + 1,
                        name = n,
                        url = line,
                        group = g,
                        logo = logo?.takeIf { it.startsWith("http", ignoreCase = true) },
                        userAgent = ua?.takeIf { it.isNotBlank() },
                        referrer = ref?.takeIf { it.isNotBlank() },
                    )
                    name = null; group = null; logo = null; ua = null; ref = null
                    // #EXTGRP действует до следующего #EXTGRP
                }
            }
        }
        return result
    }

    /** Разбирает `#EXTINF:-1 key="value" key2=value2,Название`. Возвращает название. */
    private fun parseExtInf(line: String, attrs: MutableMap<String, String>): String {
        var i = line.indexOf(':') + 1
        if (i <= 0) return ""
        val len = line.length
        // пропускаем длительность
        while (i < len && line[i] != ' ' && line[i] != ',' && line[i] != '\t') i++
        while (i < len) {
            val c = line[i]
            when {
                c == ',' -> return line.substring(i + 1).trim()
                c == ' ' || c == '\t' -> i++
                else -> {
                    val keyStart = i
                    while (i < len && line[i] != '=' && line[i] != ' ' && line[i] != ',') i++
                    if (i >= len || line[i] != '=') continue
                    val key = line.substring(keyStart, i).lowercase()
                    i++ // '='
                    val value: String
                    if (i < len && line[i] == '"') {
                        val end = line.indexOf('"', i + 1).let { if (it < 0) len else it }
                        value = line.substring(i + 1, end)
                        i = end + 1
                    } else {
                        val start = i
                        while (i < len && line[i] != ' ' && line[i] != ',') i++
                        value = line.substring(start, i)
                    }
                    attrs[key] = value.trim()
                }
            }
        }
        return ""
    }
}
