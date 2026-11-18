package tv.own.owntv.core.backup

import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ListRow
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.MediaType
import java.io.File

/**
 * Round trip for one playlist's category / channel lists, so they can be edited on a computer.
 *
 * **Export** writes a `slightlygood-lists` JSON file: every category and channel / movie / series of
 * the playlist as the app knows it (with the exact customization keys the app uses), plus the hide /
 * rename / order settings already stored for the profile. A companion editor page reads it.
 *
 * **Import** reads a `slightlygood-edits` (or a `slightlygood-lists`) file and applies its hide, rename
 * and order settings to the chosen playlist for the profile: only this playlist's entries are replaced,
 * every other playlist and every other customization (EPG matches, locks, custom categories) stays.
 * Keys start with the playlist's id, and the id in the file may differ from the playlist it is applied
 * to (another TV), so entries are re-pointed at the target id.
 *
 * Nothing here touches credentials: no URL, username or password is written.
 */
class PlaylistLists(
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val customize: CustomizationStore,
) {
    class Summary(val hiddenCategories: Int, val hiddenItems: Int, val renamed: Int, val reordered: Int)

    class BadFileException(message: String) : Exception(message)

    suspend fun export(sourceId: Long, profileId: Long, dir: File): Result<File> = runCatching {
        val source = sourceDao.getById(sourceId) ?: error("playlist $sourceId not found")
        val stored = customize.exportAll()
        dir.mkdirs()
        val file = File(dir, safeName(source.name) + "-lists.json")
        val partial = File(dir, file.name + ".part")
        // Streamed straight to disk, one section at a time: a big playlist (hundreds of thousands of rows)
        // must never be held as one JSON tree plus its string copy, which a TV's small heap cannot take.
        JsonWriter(partial.bufferedWriter(Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("format").value(FORMAT_LISTS)
            w.name("version").value(1)
            w.name("source").beginObject().name("id").value(source.id).name("name").value(source.name).name("type").value(source.type.name).endObject()
            w.name("profileId").value(profileId)
            w.name("sections").beginObject()
            for (type in SECTIONS) {
                val cats = categoryDao.forSource(sourceId, type)
                val catKeyById = cats.associate { it.id to itemKey(sourceId, it.remoteId, it.name) }
                w.name(type.name).beginObject()
                w.name("categories").beginArray()
                for (c in cats) w.beginObject().name("key").value(itemKey(sourceId, c.remoteId, c.name)).name("name").value(c.name).endObject()
                w.endArray()
                w.name("items").beginArray()
                val rows: List<ListRow> = when (type) {
                    MediaType.LIVE -> channelDao.listRows(sourceId)
                    MediaType.MOVIE -> movieDao.listRows(sourceId)
                    else -> seriesDao.listRows(sourceId)
                }
                for (r in rows) {
                    w.beginObject()
                        .name("key").value(itemKey(sourceId, r.remoteId, r.name))
                        .name("name").value(r.name)
                        .name("cat").value(r.categoryId?.let(catKeyById::get) ?: "")
                        .endObject()
                }
                w.endArray()
                w.endObject()
            }
            w.endObject()
            w.name("customizations").beginObject()
            for (type in SECTIONS) {
                val raw = stored["cust_${profileId}_${type.name}"]
                w.name(type.name)
                writeJson(w, if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw))
            }
            w.endObject()
            w.endObject()
        }
        if (file.exists()) file.delete()
        check(partial.renameTo(file)) { "could not finish the lists file" }
        file
    }

    suspend fun importEdits(file: File, sourceId: Long, profileId: Long): Result<Summary> = runCatching {
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            throw BadFileException("not a playlist edits file")
        }
        val format = root.optString("format")
        if (format != FORMAT_EDITS && format != FORMAT_LISTS) throw BadFileException("not a playlist edits file")
        val fromId = root.optJSONObject("source")?.optLong("id", -1L) ?: -1L
        if (fromId < 0) throw BadFileException("the file does not say which playlist it came from")
        sourceDao.getById(sourceId) ?: error("playlist $sourceId not found")
        val cust = root.optJSONObject("customizations") ?: throw BadFileException("no settings in the file")
        var hiddenCats = 0
        var hiddenItems = 0
        var renamed = 0
        var reordered = 0
        for (type in SECTIONS) {
            val edits = cust.optJSONObject(type.name) ?: continue
            val newHiddenCats = edits.optJSONArray("hiddenCats").strings().filter { it.startsWith("$fromId:") }.map { repoint(it, fromId, sourceId) }.toSet()
            val newHiddenItems = edits.optJSONObject("hiddenItems").stringMap(fromId, sourceId)
            val newCatNames = edits.optJSONObject("catNames").stringMap(fromId, sourceId)
            val newItemNames = edits.optJSONObject("itemNames").stringMap(fromId, sourceId)
            val newPositions = edits.optJSONObject("catPos").intMap(fromId, sourceId)
            val prefix = "$sourceId:"
            customize.update(profileId, type) { c ->
                c.copy(
                    hiddenCategories = c.hiddenCategories.filterNot { it.startsWith(prefix) }.toSet() + newHiddenCats,
                    hiddenItems = c.hiddenItems.filterKeys { !it.startsWith(prefix) } + newHiddenItems,
                    categoryNames = c.categoryNames.filterKeys { !it.startsWith(prefix) } + newCatNames,
                    itemNames = c.itemNames.filterKeys { !it.startsWith(prefix) } + newItemNames,
                    categoryPositions = c.categoryPositions.filterKeys { !it.startsWith(prefix) } + newPositions,
                )
            }
            hiddenCats += newHiddenCats.size
            hiddenItems += newHiddenItems.size
            renamed += newCatNames.size + newItemNames.size
            reordered += newPositions.size
        }
        Summary(hiddenCats, hiddenItems, renamed, reordered)
    }

    companion object {
        const val FORMAT_LISTS = "slightlygood-lists"
        const val FORMAT_EDITS = "slightlygood-edits"
        private val SECTIONS = listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)

        /** Same key the app builds in `CustomizeKeys`: `<sourceId>:<provider id, or the name when there is none>`. */
        internal fun itemKey(sourceId: Long, remoteId: String?, name: String) = "$sourceId:${remoteId ?: name}"

        /** Whether [raw] looks like one of these files — lets a shared upload path tell them from a real backup. */
        fun looksLikeEdits(text: String): Boolean {
            val head = text.take(400)
            return head.contains("\"$FORMAT_EDITS\"") || head.contains("\"$FORMAT_LISTS\"")
        }

        internal fun repoint(key: String, fromId: Long, toId: Long): String =
            if (key.startsWith("$fromId:")) "$toId:" + key.substring("$fromId:".length) else key

        private fun safeName(raw: String): String =
            raw.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifBlank { "playlist" }.take(60)

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        private fun JSONObject?.stringMap(fromId: Long, toId: Long): Map<String, String> {
            if (this == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            keys().forEach { k -> if (k.startsWith("$fromId:")) out[repoint(k, fromId, toId)] = getString(k) }
            return out
        }

        private fun JSONObject?.intMap(fromId: Long, toId: Long): Map<String, Int> {
            if (this == null) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            keys().forEach { k -> if (k.startsWith("$fromId:")) out[repoint(k, fromId, toId)] = getInt(k) }
            return out
        }
    }

private fun writeJson(w: JsonWriter, v: Any?) {
    when (v) {
        is JSONObject -> {
            w.beginObject()
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                w.name(k)
                writeJson(w, v.opt(k))
            }
            w.endObject()
        }
        is JSONArray -> {
            w.beginArray()
            for (i in 0 until v.length()) writeJson(w, v.opt(i))
            w.endArray()
        }
        null -> w.nullValue()
        JSONObject.NULL -> w.nullValue()
        is Boolean -> w.value(v)
        is Number -> w.value(v)
        else -> w.value(v.toString())
    }
}
}
