package com.voiceforge.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App storage — same layout and JSON schema as the Linux app:
 *   filesDir/voiceforge/voices/<id>.mp3 + index.json
 *   filesDir/voiceforge/saved/<id>.mp3  + index.json
 *   filesDir/voiceforge/staging/        (transient generation output)
 *   filesDir/voiceforge/config.json     (lang, normalize)
 *   filesDir/voiceforge/model/          (downloaded ONNX model)
 */
object Storage {
    lateinit var root: File
        private set
    val voicesDir: File get() = File(root, "voices")
    val savedDir: File get() = File(root, "saved")
    val stagingDir: File get() = File(root, "staging")
    val modelDir: File get() = File(root, "model")

    fun init(context: Context) {
        root = File(context.filesDir, "voiceforge")
        listOf(root, voicesDir, savedDir, stagingDir, modelDir).forEach { it.mkdirs() }
    }

    private val voicesIndex: File get() = File(voicesDir, "index.json")
    private val savedIndex: File get() = File(savedDir, "index.json")
    private val configFile: File get() = File(root, "config.json")

    // ---------------------------------------------------------------- voices
    @Synchronized
    fun listVoices(): List<VoiceSample> {
        val items = readIndex(voicesIndex).objects().mapNotNull { o ->
            val f = File(voicesDir, o.optString("file"))
            if (!f.exists()) return@mapNotNull null
            VoiceSample(
                id = o.getString("id"),
                name = o.getString("name"),
                file = o.getString("file"),
                created = o.optLong("created"),
                duration = o.optDouble("duration"),
                source = o.optString("source", "upload"),
            )
        }
        return items.sortedBy { it.created }
    }

    @Synchronized
    fun addVoice(sample: VoiceSample) {
        val arr = readIndex(voicesIndex)
        arr.put(sample.toJson())
        writeIndex(voicesIndex, arr)
    }

    @Synchronized
    fun renameVoice(id: String, newName: String) {
        val arr = readIndex(voicesIndex)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id && newName.isNotBlank()) o.put("name", newName)
        }
        writeIndex(voicesIndex, arr)
    }

    @Synchronized
    fun deleteVoice(id: String) {
        val arr = readIndex(voicesIndex)
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) File(voicesDir, o.getString("file")).delete()
            else keep.put(o)
        }
        writeIndex(voicesIndex, keep)
    }

    fun voiceFile(v: VoiceSample): File = File(voicesDir, v.file)

    // ----------------------------------------------------------------- saved
    @Synchronized
    fun listSaved(sort: String = "date_desc", query: String = ""): List<SavedItem> {
        var items = readIndex(savedIndex).objects().mapNotNull { o ->
            val f = File(savedDir, o.optString("file"))
            if (!f.exists()) return@mapNotNull null
            val st = o.optJSONObject("settings")
            SavedItem(
                id = o.getString("id"),
                name = o.getString("name"),
                file = o.getString("file"),
                created = o.optLong("created"),
                text = o.optString("text"),
                mood = o.optString("mood"),
                voiceName = o.optString("voiceName"),
                duration = o.optDouble("duration"),
                settings = st?.let { parseSettings(it) },
            )
        }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            items = items.filter {
                it.name.lowercase().contains(q) ||
                    it.text.lowercase().contains(q) ||
                    it.voiceName.lowercase().contains(q)
            }
        }
        return when (sort) {
            "date_asc" -> items.sortedBy { it.created }
            "name" -> items.sortedBy { it.name.lowercase() }
            else -> items.sortedByDescending { it.created }
        }
    }

    @Synchronized
    fun addSaved(
        src: File, name: String, text: String = "", mood: String = "",
        voiceName: String = "", settings: GenSettings? = null,
    ): SavedItem {
        val arr = readIndex(savedIndex)
        val existing = mutableSetOf<String>()
        for (i in 0 until arr.length()) existing.add(arr.getJSONObject(i).getString("name"))
        var finalName = name.ifBlank { "Generated speech" }
        var suffix = 1
        while (finalName in existing) {
            suffix++
            finalName = "$name ($suffix)"
        }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val fileName = "$id.mp3"
        src.copyTo(File(savedDir, fileName), overwrite = true)
        val item = SavedItem(
            id = id, name = finalName, file = fileName,
            created = System.currentTimeMillis(), text = text, mood = mood,
            voiceName = voiceName,
            duration = 0.0, // filled by caller via MediaMetadataRetriever if wanted
            settings = settings,
        )
        arr.put(item.toJson())
        writeIndex(savedIndex, arr)
        return item
    }

    @Synchronized
    fun deleteSaved(id: String) {
        val arr = readIndex(savedIndex)
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) File(savedDir, o.getString("file")).delete()
            else keep.put(o)
        }
        writeIndex(savedIndex, keep)
    }

    fun savedFile(item: SavedItem): File = File(savedDir, item.file)

    // ---------------------------------------------------------------- config

    /**
     * In-memory mirror of config.json's "theme" key. Other writers only save
     * lang/normalize (EngineViewModel builds an AppConfig without a theme), so
     * re-writing the mirror keeps the user's theme choice from being wiped.
     */
    @Volatile
    private var themeMode: String = "system"

    fun loadConfig(): AppConfig {
        if (!configFile.exists()) return AppConfig()
        return runCatching {
            val o = JSONObject(configFile.readText())
            themeMode = o.optString("theme", "system")
            AppConfig(
                lang = o.optString("lang", "en"),
                normalize = o.optBoolean("normalize", false),
                theme = themeMode,
            )
        }.getOrDefault(AppConfig())
    }

    fun saveConfig(cfg: AppConfig) {
        if (cfg.theme != "system") themeMode = cfg.theme
        val o = JSONObject()
            .put("lang", cfg.lang)
            .put("normalize", cfg.normalize)
            .put("theme", themeMode)
        configFile.writeText(o.toString())
    }

    /** Persist the theme mode ("system" | "light" | "dark"), keeping lang/normalize. */
    fun saveTheme(mode: String) {
        val cfg = loadConfig()
        themeMode = mode
        saveConfig(cfg.copy(theme = mode))
    }

    // ---------------------------------------------------------------- helpers
    private fun readIndex(file: File): JSONArray =
        if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        else JSONArray()

    /** Kotlin fix: Android's JSONArray is not Iterable (compile-only change). */
    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { i -> optJSONObject(i) }

    private fun writeIndex(file: File, arr: JSONArray) {
        file.writeText(arr.toString())
    }

    private fun VoiceSample.toJson() = JSONObject()
        .put("id", id).put("name", name).put("file", file)
        .put("created", created).put("duration", duration).put("source", source)

    private fun SavedItem.toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id).put("name", name).put("file", file)
            .put("created", created).put("text", text).put("mood", mood)
            .put("voiceName", voiceName).put("duration", duration)
        settings?.let { s ->
            o.put(
                "settings",
                JSONObject()
                    .put("mood", s.mood).put("pitch", s.pitch.toDouble())
                    .put("speed", s.speed.toDouble()).put("gain", s.gain.toDouble())
                    .put("normalize", s.normalize).put("language", s.language)
                    .put("voiceId", s.voiceId).put("voiceName", s.voiceName),
            )
        }
        return o
    }

    private fun parseSettings(o: JSONObject) = GenSettings(
        mood = o.optString("mood", "Neutral"),
        pitch = o.optDouble("pitch", 0.0).toFloat(),
        speed = o.optDouble("speed", 1.0).toFloat(),
        gain = o.optDouble("gain", 0.0).toFloat(),
        normalize = o.optBoolean("normalize", false),
        language = o.optString("language", "pl"),
        voiceId = o.optString("voiceId", ""),
        voiceName = o.optString("voiceName", ""),
    )
}
