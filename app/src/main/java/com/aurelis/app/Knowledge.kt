package com.aurelis.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * The offline knowledge library: reference packs (with their source and licence) searched with
 * SQLite full-text search. Kept in its own database, completely separate from personal memory,
 * so packs can be added or removed without touching conversations, notes or memories.
 *
 * Pack format (JSON):
 * {"id":"first-aid","title":"First aid basics","source":"Who wrote it / URL","license":"CC BY 4.0",
 *  "version":"1","entries":[{"title":"Burns","body":"Cool the burn under running water...","source":"optional"}]}
 */
object Knowledge {

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "knowledge.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE packs(id TEXT PRIMARY KEY, title TEXT, source TEXT, license TEXT, version TEXT, entries INTEGER, builtin INTEGER)")
            // FTS4 is available in Android's SQLite on every supported version (FTS5 is not guaranteed).
            db.execSQL("CREATE VIRTUAL TABLE kb USING fts4(pack, title, body, source, tokenize=porter)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    class Pack(val id: String, val title: String, val source: String, val license: String, val version: String, val entries: Int, val builtin: Boolean)
    class Hit(val pack: String, val title: String, val body: String, val source: String)

    @Volatile private var helper: Helper? = null

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) {
            helper ?: Helper(ctx.applicationContext).also { helper = it }
        }
        val db = h.writableDatabase
        ensureBuiltIn(db)
        return db
    }

    @Volatile private var builtInChecked = false

    private fun ensureBuiltIn(db: SQLiteDatabase) {
        if (builtInChecked) return
        synchronized(this) {
            if (builtInChecked) return
            val have = db.rawQuery("SELECT version FROM packs WHERE id='aurelis-guide'", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (have != GUIDE_VERSION) {
                write(
                    db, "aurelis-guide", "AURELIS user guide", "AURELIS (written for this app)",
                    "Part of AURELIS", GUIDE_VERSION, GUIDE, builtin = true
                )
            }
            builtInChecked = true
        }
    }

    private fun write(
        db: SQLiteDatabase, id: String, title: String, source: String, license: String, version: String,
        entries: List<Triple<String, String, String>>, builtin: Boolean
    ) {
        db.beginTransaction()
        try {
            db.delete("kb", "pack = ?", arrayOf(id))
            for ((t, b, s) in entries) {
                db.insert("kb", null, ContentValues().apply {
                    put("pack", id)
                    put("title", t)
                    put("body", b)
                    put("source", s.ifBlank { source })
                })
            }
            db.insertWithOnConflict("packs", null, ContentValues().apply {
                put("id", id)
                put("title", title)
                put("source", source)
                put("license", license)
                put("version", version)
                put("entries", entries.size)
                put("builtin", if (builtin) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun packs(ctx: Context): List<Pack> = try {
        db(ctx).rawQuery("SELECT id,title,source,license,version,entries,builtin FROM packs ORDER BY builtin DESC, title", null).use { c ->
            val out = ArrayList<Pack>()
            while (c.moveToNext()) {
                out.add(Pack(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getInt(5), c.getInt(6) == 1))
            }
            out
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun sizeBytes(ctx: Context): Long = ctx.getDatabasePath("knowledge.db").let { if (it.exists()) it.length() else 0L }

    /** Imports a pack the user picked. Returns a sentence describing the result. Blocking. */
    fun import(ctx: Context, uri: Uri): String = try {
        importText(ctx, ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (e: Exception) {
        "I couldn't read that pack: ${e.message ?: "invalid file"}."
    }

    private fun importText(ctx: Context, text: String?): String {
        if (text == null) return "I couldn't open that file."
        if (text.length > 20_000_000) return "That pack is too large (over 20 MB)."
        val o = JSONObject(text)
        val id = o.optString("id").trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-")
        val title = o.optString("title").trim()
        val license = o.optString("license").trim()
        val source = o.optString("source").trim()
        return when {
            id.isEmpty() || title.isEmpty() -> "That file isn't a knowledge pack: it needs an id and a title."
            id == "aurelis-guide" -> "That name is reserved for the built-in guide."
            license.isEmpty() -> "I didn't import it: the pack must state its licence, so I only keep content you're allowed to use."
            source.isEmpty() -> "I didn't import it: the pack must say where its content comes from."
            else -> {
                val arr = o.optJSONArray("entries")
                val entries = ArrayList<Triple<String, String, String>>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val t = e.optString("title").trim()
                    val b = e.optString("body").trim()
                    if (t.isNotEmpty() && b.isNotEmpty()) entries.add(Triple(t, b, e.optString("source").trim()))
                }
                if (entries.isEmpty()) "That pack has no usable entries."
                else {
                    write(db(ctx), id, title, source, license, o.optString("version", "1"), entries, builtin = false)
                    "Added \"$title\" to the offline library: ${entries.size} entries. Source: $source. Licence: $license."
                }
            }
        }
    }

    fun remove(ctx: Context, id: String) {
        if (id == "aurelis-guide") return
        val db = db(ctx)
        db.delete("kb", "pack = ?", arrayOf(id))
        db.delete("packs", "id = ?", arrayOf(id))
    }

    private val STOP = setOf(
        "the", "a", "an", "is", "are", "was", "what", "how", "why", "who", "when", "where", "which", "do", "does",
        "did", "can", "could", "you", "your", "me", "my", "i", "to", "of", "in", "on", "for", "and", "or", "about",
        "tell", "please", "it", "this", "that", "with", "be", "should", "would", "will", "aurelis", "explain"
    )

    /** Full-text search. Never throws. */
    fun search(ctx: Context, query: String, limit: Int = 3): List<Hit> = try {
        val terms = query.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ")
            .filter { it.length > 2 && it !in STOP }.distinct().take(8)
        if (terms.isEmpty()) emptyList() else {
            val match = terms.joinToString(" OR ") { "$it*" }
            db(ctx).rawQuery(
                "SELECT pack, title, body, source FROM kb WHERE kb MATCH ? LIMIT 40", arrayOf(match)
            ).use { c ->
                val all = ArrayList<Pair<Hit, Int>>()
                while (c.moveToNext()) {
                    val h = Hit(c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "")
                    val tl = h.title.lowercase()
                    val bl = h.body.lowercase()
                    val score = terms.sumOf { t -> (if (tl.contains(t)) 3 else 0) + (if (bl.contains(t)) 1 else 0) }
                    all.add(h to score)
                }
                // Several words asked: a hit must match the title or at least two of them.
                val need = if (terms.size == 1) 1 else 2
                all.filter { it.second >= need }.sortedByDescending { it.second }.take(limit).map { it.first }
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Library context for a brain prompt (empty when nothing relevant). */
    fun context(ctx: Context, query: String): String {
        val hits = search(ctx, query, 2)
        if (hits.isEmpty()) return ""
        return hits.joinToString("\n") { "- ${it.title}: ${it.body.take(600)} (source: ${it.source})" }
    }

    /** A direct spoken answer from the library, naming the source, or an honest "not in the library". */
    fun answer(ctx: Context, query: String): String {
        val h = search(ctx, query, 1).firstOrNull()
            ?: return "That isn't in my offline library, so I can't answer it reliably without the internet."
        val body = h.body.split(Regex("(?<=[.!?])\\s+")).take(3).joinToString(" ")
        return "From my offline library, ${h.title}: $body Source: ${h.source}."
    }

    fun summary(ctx: Context): String {
        val p = packs(ctx)
        if (p.isEmpty()) return "My offline library is empty."
        val total = p.sumOf { it.entries }
        return "My offline library has ${p.size} pack${if (p.size == 1) "" else "s"} with $total entries: " +
            p.joinToString(", ") { it.title } + ". You can add packs in Settings."
    }

    fun exportTemplate(ctx: Context): File {
        val f = File(ctx.cacheDir, "knowledge-pack-template.json")
        f.writeText(
            """{"id":"my-pack","title":"My notes","source":"Where this comes from (book, website, yourself)","license":"The licence that lets you use it, e.g. CC BY 4.0 or Public domain or My own notes","version":"1","entries":[{"title":"Topic","body":"What AURELIS should know about it.","source":"optional, per entry"}]}"""
        )
        return f
    }

    private const val GUIDE_VERSION = "2"

    private val GUIDE: List<Triple<String, String, String>> = listOf(
        Triple("Waking AURELIS", "Say Hey AURELIS, say hello from standby, or clap twice if clap wake is on. The wake word is detected on the phone by Vosk; standby audio is never uploaded. You can turn continuous listening off at any time in Settings or from the notification.", ""),
        Triple("Listening modes", "In continuous conversation mode AURELIS keeps listening after each answer until you stop talking or say goodbye. In single-command mode it answers once and returns to standby. Say single command mode or continuous conversation mode, or change it in Settings.", ""),
        Triple("Interrupting AURELIS", "While AURELIS is speaking you can simply start talking to interrupt it. It uses the echo canceller and a voice detector to avoid reacting to its own voice. If that is turned off, say Hey AURELIS to interrupt. Say turn off voice interrupt to disable talking over it.", ""),
        Triple("Offline speech", "Offline, AURELIS records until you finish speaking, using a voice activity detector that tolerates short pauses, and transcribes with Whisper tiny English if its pack is downloaded, otherwise with Vosk. Online it can use Google speech recognition, which is usually the most accurate.", ""),
        Triple("Offline brain", "The offline brain is Qwen running on the phone through MediaPipe. Download it in Settings. Without internet and without Qwen, AURELIS can still run phone commands, reminders, tasks, memories, time, battery and the torch.", ""),
        Triple("Online brain", "Online, AURELIS uses Google Gemini with your own free API key stored encrypted on the phone. If the internet drops, AURELIS stops trying the cloud for about a minute and a half and uses the offline brain instead.", ""),
        Triple("Sensitive actions", "AURELIS always asks for confirmation before sending messages, placing calls, paying, deleting files or other important information. Say yes to continue or no to cancel.", ""),
        Triple("Screen control", "With the AURELIS accessibility service enabled you can say scroll down, go back, tap a button by name, type text, read my screen, summarise this page, take a screenshot or lock the phone. Android may require allowing restricted settings in App info first.", ""),
        Triple("Memory and privacy", "Things you ask AURELIS to remember are stored in its memory database on the phone and can be viewed and deleted under Memory. The offline library is separate from personal memory. Conversations are saved only if Save conversations is on.", ""),
        Triple("Knowledge packs", "Knowledge packs are JSON files with an id, title, source, licence and entries. Import them in Settings under Offline library. AURELIS answers from them offline and names the source. It only imports packs that state their licence.", ""),
        Triple("Updates", "AURELIS checks GitHub for new builds and shows Update available on the Home screen. Downloaded speech and brain models stay installed across updates.", "")
    )
}
