package xyz.ruin.droidref

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Patterns
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.Insets
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
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
import xyz.ruin.droidref.Dialogs.themeColor
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
        // Draw the board under the system bars (mandatory when targeting
        // Android 15+); the toolbars are inset below in applySystemBarInsets.
        setTheme(currentTheme().style)
        // System bar icons follow the theme (dark icons on Krita bright/neutral).
        val barStyle = if (themeBoolean(R.attr.droidrefLightTheme)) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        applySystemBarInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirm("Are you sure you want to quit?") { finish() }
            }
        })

        stickerViewModel = ViewModelProvider(this)[StickerViewModel::class.java]
        stickerViewModel.stickerOperationListener = RedrawListener(binding.stickerView)
        stickerViewModel.boardListener = this
        stickerViewModel.accentColor = themeColor(R.attr.droidrefPrimary)
        stickerViewModel.emptyHint =
            "Tap Add to bring in images\nLong-press any button to see what it does\nMore › Help lists the gestures"
        binding.viewModel = stickerViewModel
        binding.lifecycleOwner = this
        binding.executePendingBindings()

        io = BoardIO(this)
        BlobStore.init(File(filesDir, "blobs"))

        setupIcons()
        setupButtons()
        setupSettings()
        applyButtonLabels(prefs.getBoolean(PREF_LABELS, true))

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
        binding.buttonPaste.setOnClickListener { paste() }
        binding.buttonNote.setOnClickListener { addNote() }
        binding.buttonDraw.setOnClickListener {
            vm.tool.value = if (vm.tool.value == Tool.DRAW) Tool.SELECT else Tool.DRAW
            if (vm.tool.value == Tool.DRAW) {
                hintOnce(
                    "draw",
                    "Draw: one finger draws, two fingers move around. Pen settings: More › View."
                )
            }
        }
        binding.buttonPicker.setOnClickListener {
            vm.tool.value = if (vm.tool.value == Tool.PICK_COLOR) Tool.SELECT else Tool.PICK_COLOR
            if (vm.tool.value == Tool.PICK_COLOR) {
                hintOnce("picker", "Color picker: touch and drag over the board, then lift to pick.")
            }
        }
        binding.buttonArrange.setOnClickListener { vm.arrange(Arrangement.OPTIMAL) }
        binding.buttonReset.setOnClickListener { vm.fitAll() }
        binding.buttonSelect.setOnClickListener {
            vm.selectMode.value = vm.selectMode.value != true
            if (vm.selectMode.value == true) {
                hintOnce(
                    "select",
                    "Select: tap images to add or remove them, or drag to box-select."
                )
            }
        }
        binding.buttonDuplicate.setOnClickListener { withSelection { vm.duplicateSelection() } }
        binding.buttonDelete.setOnClickListener { withSelection { vm.deleteSelection() } }
        binding.buttonResetZoom.setOnClickListener { withSelection { vm.resetTransform() } }
        binding.buttonResetCrop.setOnClickListener { withSelection { vm.resetCrop() } }
        binding.buttonHideShowUI.setOnClickListener { setUIVisibility(!binding.buttonHideShowUI.isSelected) }
        binding.buttonLock.setOnClickListener {
            vm.isLocked.value = vm.isLocked.value != true
            if (vm.isLocked.value == true) {
                hintOnce("lock", "Board locked: only panning and zooming work. Tap Lock to unlock.")
            }
        }
        binding.buttonCrop.setOnClickListener {
            vm.isCropActive.value = vm.isCropActive.value != true
            if (vm.isCropActive.value == true) {
                hintOnce("crop", "Crop: drag the selected image's corner handles. Uncrop restores it.")
            }
        }
        binding.buttonRotate.setOnClickListener {
            vm.rotationEnabled.value = vm.rotationEnabled.value != true
            if (vm.rotationEnabled.value == true) {
                hintOnce("rotate", "Rotation on: twist with two fingers, or drag the corner handle.")
            }
        }

        binding.buttonLabels.setOnClickListener {
            val show = !prefs.getBoolean(PREF_LABELS, true)
            prefs.edit().putBoolean(PREF_LABELS, show).apply()
            applyButtonLabels(show)
        }
        TooltipCompat.setTooltipText(binding.buttonLabels, binding.buttonLabels.contentDescription)

        // Long-pressing any toolbar button explains it.
        for (bar in listOf(binding.toolbarTop, binding.toolbarBottom, binding.toolbarHideShowUI)) {
            toolbarButtons(bar).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
        }

        vm.revision.observe(this) { scheduleAutosave() }

        vm.tool.observe(this) { tool ->
            binding.buttonDraw.isSelected = tool == Tool.DRAW
            binding.buttonPicker.isSelected = tool == Tool.PICK_COLOR
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
        vm.backgroundColor.value = prefs.getInt(PREF_BACKGROUND, themeColor(R.attr.droidrefBoard))
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
        menu.findItem(currentTheme().menuId).isChecked = true
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
            R.id.action_help -> showHelp()
            in THEMES.map { it.menuId } -> applyTheme(THEMES.first { it.menuId == item.itemId })

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

    private fun toolbarButtons(bar: ViewGroup): List<TextView> =
        (bar.getChildAt(0) as ViewGroup).children.filterIsInstance<TextView>().toList()

    /** Shows or hides the text under the toolbar icons. */
    private val labelPadding by lazy { (6 * resources.displayMetrics.density).toInt() }

    private fun applyButtonLabels(show: Boolean) {
        val size = resources.getDimensionPixelSize(
            if (show) R.dimen.toolbar_button_labelled else R.dimen.toolbar_button_compact
        )
        for (bar in listOf(binding.toolbarTop, binding.toolbarBottom, binding.toolbarHideShowUI)) {
            for (button in toolbarButtons(bar)) {
                if (button.tag == null) button.tag = button.text
                button.text = if (show) button.tag as CharSequence else null
                // A drawableTop icon sits at the top padding (only the text is
                // centred), so without a label centre it with the padding.
                val icon = button.compoundDrawables[1]?.intrinsicHeight ?: 0
                val top = if (show) labelPadding else (size - icon) / 2
                button.setPadding(button.paddingLeft, top, button.paddingRight, if (show) labelPadding else 0)
                button.minWidth = resources.getDimensionPixelSize(
                    if (show) R.dimen.toolbar_button_width else R.dimen.toolbar_button_compact
                )
                button.updateLayoutParams { height = size }
            }
        }
        binding.buttonLabels.alpha = if (show) 0.8f else 0.4f
        positionLabelsButton()
        updateFrameInsets()
    }

    /** Keeps the ? button vertically centred on the bottom toolbar. */
    private fun positionLabelsButton() {
        val bar = resources.getDimensionPixelSize(
            if (prefs.getBoolean(PREF_LABELS, true)) R.dimen.toolbar_button_labelled else R.dimen.toolbar_button_compact
        )
        val size = binding.buttonLabels.layoutParams.height
        binding.buttonLabels.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = systemBars.bottom + (bar - size) / 2
            marginEnd = systemBars.right + (bar - size) / 4
        }
    }

    /** Explains a mode the first time it's used. */
    private fun hintOnce(key: String, message: String) {
        val pref = "hint_$key"
        if (prefs.getBoolean(pref, false)) return
        prefs.edit().putBoolean(pref, true).apply()
        toast(message)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Help")
            .setMessage(
                """
                Selecting
                • Tap an image to select it; tap it again to deselect it.
                • Long-press an image to add it to (or remove it from) the selection.
                • Long-press empty space, then drag, to select everything in a box.
                • Tap empty space to deselect everything.
                • Or turn on Select: then taps add and remove images, and dragging draws a selection box.

                Moving
                • Only selected images move: drag a selected image to move the selection, pinch on it to resize.
                • Dragging or pinching anywhere else, even on other images, moves around the board.
                • Double-tap an image to zoom to it; again to zoom back. Double-tap a note to edit it.
                • Tap with two fingers to undo, three fingers to redo.

                Toolbar
                • Long-press any button to see what it does; the ? in the corner shows or hides the labels.
                • Add picks several images at once; they are arranged automatically.
                • Arrange, Delete, Duplicate, Uncrop and Reset size act on the selection.
                • Select, Draw, Color, Crop, Rotate and Lock are switches: highlighted means on.

                More (⋮) has everything else: align, normalize, grid, export, slideshow and settings.
                """.trimIndent()
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private var systemBars = Insets.NONE

    /** Keeps the toolbars clear of the status bar, navigation bar and cutouts. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.toolbarTop.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
                marginStart = systemBars.left
            }
            binding.toolbarHideShowUI.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
                marginEnd = systemBars.right
            }
            binding.toolbarBottom.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
                marginStart = systemBars.left
            }
            positionLabelsButton()
            updateFrameInsets()
            insets
        }
    }

    private fun updateFrameInsets(hidden: Boolean = binding.buttonHideShowUI.isSelected) {
        val labels = prefs.getBoolean(PREF_LABELS, true)
        val toolbar = if (hidden) 0f else resources.getDimension(
            if (labels) R.dimen.toolbar_button_labelled else R.dimen.toolbar_button_compact
        )
        stickerViewModel.frameInsetTop = systemBars.top + toolbar
        stickerViewModel.frameInsetBottom = systemBars.bottom + toolbar
    }

    private fun setUIVisibility(hidden: Boolean) {
        updateFrameInsets(hidden)
        val visibility = if (hidden) View.GONE else View.VISIBLE
        binding.toolbarTop.visibility = visibility
        binding.toolbarBottom.visibility = visibility
        binding.buttonLabels.visibility = visibility
        binding.buttonMenu.visibility = visibility
        val icon = if (hidden) R.drawable.ic_baseline_visibility_off_24 else R.drawable.ic_baseline_visibility_24
        binding.buttonHideShowUI.isSelected = hidden
        binding.buttonHideShowUI.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
        binding.buttonHideShowUI.tag = if (hidden) "Show" else "Hide"
        if (binding.buttonHideShowUI.text.isNotEmpty()) binding.buttonHideShowUI.text = binding.buttonHideShowUI.tag as String
        if (hidden) hintOnce("hide", "Tap Show to bring the toolbars back.")
    }


    // endregion

    // region Theme

    /** A Krita colour scheme, as an Android theme. */
    class KritaTheme(val key: String, val style: Int, val menuId: Int)

    private fun currentTheme(): KritaTheme {
        val key = prefs.getString(PREF_THEME, null)
        return THEMES.firstOrNull { it.key == key } ?: THEMES[0]
    }

    private fun themeBoolean(attr: Int): Boolean {
        val value = TypedValue()
        return theme.resolveAttribute(attr, value, true) && value.data != 0
    }

    private fun applyTheme(chosen: KritaTheme) {
        if (chosen == currentTheme()) return
        // The board background follows the theme; it can still be changed afterwards.
        val board = obtainStyledAttributes(chosen.style, intArrayOf(R.attr.droidrefBoard)).use {
            it.getColor(0, StickerViewModel.DEFAULT_BACKGROUND)
        }
        prefs.edit()
            .putString(PREF_THEME, chosen.key)
            .putInt(PREF_BACKGROUND, board)
            .commit()
        stickerViewModel.backgroundColor.value = board
        recreate()
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

    override fun onHistoryGesture(redo: Boolean, done: Boolean) {
        val message = when {
            done && redo -> "Redo"
            done -> "Undo"
            redo -> "Nothing to redo"
            else -> "Nothing to undo"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

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
        private const val PREF_LABELS = "buttonLabels"
        private const val PREF_THEME = "theme"

        private val THEMES = listOf(
            KritaTheme("krita_dark", R.style.Theme_DroidRef_KritaDark, R.id.theme_krita_dark),
            KritaTheme("krita_darker", R.style.Theme_DroidRef_KritaDarker, R.id.theme_krita_darker),
            KritaTheme("krita_bright", R.style.Theme_DroidRef_KritaBright, R.id.theme_krita_bright),
            KritaTheme("krita_neutral", R.style.Theme_DroidRef_KritaNeutral, R.id.theme_krita_neutral),
            KritaTheme("krita_blender", R.style.Theme_DroidRef_KritaBlender, R.id.theme_krita_blender),
            KritaTheme("krita_dark_orange", R.style.Theme_DroidRef_KritaDarkOrange, R.id.theme_krita_dark_orange),
        )
        private const val AUTOSAVE_FILE = "autosave.ref"
        private const val AUTOSAVE_DELAY_MS = 5_000L

        private val autosaveExecutor = Executors.newSingleThreadExecutor()
    }
}
