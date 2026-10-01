package xyz.ruin.droidref

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The few read-only calls of the hydrus Client API that importing needs.
 * Everything here blocks, so call it off the main thread.
 *
 * https://hydrusnetwork.github.io/hydrus/developer_api.html
 */
class HydrusClient(host: String, private val key: String) {
    private val base = normalizeHost(host)

    /**
     * IDs of every image matching [tags] (hydrus syntax, "-tag" negates),
     * newest import first. No tags lists every image.
     */
    fun search(tags: List<String>): List<Int> {
        val ids = getJson(
            "get_files/search_files",
            "tags" to JSONArray(tags + "system:filetype is image").toString(),
            "file_sort_asc" to "false",
        ).getJSONArray("file_ids")
        return List(ids.length()) { ids.getInt(it) }
    }

    /** SHA-256 hashes of [ids], which is how hydrus identifies a file anywhere. */
    fun hashes(ids: List<Int>): Map<Int, String> =
        // Batched so the query string stays a sensible length.
        ids.chunked(100).flatMap { batch ->
            val rows = getJson(
                "get_files/file_metadata",
                "file_ids" to JSONArray(batch).toString(),
                "only_return_identifiers" to "true",
            ).getJSONArray("metadata")
            List(rows.length()) { rows.getJSONObject(it) }.map { it.getInt("file_id") to it.getString("hash") }
        }.toMap()

    /** Tags starting with [text], most used first. */
    fun suggestTags(text: String): List<String> {
        val tags = getJson("add_tags/search_tags", "search" to text, "tag_display_type" to "display")
            .getJSONArray("tags")
        return List(tags.length()) { tags.getJSONObject(it) }
            .sortedByDescending { it.optInt("count") }
            .map { it.getString("value") }
    }

    fun thumbnail(id: Int): Bitmap? =
        get("get_files/thumbnail", "file_id" to id.toString()).let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** The original file, exactly as stored. */
    fun file(id: Int): ByteArray = get("get_files/file", "file_id" to id.toString())

    /** The file rendered to PNG by hydrus, for formats Android can't decode itself. */
    fun rendered(id: Int): ByteArray = get("get_files/render", "file_id" to id.toString())

    private fun getJson(path: String, vararg params: Pair<String, String>) =
        JSONObject(String(get(path, *params)))

    private fun get(path: String, vararg params: Pair<String, String>): ByteArray {
        val query = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val connection = URL("$base/$path?$query").openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("Hydrus-Client-API-Access-Key", key)
            connection.connectTimeout = 10000
            connection.readTimeout = 60000
            val code = connection.responseCode
            if (code !in 200..299) {
                // hydrus explains itself in the body, e.g. a missing permission.
                val reason = connection.errorStream?.use { String(it.readBytes()) }?.trim()?.take(200)
                throw IOException("Hydrus responded $code" + (reason?.let { ": $it" } ?: ""))
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** "desktop" -> "http://desktop:45869"; a scheme or port already given is kept. */
        fun normalizeHost(host: String): String {
            var h = host.trim().trimEnd('/')
            if (!h.contains("://")) h = "http://$h"
            if (URL(h).port == -1) h = "$h:$DEFAULT_PORT"
            return h
        }

        private const val DEFAULT_PORT = 45869
    }
}
