package xyz.ruin.droidref

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.ruin.droidref.Dialogs.column
import xyz.ruin.droidref.Dialogs.dp
import xyz.ruin.droidref.Dialogs.label
import xyz.ruin.droidref.Dialogs.themeColor

/**
 * Search a hydrus client by tag and pick images from the results. Only reads;
 * nothing in hydrus is changed.
 */
object HydrusDialog {
    private const val PREF_HOST = "hydrusHost"
    private const val PREF_KEY = "hydrusKey"
    private const val PREF_QUERY = "hydrusQuery"
    private const val PREF_BLACKLIST = "hydrusBlacklist"
    // Blacklisted tags whose own checkbox is off; they stay in the list but don't apply.
    private const val PREF_BLACKLIST_OFF = "hydrusBlacklistOff"

    /** Splits a comma-separated tag list; hydrus tags can contain spaces. */
    private fun tags(text: String?) = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Opens the search, asking for the server first if none is set. */
    fun show(context: Context, prefs: SharedPreferences, onPicked: (HydrusClient, List<Int>) -> Unit) {
        val client = client(prefs)
        if (client == null) {
            settings(context, prefs) { show(context, prefs, onPicked) }
        } else {
            search(context, prefs, client, onPicked)
        }
    }

    private fun client(prefs: SharedPreferences): HydrusClient? {
        val host = prefs.getString(PREF_HOST, null) ?: return null
        val key = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            HydrusClient(host, key)
        } catch (e: Exception) {
            null
        }
    }

    private fun settings(context: Context, prefs: SharedPreferences, onSaved: () -> Unit) {
        val host = EditText(context).apply {
            setText(prefs.getString(PREF_HOST, null))
            hint = "e.g. desktop or 100.x.y.z"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val key = EditText(context).apply {
            setText(prefs.getString(PREF_KEY, null))
            hint = "64 hex characters"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val blacklist = EditText(context).apply {
            setText(prefs.getString(PREF_BLACKLIST, null))
            hint = "e.g. rating:explicit, rating:questionable"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val layout = column(context).apply {
            addView(label(context, "Client API address"))
            addView(host)
            addView(label(context, "Access key (needs \"search for and fetch files\")"))
            addView(key)
            addView(label(context, "Blacklist (comma separated)"))
            addView(blacklist)
        }
        AlertDialog.Builder(context)
            .setTitle("Hydrus settings")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit()
                    .putString(PREF_HOST, host.text.toString().trim())
                    .putString(PREF_KEY, key.text.toString().trim())
                    .putString(PREF_BLACKLIST, tags(blacklist.text.toString()).joinToString(", "))
                    .apply()
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun search(
        context: Context,
        prefs: SharedPreferences,
        client: HydrusClient,
        onPicked: (HydrusClient, List<Int>) -> Unit
    ) {
        // Lives as long as the dialog; dismissing it stops searches and thumbnails.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val thumbs = Thumbnails(context, client, scope)
        var runSearch: () -> Unit = {}

        val query = MultiAutoCompleteTextView(context).apply {
            setText(prefs.getString(PREF_QUERY, ""))
            hint = "tags, comma separated; -tag excludes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            threshold = 2
            setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            setAdapter(TagSuggestions(context, client))
            setSelection(text.length)
        }
        val blacklist = Blacklist(context, prefs, client) { runSearch() }
        val status = TextView(context).apply { setPadding(0, context.dp(8), 0, context.dp(8)) }
        val grid = GridView(context).apply {
            columnWidth = context.dp(96)
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = context.dp(4)
            verticalSpacing = context.dp(4)
            choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
            adapter = thumbs
            // Takes whatever height the blacklist leaves.
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val layout = column(context).apply {
            addView(query)
            addView(blacklist.view)
            addView(status)
            addView(grid)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.65).toInt()
            )
        }

        // AlertDialog stretches the view it's given, so the fixed height goes on a child.
        val dialog = AlertDialog.Builder(context)
            .setTitle("Add from Hydrus")
            .setView(FrameLayout(context).apply { addView(layout) })
            .setPositiveButton("Add", null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Settings…", null)
            .setOnDismissListener { scope.cancel() }
            .show()
        val add = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        // In result order, newest first.
        fun checked(): List<Int> {
            val picked = grid.checkedItemIds.toHashSet()
            return thumbs.ids.filter { it.toLong() in picked }
        }

        fun updateAdd() {
            val n = grid.checkedItemCount
            add.isEnabled = n > 0
            add.text = if (n > 0) "Add $n" else "Add"
        }

        var running: Job? = null
        runSearch = {
            val text = query.text.toString()
            prefs.edit().putString(PREF_QUERY, text).apply()
            val typed = tags(text)
            // The blacklist rides along with the search, but stays out of the box.
            val excluded = blacklist.active().map { "-$it" }
            running?.cancel()
            grid.clearChoices()
            thumbs.show(emptyList())
            updateAdd()
            status.text = "Searching…"
            running = scope.launch {
                try {
                    val shown = async(Dispatchers.IO) { client.search(typed + excluded) }
                    // Searching again without the blacklist is the only way to know what it hid.
                    val all = if (excluded.isEmpty()) null else async(Dispatchers.IO) { client.search(typed).size }
                    val ids = shown.await()
                    thumbs.show(ids)
                    val found = when (ids.size) {
                        0 -> "No images"
                        1 -> "1 image"
                        else -> "${ids.size} images"
                    }
                    val hidden = all?.await()?.minus(ids.size) ?: 0
                    status.text = if (hidden > 0) "$found ($hidden hidden by blacklist)" else found
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Hydrus search failed")
                    status.text = e.message ?: e.toString()
                }
            }
        }

        query.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_SEARCH || enter) {
                runSearch()
                true
            } else {
                false
            }
        }
        grid.setOnItemClickListener { _, _, _, _ ->
            thumbs.notifyDataSetChanged()
            updateAdd()
        }
        thumbs.isChecked = grid::isItemChecked
        add.setOnClickListener {
            onPicked(client, checked())
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            settings(context, prefs) { show(context, prefs, onPicked) }
        }
        runSearch()
    }

    /**
     * The blacklist inside the search dialog: one checkbox for the whole thing,
     * which expands to a checkbox per tag and a field to add more. The tags and
     * their own checkboxes are saved; the master checkbox starts on every time,
     * so the blacklist is only ever off until the dialog closes.
     */
    private class Blacklist(
        private val context: Context,
        private val prefs: SharedPreferences,
        client: HydrusClient,
        private val onChange: () -> Unit,
    ) {
        private val master = CheckBox(context).apply {
            text = "Blacklist"
            isChecked = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        private val expand = TextView(context).apply {
            setPadding(context.dp(12), context.dp(8), 0, context.dp(8))
            setTextColor(context.themeColor(R.attr.droidrefPrimary))
        }
        private val rows = column(context).apply { setPadding(0, 0, 0, 0) }
        private val scroll = ScrollView(context).apply { addView(rows) }
        private val input = MultiAutoCompleteTextView(context).apply {
            hint = "tag to blacklist"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            threshold = 2
            setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            setAdapter(TagSuggestions(context, client))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        private val details = column(context).apply {
            setPadding(context.dp(24), 0, 0, 0)
            visibility = View.GONE
            addView(scroll)
            addView(LinearLayout(context).apply {
                addView(input)
                addView(Button(context).apply {
                    text = "Add"
                    setOnClickListener { addTyped() }
                })
            })
        }
        val view = column(context).apply {
            setPadding(0, 0, 0, 0)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(master)
                addView(expand)
            })
            addView(details)
        }

        private var all = tags(prefs.getString(PREF_BLACKLIST, null))
        private var off = tags(prefs.getString(PREF_BLACKLIST_OFF, null)).toSet()

        init {
            master.setOnCheckedChangeListener { _, _ ->
                rebuild()
                onChange()
            }
            expand.setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                rebuild()
            }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) addTyped()
                action == EditorInfo.IME_ACTION_DONE
            }
            rebuild()
        }

        /** Tags to exclude from the search right now. */
        fun active(): List<String> = if (master.isChecked) all.filter { it !in off } else emptyList()

        private fun save() {
            off = off.intersect(all.toSet())
            prefs.edit()
                .putString(PREF_BLACKLIST, all.joinToString(", "))
                .putString(PREF_BLACKLIST_OFF, off.joinToString(", "))
                .apply()
        }

        private fun addTyped() {
            // "-tag" would mean "blacklist the absence of tag", which nobody wants here.
            val new = tags(input.text.toString()).map { it.removePrefix("-").trim() }.filter { it.isNotEmpty() }
            input.setText("")
            if (new.isEmpty()) return
            all = (all + new).distinct()
            off = off - new.toSet()
            save()
            rebuild()
            if (master.isChecked) onChange()
        }

        private fun rebuild() {
            val on = all.count { it !in off }
            expand.text = (if (all.size == on) "${all.size} tags" else "$on of ${all.size} tags") +
                    if (details.visibility == View.VISIBLE) " ▴" else " ▾"
            rows.removeAllViews()
            for (tag in all) {
                rows.addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(CheckBox(context).apply {
                        text = tag
                        isChecked = tag !in off
                        isEnabled = master.isChecked
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnCheckedChangeListener { _, checked ->
                            off = if (checked) off - tag else off + tag
                            save()
                            rebuild()
                            onChange()
                        }
                    })
                    addView(TextView(context).apply {
                        text = "✕"
                        contentDescription = "Remove $tag from the blacklist"
                        setPadding(context.dp(16), context.dp(8), context.dp(8), context.dp(8))
                        setOnClickListener {
                            val wasActive = tag in active()
                            all = all - tag
                            save()
                            rebuild()
                            if (wasActive) onChange()
                        }
                    })
                })
            }
            // Long lists scroll rather than push the results off the dialog.
            val rowHeight = context.dp(44)
            scroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (all.size > 3) rowHeight * 3 else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * Result grid. Thumbnails are fetched only for cells on screen, and a cell
     * scrolled away before its thumbnail arrived drops the request.
     */
    private class Thumbnails(
        private val context: Context,
        private val client: HydrusClient,
        private val scope: CoroutineScope,
    ) : BaseAdapter() {
        var ids: List<Int> = emptyList()
            private set
        var isChecked: (Int) -> Boolean = { false }
        private val cache = object : LruCache<Int, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
        private val failed = HashSet<Int>()
        private val fetchers = Dispatchers.IO.limitedParallelism(4)

        private class Cell(context: Context) : ImageView(context) {
            var fileId = -1
            var job: Job? = null
        }

        fun show(ids: List<Int>) {
            this.ids = ids
            notifyDataSetChanged()
        }

        override fun getCount() = ids.size
        override fun getItem(position: Int) = ids[position]
        override fun getItemId(position: Int) = ids[position].toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? Cell ?: Cell(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(96))
            }
            val id = ids[position]
            if (cell.fileId != id) {
                cell.job?.cancel()
                cell.job = null
                cell.fileId = id
            }
            val checked = isChecked(position)
            // A ring in the accent colour marks picked images.
            val ring = if (checked) context.dp(4) else 0
            cell.setPadding(ring, ring, ring, ring)
            cell.setBackgroundColor(if (checked) context.themeColor(R.attr.droidrefPrimary) else 0)
            val cached = cache.get(id)
            cell.setImageBitmap(cached)
            if (cached == null && cell.job == null && id !in failed) {
                cell.job = scope.launch {
                    try {
                        val bitmap = withContext(fetchers) { client.thumbnail(id) }
                        if (bitmap == null) {
                            failed.add(id)
                        } else {
                            cache.put(id, bitmap)
                            if (cell.fileId == id) cell.setImageBitmap(bitmap)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Not retried, so a broken one isn't fetched again on every redraw.
                        failed.add(id)
                        Timber.w(e, "No thumbnail for %d", id)
                    } finally {
                        if (cell.fileId == id) cell.job = null
                    }
                }
            }
            return cell
        }
    }

    /** Tag completions from hydrus for the tag being typed; keeps a leading "-". */
    private class TagSuggestions(context: Context, private val client: HydrusClient) :
        ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {
        private var results: List<String> = emptyList()

        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]

        override fun getFilter() = object : Filter() {
            // Runs on a worker thread, so the request can block here.
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val typed = constraint?.toString()?.trim().orEmpty()
                val negated = typed.startsWith("-")
                val text = typed.removePrefix("-")
                val found = if (text.length < 2) emptyList() else try {
                    client.suggestTags(text).take(30).map { if (negated) "-$it" else it }
                } catch (e: Exception) {
                    Timber.w(e, "Tag suggestions failed")
                    emptyList()
                }
                return FilterResults().apply {
                    values = found
                    count = found.size
                }
            }

            override fun publishResults(constraint: CharSequence?, filterResults: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                results = filterResults.values as? List<String> ?: emptyList()
                if (results.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
            }
        }
    }
}
