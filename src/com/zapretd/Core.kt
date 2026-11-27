package com.zapretd

import android.content.Context
import org.json.JSONArray

object AppLog {
    private val lines = ArrayList<String>()
    private var lastRaw = ""
    private var repeats = 0

    @Synchronized
    fun add(message: String) {
        for (line in message.split("\n")) {
            val raw = line.trim()
            if (raw.isEmpty()) continue
            if (raw == lastRaw && lines.isNotEmpty()) {
                repeats++
                lines[lines.size - 1] = "$raw (×$repeats)"
            } else {
                lastRaw = raw
                repeats = 1
                lines.add(raw)
            }
        }
        while (lines.size > 150) lines.removeAt(0)
    }

    @Synchronized
    fun text(): String = lines.takeLast(40).joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        lastRaw = ""
        repeats = 0
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("zapret", Context.MODE_PRIVATE)

    var useRoot: Boolean
        get() = prefs.getBoolean("root", false)
        set(value) = prefs.edit().putBoolean("root", value).apply()

    var preset: String
        get() = prefs.getString("preset", "general") ?: "general"
        set(value) = prefs.edit().putString("preset", value).apply()

    var strategy: String
        get() = prefs.getString("strategy", "Split + TLS-record") ?: "Split + TLS-record"
        set(value) = prefs.edit().putString("strategy", value).apply()

    var comboYoutube: String
        get() = prefs.getString("comboYoutube", "Многошаговый") ?: "Многошаговый"
        set(value) = prefs.edit().putString("comboYoutube", value).apply()

    var comboDiscord: String
        get() = prefs.getString("comboDiscord", "Fake + SNI google") ?: "Fake + SNI google"
        set(value) = prefs.edit().putString("comboDiscord", value).apply()

    val selected: String
        get() = if (useRoot) preset else strategy

    fun select(name: String) {
        if (useRoot) preset = name else strategy = name
    }

    var discord: Boolean
        get() = prefs.getBoolean("discord", true)
        set(value) = prefs.edit().putBoolean("discord", value).apply()

    var youtube: Boolean
        get() = prefs.getBoolean("youtube", true)
        set(value) = prefs.edit().putBoolean("youtube", value).apply()

    var other: Boolean
        get() = prefs.getBoolean("other", false)
        set(value) = prefs.edit().putBoolean("other", value).apply()

    fun anyServiceEnabled() = discord || youtube || other

    fun allows(kind: String): Boolean = when (kind) {
        "discord" -> discord
        "youtube" -> youtube
        else -> discord || other
    }
}

class Segment(val kind: String, val args: List<String>)

class Preset(val name: String, val segments: List<Segment>, val byedpi: String)

object Catalog {
    const val PLACEHOLDER_HOST = "zapret.invalid"
    const val PLACEHOLDER_IP = "203.0.113.113/32"

    fun strategies(context: Context): List<Preset> {
        val array = JSONArray(readAsset(context, "strategies.json"))
        val result = ArrayList<Preset>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            result.add(Preset(item.getString("name"), emptyList(), item.getString("args")))
        }
        return result
    }

    const val COMBO = "Комбо (по сервисам)"

    fun options(context: Context, root: Boolean): List<Preset> =
        if (root) presets(context) else listOf(Preset(COMBO, emptyList(), "")) + strategies(context)

    fun scannable(context: Context, root: Boolean): List<Preset> =
        options(context, root).filter { it.name != COMBO }

    fun argsOf(context: Context, name: String): String {
        val all = strategies(context)
        return (all.firstOrNull { it.name == name } ?: all[0]).byedpi
    }

    fun discordHosts(context: Context, settings: Settings): List<String> {
        val items = ArrayList<String>()
        if (settings.discord) items.addAll(discordDomains(context))
        if (settings.other) items.addAll(otherDomains(context))
        return items.map { it.removePrefix("^") }.distinct()
    }

    fun youtubeHosts(context: Context, settings: Settings): List<String> =
        if (settings.youtube) youtubeDomains(context).map { it.removePrefix("^") }.distinct() else emptyList()

    fun find(context: Context, name: String, root: Boolean): Preset? {
        val all = options(context, root)
        return all.firstOrNull { it.name == name } ?: all.firstOrNull()
    }

    fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    fun presets(context: Context): List<Preset> {
        val array = JSONArray(readAsset(context, "presets.json"))
        val result = ArrayList<Preset>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val segments = ArrayList<Segment>()
            val rawSegments = item.getJSONArray("segments")
            for (j in 0 until rawSegments.length()) {
                val raw = rawSegments.getJSONObject(j)
                val args = ArrayList<String>()
                val rawArgs = raw.getJSONArray("args")
                for (k in 0 until rawArgs.length()) args.add(rawArgs.getString(k))
                segments.add(Segment(raw.getString("kind"), args))
            }
            result.add(Preset(item.getString("name"), segments, item.getString("byedpi")))
        }
        return result
    }

    private fun lines(context: Context, path: String): List<String> =
        readAsset(context, path).split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun isDiscord(host: String) = host.contains("discord") || host == "dis.gd"

    fun discordDomains(context: Context): List<String> =
        lines(context, "lists/list-general.txt").filter { isDiscord(it) }

    fun otherDomains(context: Context): List<String> =
        lines(context, "lists/list-general.txt").filter { !isDiscord(it) }

    fun youtubeDomains(context: Context): List<String> =
        lines(context, "lists/list-google.txt").filter { !it.contains("/") }

    fun generalList(context: Context, settings: Settings): List<String> {
        val items = ArrayList<String>()
        if (settings.discord) items.addAll(discordDomains(context))
        if (settings.other) items.addAll(otherDomains(context))
        return if (items.isEmpty()) listOf(PLACEHOLDER_HOST) else items
    }

    fun youtubeList(context: Context, settings: Settings): List<String> =
        if (settings.youtube) youtubeDomains(context) else listOf(PLACEHOLDER_HOST)

    fun proxyHosts(context: Context, settings: Settings): List<String> {
        val items = ArrayList<String>()
        if (settings.discord) items.addAll(discordDomains(context))
        if (settings.youtube) items.addAll(youtubeDomains(context))
        if (settings.other) items.addAll(otherDomains(context))
        return items.map { it.removePrefix("^") }.distinct()
    }
}
