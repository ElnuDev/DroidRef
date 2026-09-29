package xyz.ruin.droidref

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Patterns
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.xiaopo.flying.sticker.*
import com.xiaopo.flying.sticker.StickerViewModel.Alignment
import com.xiaopo.flying.sticker.StickerViewModel.Arrangement
import com.xiaopo.flying.sticker.StickerViewModel.GridMode
import com.xiaopo.flying.sticker.StickerViewModel.Normalization
import com.xiaopo.flying.sticker.StickerViewModel.Tool
import com.xiaopo.flying.sticker.iconEvents.DeleteIconEvent
import com.xiaopo.flying.sticker.iconEvents.FlipHorizontallyEvent
import com.xiaopo.flying.sticker.iconEvents.FlipVerticallyEvent
import com.xiaopo.flying.sticker.iconEvents.ZoomIconEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.ruin.droidref.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger


class MainActivity : AppCompatActivity(), StickerViewModel.BoardListener {
    private lateinit var stickerViewModel: StickerViewModel
    private lateinit var binding: ActivityMainBinding
    private lateinit var io: BoardIO
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        importUris(it)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(::importFolder)
    }
    private val pickReplacement = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::replaceImage)
    }
    private val openBoard = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::loadBoard)
    }
    private val createBoard =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let(::saveBoard)
        }

    private val items get() = stickerViewModel.stickers.value!!

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        stickerViewModel = ViewModelProvider(this)[StickerViewModel::class.java]
        stickerViewModel.stickerOperationListener = RedrawListener(binding.stickerView)
        stickerViewModel.boardListener = this
        binding.viewModel = stickerViewModel
        binding.lifecycleOwner = this
        binding.executePendingBindings()

        io = BoardIO(this)
        BlobStore.init(File(filesDir, "blobs"))

        setupIcons()
        setupButtons()
        setupSettings()
        updateFrameInsets()

        if (!stickerViewModel.sessionStarted) {
            // Fresh process: nothing references old blobs, so start clean and
            // bring back the board from last time.
            stickerViewModel.sessionStarted = true
            BlobStore.clear()
            restoreAutosave {
                if (savedInstanceState == null) {
                    handleIntent(intent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        autosave()
    }

    // region Intents

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
                        importUris(listOf(it))
                    }
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::handleText)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
                importUris(uris.orEmpty().mapNotNull { it as? Uri })
            }
            Intent.ACTION_VIEW -> intent.data?.let { importUris(listOf(it)) }
        }
    }

    /** Shared or pasted text: a link is downloaded, anything else becomes a note. */
    private fun handleText(text: String) {
        val trimmed = text.trim()
        if (Patterns.WEB_URL.matcher(trimmed).matches()) {
            downloadImage(trimmed)
        } else if (trimmed.isNotEmpty()) {
            stickerViewModel.addNote(NoteSticker(trimmed))
        }
    }

    // endregion

    // region Background work

    private var busyCount = 0

    private fun showProgress(message: String) {
        busyCount++
        binding.progressText.text = message
        binding.progressBarHolder.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        busyCount = maxOf(0, busyCount - 1)
        if (busyCount == 0) {
            binding.progressBarHolder.visibility = View.GONE
        }
    }

    /** Runs [work] off the main thread with a progress overlay, then [done] on the main thread. */
    private fun <T> runBusy(message: String, work: () -> T, done: (T) -> Unit) {
        lifecycleScope.launch {
            showProgress(message)
            try {
                val result = withContext(Dispatchers.IO) { work() }
                done(result)
            } catch (e: Exception) {
                Timber.e(e)
                toast(e.message ?: e.toString())
            } finally {
                hideProgress()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // endregion

    // region Importing

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val total = uris.size
        lifecycleScope.launch {
            showProgress("Importing 0 / $total")
            val done = AtomicInteger()
            val decoders = Dispatchers.IO.limitedParallelism(4)
            val results = try {
                uris.map { uri ->
                    async(decoders) {
                        val sticker = try {
                            io.loadImage(uri)
                        } catch (e: Exception) {
                            Timber.e(e, "Could not import %s", uri)
                            null
                        }
                        val n = done.incrementAndGet()
                        withContext(Dispatchers.Main) { binding.progressText.text = "Importing $n / $total" }
                        sticker
                    }
                }.awaitAll()
            } finally {
                hideProgress()
            }
            val stickers = results.filterNotNull()
            stickerViewModel.addStickers(stickers)
            val failed = total - stickers.size
            when {
                failed > 0 -> toast("$failed of $total files could not be opened as images")
                total > 1 -> toast("Added $total images")
            }
        }
    }

    private fun importFolder(tree: Uri) {
        runBusy("Scanning folder…", { io.imagesInTree(tree) }) { uris ->
            if (uris.isEmpty()) toast("No images found in folder.") else importUris(uris)
        }
    }

    private fun downloadImage(url: String) {
        runBusy("Downloading…", { io.download(url) }) { stickerViewModel.addSticker(it) }
    }

    private fun paste() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("Clipboard is empty")
            return
        }
        val uris = ArrayList<Uri>()
        var text: String? = null
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri
            if (uri != null) uris.add(uri) else if (text == null) text = item.coerceToText(this)?.toString()
        }
        if (uris.isNotEmpty()) importUris(uris) else text?.let(::handleText)
    }

    private fun copySelection() {
        val selected = stickerViewModel.selected()
        val clipboard = getSystemService(ClipboardManager::class.java)
        val image = selected.filterIsInstance<DrawableSticker>().firstOrNull()
        val note = selected.filterIsInstance<NoteSticker>().firstOrNull()
        when {
            image != null -> runBusy("Copying…", { io.clipboardImage(image) }) { file ->
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "Image", uri))
                toast("Copied image")
            }
            note != null -> {
                clipboard.setPrimaryClip(ClipData.newPlainText("Note", note.text))
                toast("Copied note text")
            }
            else -> toast("Select an image or note to copy")
        }
    }

    private fun replaceImage(uri: Uri) {
        val target = stickerViewModel.selected().filterIsInstance<DrawableSticker>().singleOrNull() ?: return
        runBusy("Replacing…", { io.loadImage(uri) as? DrawableSticker }) { loaded ->
            if (loaded == null) {
                toast("Could not decode image")
                return@runBusy
            }
            stickerViewModel.change {
                target.replaceDrawable(loaded.drawable, loaded.blobKey)
                target.name = loaded.name
                target.source = null
            }
        }
    }

    // endregion

    // region Boards

    private fun boardSnapshot(): Pair<Matrix, List<Sticker>> =
        Matrix(stickerViewModel.canvasMatrix.value!!.getMatrix()) to items.map { it.copy(true) }

    private fun save() {
        val uri = stickerViewModel.currentFileName?.let(Uri::parse)
        if (uri == null) saveAs() else saveBoard(uri)
    }

    private fun saveAs() {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        createBoard.launch(formatter.format(Date()) + "." + SAVE_FILE_EXTENSION)
    }

    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Not all providers offer persistable grants; Save will ask again.
        }
    }

    private fun saveBoard(uri: Uri) {
        val (camera, copies) = boardSnapshot()
        runBusy("Saving…", {
            io.writeBoard(uri, camera, copies)
            io.displayName(uri)
        }) { name ->
            keepAccess(uri)
            stickerViewModel.currentFileName = uri.toString()
            Toast.makeText(this, "Saved ${name ?: ""}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBoard(uri: Uri) {
        runBusy("Opening…", { io.readBoard(uri) }) { board ->
            keepAccess(uri)
            stickerViewModel.loadBoard(board)
            stickerViewModel.currentFileName = uri.toString()
        }
    }

    private fun newBoard() {
        stickerViewModel.removeAllStickers()
        stickerViewModel.resetView()
        stickerViewModel.currentFileName = null
    }

    private var savedRevision = -1L
    private val autosaveSoon = Runnable { autosave() }

    /** Autosave a little while after the last change, so a crash loses little. */
    private fun scheduleAutosave() {
        binding.root.removeCallbacks(autosaveSoon)
        binding.root.postDelayed(autosaveSoon, AUTOSAVE_DELAY_MS)
    }

    private fun autosave() {
        binding.root.removeCallbacks(autosaveSoon)
        // Nothing to write until the previous session's board has been restored.
        if (restoring) return
        val revision = stickerViewModel.revision.value!!
        val cameraChanged = !stickerViewModel.canvasMatrix.value!!.getMatrix().equals(savedCamera)
        if (revision == savedRevision && !cameraChanged) return
        savedRevision = revision
        savedCamera.set(stickerViewModel.canvasMatrix.value!!.getMatrix())
        val (camera, copies) = boardSnapshot()
        prefs.edit().putString(PREF_CURRENT_FILE, stickerViewModel.currentFileName).apply()
        val file = File(filesDir, AUTOSAVE_FILE)
        autosaveExecutor.execute {
            try {
                if (copies.isEmpty()) file.delete() else io.writeBoard(file, camera, copies)
            } catch (e: Exception) {
                Timber.e(e, "Autosave failed")
            }
        }
    }

    private var restoring = false
    private val savedCamera = Matrix()

    private fun restoreAutosave(then: () -> Unit) {
        val file = File(filesDir, AUTOSAVE_FILE)
        if (!file.exists()) {
            then()
            return
        }
        restoring = true
        lifecycleScope.launch {
            showProgress("Restoring board…")
            try {
                // Wait for any autosave still being written by a previous activity.
                val board = withContext(Dispatchers.IO) {
                    autosaveExecutor.submit {}.get()
                    io.readBoard(file)
                }
                stickerViewModel.loadBoard(board)
                stickerViewModel.currentFileName = prefs.getString(PREF_CURRENT_FILE, null)
            } catch (e: Exception) {
                Timber.e(e)
                toast("Failed to load previous board state.")
            } finally {
                hideProgress()
                restoring = false
                savedRevision = stickerViewModel.revision.value!!
                savedCamera.set(stickerViewModel.canvasMatrix.value!!.getMatrix())
            }
            then()
        }
    }

    // endregion

    // region Exporting

    private fun ensureWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this, arrayOf(permission), PERM_RQST_CODE)
        toast("Allow storage access, then export again")
        return false
    }

    private fun exportImages(stickers: List<Sticker>) {
        if (stickers.none { it is DrawableSticker }) {
            toast("No images to export")
            return
        }
        if (!ensureWritePermission()) return
        val copies = stickers.map { it.copy(true) }
        runBusy("Exporting…", { io.exportImages(copies, BoardIO.EXPORT_FOLDER) }) { count ->
            toast("Exported $count images to Pictures/${BoardIO.EXPORT_FOLDER}")
        }
    }

    private fun exportScene(stickers: List<Sticker>) {
        if (stickers.isEmpty()) {
            toast("Nothing to export")
            return
        }
        if (!ensureWritePermission()) return
        val copies = stickers.map { it.copy(true) }
        val background = stickerViewModel.backgroundColor.value!!
        val grayscale = stickerViewModel.canvasGrayscale.value == true
        val name = "DroidRef_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        runBusy("Rendering…", { io.exportScene(copies, background, grayscale, name) }) { path ->
            toast("Exported $path")
        }
    }

    // endregion

    // region UI

    private fun setupIcons() {
        val deleteIcon = BitmapStickerIcon(
            ContextCompat.getDrawable(this, com.xiaopo.flying.sticker.R.drawable.sticker_ic_close_white_18dp),
            BitmapStickerIcon.LEFT_TOP
        )
        deleteIcon.iconEvent = DeleteIconEvent()
        val zoomIcon = BitmapStickerIcon(
            ContextCompat.getDrawable(this, com.xiaopo.flying.sticker.R.drawable.sticker_ic_scale_white_18dp),
            BitmapStickerIcon.RIGHT_BOTTOM
        )
        zoomIcon.iconEvent = ZoomIconEvent()
        val flipIcon = BitmapStickerIcon(
            ContextCompat.getDrawable(this, com.xiaopo.flying.sticker.R.drawable.sticker_ic_flip_white_18dp),
            BitmapStickerIcon.RIGHT_TOP
        )
        flipIcon.iconEvent = FlipHorizontallyEvent()
        val flipVerticallyIcon = BitmapStickerIcon(
            ContextCompat.getDrawable(this, com.xiaopo.flying.sticker.R.drawable.sticker_ic_flip_vert_white_18dp),
            BitmapStickerIcon.LEFT_BOTTOM
        )
        flipVerticallyIcon.iconEvent = FlipVerticallyEvent()
        stickerViewModel.icons.value = arrayListOf(deleteIcon, zoomIcon, flipIcon, flipVerticallyIcon)
        stickerViewModel.activeIcons.value = stickerViewModel.icons.value
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle("Confirm")
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun setupButtons() {
        val vm = stickerViewModel
        binding.buttonOpen.setOnClickListener { openBoard.launch(arrayOf("*/*")) }
        binding.buttonSave.setOnClickListener { save() }
        binding.buttonSaveAs.setOnClickListener { saveAs() }
        binding.buttonNew.setOnClickListener {
            confirm("Are you sure you want to create a new board?") { newBoard() }
        }
        binding.buttonUndo.setOnClickListener { vm.undo() }
        binding.buttonRedo.setOnClickListener { vm.redo() }
        binding.buttonMenu.setOnClickListener { showMenu() }

        binding.buttonAdd.setOnClickListener { pickImages.launch("image/*") }
        binding.buttonAdd.setOnLongClickListener { pickFolder.launch(null); true }
        binding.buttonPaste.setOnClickListener { paste() }
        binding.buttonNote.setOnClickListener { addNote() }
        binding.buttonDraw.setOnClickListener {
            vm.tool.value = if (vm.tool.value == Tool.DRAW) Tool.SELECT else Tool.DRAW
        }
        binding.buttonDraw.setOnLongClickListener { editPen(); true }
        binding.buttonPicker.setOnClickListener {
            vm.tool.value = if (vm.tool.value == Tool.PICK_COLOR) Tool.SELECT else Tool.PICK_COLOR
        }
        binding.buttonArrange.setOnClickListener { vm.arrange(Arrangement.OPTIMAL) }
        binding.buttonReset.setOnClickListener { vm.fitAll() }
        binding.buttonReset.setOnLongClickListener { vm.resetView(); true }
        binding.buttonDuplicate.setOnClickListener { withSelection { vm.duplicateSelection() } }
        binding.buttonDelete.setOnClickListener { withSelection { vm.deleteSelection() } }
        binding.buttonResetZoom.setOnClickListener { withSelection { vm.resetTransform() } }
        binding.buttonResetCrop.setOnClickListener { withSelection { vm.resetCrop() } }
        binding.buttonRotate.setOnLongClickListener {
            vm.resetCurrentStickerRotation()
            true
        }
        binding.buttonHideShowUI.setOnCheckedChangeListener { _, isToggled -> setUIVisibility(isToggled) }

        vm.revision.observe(this) { scheduleAutosave() }

        vm.tool.observe(this) { tool ->
            binding.buttonDraw.isChecked = tool == Tool.DRAW
            binding.buttonPicker.isChecked = tool == Tool.PICK_COLOR
            binding.stickerView.invalidate()
        }
    }

    private fun withSelection(action: () -> Unit) {
        if (stickerViewModel.selection.isEmpty()) {
            toast("Nothing selected. Tap an item; long-press to select several.")
        } else {
            action()
        }
    }

    private fun setupSettings() {
        val vm = stickerViewModel
        vm.gridMode.value = GridMode.entries[prefs.getInt(PREF_GRID, 0).coerceIn(0, GridMode.entries.size - 1)]
        vm.snapToGrid.value = prefs.getBoolean(PREF_SNAP, false)
        vm.canvasGrayscale.value = prefs.getBoolean(PREF_GRAYSCALE, false)
        vm.backgroundColor.value = prefs.getInt(PREF_BACKGROUND, StickerViewModel.DEFAULT_BACKGROUND)
        vm.autoArrange.value = prefs.getBoolean(PREF_AUTO_ARRANGE, true)
        vm.penColor = prefs.getInt(PREF_PEN_COLOR, vm.penColor)
        vm.penWidth = prefs.getFloat(PREF_PEN_WIDTH, vm.penWidth)

        val redraw = { binding.stickerView.invalidate() }
        vm.gridMode.observe(this) { prefs.edit().putInt(PREF_GRID, it.ordinal).apply(); redraw() }
        vm.snapToGrid.observe(this) { prefs.edit().putBoolean(PREF_SNAP, it).apply() }
        vm.canvasGrayscale.observe(this) { prefs.edit().putBoolean(PREF_GRAYSCALE, it).apply(); redraw() }
        vm.backgroundColor.observe(this) { prefs.edit().putInt(PREF_BACKGROUND, it).apply(); redraw() }
        vm.autoArrange.observe(this) { prefs.edit().putBoolean(PREF_AUTO_ARRANGE, it).apply() }
    }

    private fun savePen() {
        prefs.edit()
            .putInt(PREF_PEN_COLOR, stickerViewModel.penColor)
            .putFloat(PREF_PEN_WIDTH, stickerViewModel.penWidth)
            .apply()
    }

    private fun editPen() {
        Dialogs.pen(this, stickerViewModel.penColor, stickerViewModel.penWidth) { color, width ->
            stickerViewModel.penColor = color
            stickerViewModel.penWidth = width
            savePen()
        }
    }

    private fun addNote() {
        Dialogs.note(this, null) { text, textColor, background ->
            stickerViewModel.addNote(NoteSticker(text, textColor, background))
        }
    }

    private fun showMenu() {
        val vm = stickerViewModel
        val popup = PopupMenu(this, binding.buttonMenu)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        val menu = popup.menu
        menu.findItem(R.id.action_auto_arrange).isChecked = vm.autoArrange.value == true
        menu.findItem(R.id.action_canvas_grayscale).isChecked = vm.canvasGrayscale.value == true
        menu.findItem(R.id.action_snap).isChecked = vm.snapToGrid.value == true
        menu.findItem(
            when (vm.gridMode.value) {
                GridMode.LINES -> R.id.action_grid_lines
                GridMode.DOTS -> R.id.action_grid_dots
                else -> R.id.action_grid_none
            }
        ).isChecked = true
        popup.setOnMenuItemClickListener(::onMenuItem)
        popup.show()
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        val vm = stickerViewModel
        when (item.itemId) {
            R.id.action_add_images -> pickImages.launch("image/*")
            R.id.action_add_folder -> pickFolder.launch(null)
            R.id.action_add_link -> Dialogs.text(this, "Image from link", null, "https://…") { handleText(it) }
            R.id.action_paste -> paste()
            R.id.action_add_note -> addNote()

            R.id.action_select_all -> vm.selectAll()
            R.id.action_deselect -> vm.clearSelection()
            R.id.action_copy -> withSelection { copySelection() }
            R.id.action_duplicate -> withSelection { vm.duplicateSelection() }
            R.id.action_delete -> withSelection { vm.deleteSelection() }
            R.id.action_front -> withSelection { vm.sendToFront() }
            R.id.action_back -> withSelection { vm.sendToBack() }
            R.id.action_flip_h -> withSelection { vm.flipSelection(StickerView.FLIP_HORIZONTALLY) }
            R.id.action_flip_v -> withSelection { vm.flipSelection(StickerView.FLIP_VERTICALLY) }
            R.id.action_reset_transform -> withSelection { vm.resetTransform() }
            R.id.action_reset_rotation -> withSelection { vm.resetCurrentStickerRotation() }
            R.id.action_reset_crop -> withSelection { vm.resetCrop() }
            R.id.action_grayscale -> withSelection { vm.toggleGrayscale() }
            R.id.action_smooth -> withSelection { vm.toggleSmooth() }
            R.id.action_opacity -> withSelection { editOpacity() }
            R.id.action_lock_items -> withSelection { vm.toggleLocked() }
            R.id.action_group -> withSelection { vm.group() }
            R.id.action_ungroup -> withSelection { vm.ungroup() }
            R.id.action_comment -> withSelection { editComment() }
            R.id.action_edit_note -> {
                val note = vm.selected().filterIsInstance<NoteSticker>().firstOrNull()
                if (note == null) toast("Select a note to edit") else onEditNote(note)
            }
            R.id.action_replace -> {
                if (vm.selected().filterIsInstance<DrawableSticker>().size != 1) {
                    toast("Select one image to replace")
                } else {
                    pickReplacement.launch("image/*")
                }
            }
            R.id.action_open_source -> openSource()
            R.id.action_crop_selected -> withSelection { vm.cropDestructively(resources, false) }

            R.id.action_arrange_optimal -> vm.arrange(Arrangement.OPTIMAL)
            R.id.action_arrange_name -> vm.arrange(Arrangement.NAME)
            R.id.action_arrange_order -> vm.arrange(Arrangement.ORDER)
            R.id.action_arrange_random -> vm.arrange(Arrangement.RANDOM)
            R.id.action_auto_arrange -> vm.autoArrange.value = !item.isChecked

            R.id.action_align_left -> vm.align(Alignment.LEFT)
            R.id.action_align_right -> vm.align(Alignment.RIGHT)
            R.id.action_align_top -> vm.align(Alignment.TOP)
            R.id.action_align_bottom -> vm.align(Alignment.BOTTOM)
            R.id.action_align_row -> vm.align(Alignment.ROW)
            R.id.action_align_column -> vm.align(Alignment.COLUMN)
            R.id.action_align_stack -> vm.align(Alignment.STACK)

            R.id.action_normalize_height -> vm.normalize(Normalization.HEIGHT)
            R.id.action_normalize_width -> vm.normalize(Normalization.WIDTH)
            R.id.action_normalize_scale -> vm.normalize(Normalization.SCALE)
            R.id.action_normalize_size -> vm.normalize(Normalization.SIZE)
            R.id.action_normalize_area -> vm.normalize(Normalization.AREA)

            R.id.action_fit_all -> vm.fitAll()
            R.id.action_zoom_selection -> vm.zoomToSelection()
            R.id.action_reset_camera -> vm.resetView()
            R.id.action_reset_zoom -> vm.resetZoom()
            R.id.action_slideshow -> startSlideshow()
            R.id.action_canvas_grayscale -> vm.canvasGrayscale.value = !item.isChecked
            R.id.action_grid_none -> vm.gridMode.value = GridMode.NONE
            R.id.action_grid_lines -> vm.gridMode.value = GridMode.LINES
            R.id.action_grid_dots -> vm.gridMode.value = GridMode.DOTS
            R.id.action_snap -> vm.snapToGrid.value = !item.isChecked
            R.id.action_background -> Dialogs.color(this, "Background color", vm.backgroundColor.value!!) {
                vm.backgroundColor.value = it or 0xFF000000.toInt()
            }
            R.id.action_pen -> editPen()

            R.id.action_export_selected -> withSelection { exportImages(vm.selected()) }
            R.id.action_export_all -> exportImages(ArrayList(items))
            R.id.action_export_selection_scene -> withSelection { exportScene(vm.selected()) }
            R.id.action_export_scene -> exportScene(ArrayList(items))
            R.id.action_crop_all -> confirm(
                "Crop all images permanently? This discards the cropped-away pixels, " +
                        "which saves space and improves performance."
            ) { vm.cropDestructively(resources, true) }
            else -> return false
        }
        return true
    }

    private fun editOpacity() {
        val vm = stickerViewModel
        val chosen = vm.selected()
        val original = chosen.map { it.opacity }
        Dialogs.slider(
            this, "Opacity", chosen.first().opacity, 255,
            format = { "${it * 100 / 255}%" },
            onChange = { value ->
                chosen.forEach { it.opacity = value }
                binding.stickerView.invalidate()
            },
            onDone = { value ->
                chosen.forEachIndexed { i, s -> s.opacity = original[i] }
                if (value != null) vm.setOpacity(value) else binding.stickerView.invalidate()
            }
        )
    }

    private fun editComment() {
        val chosen = stickerViewModel.selected()
        Dialogs.text(this, "Comment", chosen.first().comment, "Write a comment", multiLine = true) { text ->
            stickerViewModel.change { chosen.forEach { it.comment = text.ifBlank { null } } }
        }
    }

    private fun openSource() {
        val source = stickerViewModel.selected().firstNotNullOfOrNull { it.source }
        if (source == null) {
            toast("No source link for the selection")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source)))
        } catch (e: Exception) {
            toast("Can't open $source")
        }
    }

    private fun startSlideshow() {
        val chosen = stickerViewModel.targets()
        if (chosen.isEmpty()) {
            toast("Not enough images for slideshow.")
            return
        }
        val choices = arrayOf("5 seconds", "10 seconds", "30 seconds", "1 minute", "5 minutes")
        val millis = longArrayOf(5_000, 10_000, 30_000, 60_000, 300_000)
        AlertDialog.Builder(this)
            .setTitle("Show each image for")
            .setItems(choices) { _, which -> SlideshowDialog(this, chosen, millis[which]).show() }
            .show()
    }

    private fun updateFrameInsets(hidden: Boolean = binding.buttonHideShowUI.isChecked) {
        val toolbar = if (hidden) 0f else 48 * resources.displayMetrics.density
        stickerViewModel.frameInsetTop = toolbar
        stickerViewModel.frameInsetBottom = toolbar
    }

    private fun setUIVisibility(hidden: Boolean) {
        updateFrameInsets(hidden)
        val visibility = if (hidden) View.GONE else View.VISIBLE
        binding.toolbarTop.visibility = visibility
        binding.toolbarBottom.visibility = visibility
        binding.buttonMenu.visibility = visibility
        val icon = if (hidden) R.drawable.ic_baseline_visibility_off_24 else R.drawable.ic_baseline_visibility_24
        binding.buttonHideShowUI.setCompoundDrawablesWithIntrinsicBounds(
            null, ContextCompat.getDrawable(this, icon), null, null
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirm("Are you sure you want to quit?") { finish() }
    }

    // endregion

    // region BoardListener

    override fun onEditNote(note: NoteSticker) {
        Dialogs.note(this, note) { text, textColor, background ->
            stickerViewModel.change {
                note.text = text
                note.textColor = textColor
                note.backgroundColor = background
            }
        }
    }

    override fun onColorPicked(color: Int) {
        stickerViewModel.tool.value = Tool.SELECT
        val hex = BoardRenderer.hex(color)
        Dialogs.pickedColor(
            this, color,
            onCopy = {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Color", hex))
                Toast.makeText(this, "Copied $hex", Toast.LENGTH_SHORT).show()
            },
            onUseForPen = {
                stickerViewModel.penColor = color or 0xFF000000.toInt()
                savePen()
            },
            onUseForBackground = { stickerViewModel.backgroundColor.value = color or 0xFF000000.toInt() }
        )
    }

    override fun onMessage(message: String) = toast(message)

    // endregion

    internal class RedrawListener(private val view: StickerView) : StickerView.OnStickerOperationListener {
        override fun onStickerAdded(sticker: Sticker, direction: Int) = view.invalidate()
        override fun onStickerClicked(sticker: Sticker) = view.invalidate()
        override fun onStickerDeleted(sticker: Sticker) = view.invalidate()
        override fun onStickerDragFinished(sticker: Sticker) = view.invalidate()
        override fun onStickerTouchedDown(sticker: Sticker) = view.invalidate()
        override fun onStickerZoomFinished(sticker: Sticker) = view.invalidate()
        override fun onStickerFlipped(sticker: Sticker) = view.invalidate()
        override fun onStickerDoubleTapped(sticker: Sticker) = view.invalidate()
        override fun onStickerMoved(sticker: Sticker) = view.invalidate()
        override fun onInvalidateView() = view.invalidate()
    }

    companion object {
        const val PERM_RQST_CODE = 110
        const val SAVE_FILE_EXTENSION: String = "ref"

        private const val PREFS = "settings"
        private const val PREF_GRID = "grid"
        private const val PREF_SNAP = "snap"
        private const val PREF_GRAYSCALE = "grayscale"
        private const val PREF_BACKGROUND = "background"
        private const val PREF_AUTO_ARRANGE = "autoArrange"
        private const val PREF_PEN_COLOR = "penColor"
        private const val PREF_PEN_WIDTH = "penWidth"
        private const val PREF_CURRENT_FILE = "currentFile"
        private const val AUTOSAVE_FILE = "autosave.ref"
        private const val AUTOSAVE_DELAY_MS = 5_000L

        private val autosaveExecutor = Executors.newSingleThreadExecutor()
    }
}
