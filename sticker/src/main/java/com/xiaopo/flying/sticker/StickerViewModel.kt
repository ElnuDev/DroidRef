package com.xiaopo.flying.sticker

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.annotation.IntDef
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.xiaopo.flying.sticker.StickerView.Flip
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

open class StickerViewModel :
    ViewModel() {
    /** Canvas lock: everything is frozen and touches only pan and zoom. */
    var isLocked: MutableLiveData<Boolean> = MutableLiveData(false)
    var mustLockToPan: MutableLiveData<Boolean> = MutableLiveData(false)
    var isCropActive: MutableLiveData<Boolean> = MutableLiveData(false)
    var rotationEnabled: MutableLiveData<Boolean> = MutableLiveData(false)
    var constrained: MutableLiveData<Boolean> = MutableLiveData(false)
    var bringToFrontCurrentSticker = MutableLiveData(true)

    var canvasMatrix: CustomMutableLiveData<ObservableMatrix> = CustomMutableLiveData(ObservableMatrix())

    var stickers: MutableLiveData<ArrayList<Sticker>> = MutableLiveData(ArrayList())
    var icons: MutableLiveData<ArrayList<BitmapStickerIcon>> = MutableLiveData(ArrayList())
    var activeIcons: MutableLiveData<List<BitmapStickerIcon>> = MutableLiveData(ArrayList(4))

    /** The selected item when exactly one is selected; it gets the corner handles. */
    var handlingSticker: MutableLiveData<Sticker?> = MutableLiveData(null)

    var gestureListener: MutableLiveData<GestureListener> = MutableLiveData()

    init {
        gestureListener.value = GestureListener(this)
    }

    enum class Tool { SELECT, DRAW, PICK_COLOR }
    enum class GridMode { NONE, LINES, DOTS }
    enum class Arrangement { OPTIMAL, NAME, ORDER, RANDOM }
    enum class Alignment { LEFT, RIGHT, TOP, BOTTOM, ROW, COLUMN, STACK }
    enum class Normalization { HEIGHT, WIDTH, SCALE, SIZE, AREA }

    val tool = MutableLiveData(Tool.SELECT)
    val gridMode = MutableLiveData(GridMode.NONE)
    val snapToGrid = MutableLiveData(false)
    val canvasGrayscale = MutableLiveData(false)
    val backgroundColor = MutableLiveData(DEFAULT_BACKGROUND)

    /** Arrange a batch of newly added images instead of piling them up. */
    val autoArrange = MutableLiveData(true)
    var penColor: Int = Color.WHITE

    /** Pen width in screen pixels at the time of drawing. */
    var penWidth: Float = 8f

    /** Selected items, in the order they were selected. */
    val selection = LinkedHashSet<Sticker>()
    val selectionCount = MutableLiveData(0)

    val history = History()
    val canUndo = MutableLiveData(false)
    val canRedo = MutableLiveData(false)

    /** Bumped whenever the board changes, so the activity knows to autosave. */
    val revision = MutableLiveData(0L)

    init {
        history.onChanged = {
            canUndo.value = history.canUndo
            canRedo.value = history.canRedo
            revision.value = revision.value!! + 1
        }
    }

    /** Callbacks that need the activity (dialogs etc.). */
    interface BoardListener {
        fun onEditNote(note: NoteSticker)
        fun onColorPicked(color: Int)
        fun onMessage(message: String)

        /** A two- or three-finger tap undid or redid something ([done] false if there was nothing to). */
        fun onHistoryGesture(redo: Boolean, done: Boolean)
    }

    var boardListener: BoardListener? = null

    /** Location of the open board (a document URI), if it has been saved. */
    var currentFileName: String? = null

    /** Set once the activity has restored the previous session into this model. */
    var sessionStarted = false

    lateinit var stickerOperationListener: StickerView.OnStickerOperationListener

    /** Shown in the middle of an empty board. */
    var emptyHint: String? = null

    /** Screen space covered by toolbars, kept clear when framing items. */
    var frameInsetTop = 0f
    var frameInsetBottom = 0f

    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    private val pendingPlacement = ArrayList<Sticker>()
    private var pendingArrange = false

    // Overlay state drawn by StickerView.
    /** Rubber-band selection rectangle, in screen coordinates. */
    var marquee: RectF? = null
        private set

    /** World-space points of the stroke being drawn. */
    var strokePoints: FloatArray? = null
        private set
    private var strokeSize = 0

    /** Where the color picker is sampling, in screen coordinates, and what it found. */
    var pickPoint: PointF? = null
        private set
    var pickedColor: Int = Color.TRANSPARENT
        private set

    private val stickerWorldMatrix = Matrix()
    private val moveMatrix = Matrix()
    private val point = FloatArray(2)
    private val tmp = FloatArray(2)
    private var midPoint = PointF()

    //the first point down position
    private var downX = 0f
    private var downY = 0f
    private var downXScaled = 0f
    private var downYScaled = 0f

    private var oldDistance = 0f
    private var oldRotation = 0f

    @IntDef(
        ActionMode.NONE,
        ActionMode.DRAG,
        ActionMode.ZOOM_WITH_TWO_FINGER,
        ActionMode.ICON,
        ActionMode.CLICK,
        ActionMode.CANVAS_DRAG,
        ActionMode.CANVAS_ZOOM_WITH_TWO_FINGER
    )
    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    annotation class ActionMode {
        companion object {
            const val NONE = 0
            const val DRAG = 1
            const val ZOOM_WITH_TWO_FINGER = 2
            const val ICON = 3
            const val CLICK = 4
            const val CANVAS_DRAG = 5
            const val CANVAS_ZOOM_WITH_TWO_FINGER = 6
        }
    }

    var currentMode = MutableLiveData(ActionMode.NONE)

    var currentIcon: MutableLiveData<BitmapStickerIcon?> = MutableLiveData(null)

    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { v, event -> onTouchEvent(v as StickerView, event) }

    private val items get() = stickers.value!!

    private fun invalidate() {
        if (this::stickerOperationListener.isInitialized) {
            stickerOperationListener.onInvalidateView()
        }
    }

    // region Coordinates

    private val inverse = Matrix()

    fun screenToWorld(x: Float, y: Float): PointF {
        canvasMatrix.value!!.invert(inverse)
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    private fun screenToWorldVector(dx: Float, dy: Float): PointF {
        canvasMatrix.value!!.invert(inverse)
        val vec = floatArrayOf(dx, dy)
        inverse.mapVectors(vec)
        return PointF(vec[0], vec[1])
    }

    /** Screen pixels per world unit. */
    fun canvasScale(): Float = canvasMatrix.value!!.getMatrix().mapRadius(1f)

    /** The part of the world currently on screen. */
    fun visibleWorld(): RectF {
        canvasMatrix.value!!.invert(inverse)
        val r = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        inverse.mapRect(r)
        return r
    }

    fun onViewSizeChanged(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        if (width > 0 && height > 0 && pendingPlacement.isNotEmpty()) {
            val pending = ArrayList(pendingPlacement)
            pendingPlacement.clear()
            place(pending, pendingArrange)
            invalidate()
        }
    }

    // endregion

    // region Adding and removing

    fun addSticker(sticker: Sticker) {
        addStickers(listOf(sticker))
    }

    fun addSticker(sticker: Sticker, @Suppress("UNUSED_PARAMETER") position: Int) {
        addSticker(sticker)
    }

    /**
     * Adds items to the board around the middle of the screen, arranging a batch
     * so that dozens of images don't end up in a pile.
     */
    fun addStickers(added: List<Sticker>, arrange: Boolean = autoArrange.value == true) {
        if (added.isEmpty()) return
        history.record(items) {
            added.forEach {
                it.setCanvasMatrix(canvasMatrix.value!!.getMatrix())
                items.add(it)
            }
            if (viewWidth == 0 || viewHeight == 0) {
                pendingPlacement.addAll(added)
                pendingArrange = arrange
            } else {
                place(added, arrange)
            }
        }
        setSelection(added)
    }

    private fun place(added: List<Sticker>, arrange: Boolean) {
        val center = screenToWorld(viewWidth / 2f, viewHeight / 2f)
        if (arrange && added.size > 1) {
            val bounds = added.map { it.worldBounds }
            val gap = gapFor(bounds)
            val boxes = bounds.map { Arranger.Box(it.width(), it.height()) }
            val positions = Arranger.optimal(boxes, gap, viewAspect())
            val extent = Arranger.extent(boxes, positions)
            var originX = center.x - extent.w / 2
            var originY = center.y - extent.h / 2
            // Don't bury what's already on the board: if the block would cover
            // existing items, put it to their right instead.
            val existing = items.filter { it !in added }
            if (existing.isNotEmpty()) {
                val block = RectF(originX, originY, originX + extent.w, originY + extent.h)
                if (existing.any { it.intersectsWorld(block) }) {
                    val occupied = unionBounds(existing)
                    originX = occupied.right + gap * 4
                    originY = occupied.top
                }
            }
            added.forEachIndexed { i, sticker ->
                sticker.matrix.postTranslate(
                    originX + positions[i].x - bounds[i].left,
                    originY + positions[i].y - bounds[i].top
                )
                sticker.recalcFinalMatrix()
            }
        } else {
            val step = min(viewWidth, viewHeight) * 0.04f / canvasScale()
            added.forEachIndexed { i, sticker ->
                val b = sticker.worldBounds
                val offset = (i - (added.size - 1) / 2f) * step
                sticker.matrix.postTranslate(
                    center.x - b.centerX() + offset,
                    center.y - b.centerY() + offset
                )
                sticker.recalcFinalMatrix()
            }
        }
        val union = unionBounds(added)
        if (!visibleWorld().contains(union)) {
            fitTo(added)
        }
    }

    fun addNote(note: NoteSticker) {
        // Readable at the current zoom, like the text size on screen.
        val scale = 1f / canvasScale()
        note.matrix.setScale(scale, scale)
        addStickers(listOf(note), arrange = false)
    }

    fun resetView() {
        animateCanvasTo(Matrix())
    }

    fun updateCanvasMatrix() {
        val m = canvasMatrix.value!!.getMatrix()
        for (sticker in items) {
            sticker.setCanvasMatrix(m)
        }
        invalidate()
    }

    fun removeCurrentSticker(): Boolean {
        val sticker = handlingSticker.value ?: return false
        return removeSticker(sticker)
    }

    fun removeSticker(sticker: Sticker): Boolean {
        if (!items.contains(sticker)) {
            return false
        }
        history.record(items) {
            items.remove(sticker)
        }
        selection.remove(sticker)
        onSelectionChanged()
        stickerOperationListener.onStickerDeleted(sticker)
        return true
    }

    fun removeAllStickers() {
        items.clear()
        selection.clear()
        onSelectionChanged()
        currentIcon.value = null
        history.clear()
        invalidate()
    }

    /** Replaces the board, e.g. after loading a file. */
    fun loadBoard(board: StickerViewSerializer.Board) {
        removeAllStickers()
        items.addAll(board.stickers)
        canvasMatrix.value!!.setMatrix(board.canvasMatrix)
        canvasMatrix.value!!.notifyChange()
        updateCanvasMatrix()
    }

    // endregion

    // region Selection

    private fun groupOf(sticker: Sticker): List<Sticker> =
        if (sticker.groupId == 0L) listOf(sticker) else items.filter { it.groupId == sticker.groupId }

    fun setSelection(selected: Collection<Sticker>) {
        selection.clear()
        selected.forEach { sticker -> groupOf(sticker).forEach { selection.add(it) } }
        onSelectionChanged()
    }

    fun selectAll() = setSelection(items)

    fun clearSelection() = setSelection(emptyList())

    private fun onSelectionChanged() {
        selection.retainAll(items.toSet())
        val single = selection.singleOrNull()
        if (handlingSticker.value !== single) {
            handlingSticker.value = single
        }
        if (selectionCount.value != selection.size) {
            selectionCount.value = selection.size
        }
        invalidate()
    }

    /** Selected items in stacking order, or everything if nothing is selected. */
    fun targets(): List<Sticker> =
        if (selection.isEmpty()) ArrayList(items) else items.filter { it in selection }

    fun selected(): List<Sticker> = items.filter { it in selection }

    // endregion

    // region Undo

    fun undo() = restoreHistory { history.undo(items) }

    fun redo() = restoreHistory { history.redo(items) }

    private fun restoreHistory(step: () -> Boolean): Boolean {
        val selectedIds = selection.map { it.id }.toSet()
        if (!step()) return false
        updateCanvasMatrix()
        setSelection(items.filter { it.id in selectedIds })
        return true
    }

    /** Runs a board change as a single undo step and redraws. */
    fun <T> change(block: () -> T): T {
        val result = history.record(items, block)
        items.forEach { it.recalcFinalMatrix() }
        onSelectionChanged()
        return result
    }

    // endregion

    // region Touch handling

    private enum class Gesture {
        NONE, ICON, PRESS_ITEM, MOVE_ITEMS, PINCH_ITEMS, PRESS_CANVAS, PAN, PINCH_CANVAS, MARQUEE, DRAW, PICK
    }

    private var gesture = Gesture.NONE
    private var activePointerId = -1
    private var pressed: Sticker? = null
    private var selectionBeforePress: Set<Sticker> = emptySet()
    private val startMatrices = HashMap<Sticker, Matrix>()
    private val startCanvas = Matrix()
    private var pinchStartDistance = 1f
    private var pinchStartAngle = 0f
    private val pinchStartMid = PointF()
    private var longPressFired = false
    private var longPressView: View? = null
    private var longPressEvent: MotionEvent? = null
    private var lastTapTime = 0L
    private var lastTapSticker: Sticker? = null
    private var moveAnchor: RectF? = null

    private val longPressRunnable = Runnable { onLongPress() }

    // Multi-finger tap detection (two fingers: undo, three: redo).
    private var gestureStartTime = 0L
    private var maxPointers = 0
    private var fingersMoved = false
    private val pointerStarts = HashMap<Int, PointF>()

    private fun trackFingers(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartTime = event.eventTime
                maxPointers = 1
                fingersMoved = false
                pointerStarts.clear()
                pointerStarts[event.getPointerId(0)] = PointF(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointerStarts[event.getPointerId(i)] = PointF(event.getX(i), event.getY(i))
                maxPointers = max(maxPointers, event.pointerCount)
            }
            MotionEvent.ACTION_MOVE -> if (!fingersMoved) {
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                for (i in 0 until event.pointerCount) {
                    val start = pointerStarts[event.getPointerId(i)] ?: continue
                    if (hypot(event.getX(i) - start.x, event.getY(i) - start.y) > slop) {
                        fingersMoved = true
                    }
                }
            }
        }
    }

    /** Whether the gesture that just ended was a quick tap with several fingers. */
    private fun isMultiFingerTap(event: MotionEvent) =
        maxPointers >= 2 && !fingersMoved && event.eventTime - gestureStartTime < MULTI_TAP_TIMEOUT

    @SuppressLint("ClickableViewAccessibility")
    fun onTouchEvent(view: StickerView, event: MotionEvent): Boolean {
        cancelCameraAnimation()
        trackFingers(view, event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(view, event)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onMove(view, event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp(view, event)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        invalidate()
        return true
    }

    private fun onDown(view: StickerView, event: MotionEvent) {
        cancelLongPress()
        downX = event.x
        downY = event.y
        longPressFired = false
        activePointerId = event.getPointerId(0)
        calculateDown(event)
        startCanvas.set(canvasMatrix.value!!.getMatrix())

        if (tool.value == Tool.PICK_COLOR) {
            gesture = Gesture.PICK
            view.beginColorPick()
            pick(view, event.x, event.y)
            return
        }
        if (isLocked.value == true) {
            gesture = Gesture.PRESS_CANVAS
            return
        }
        if (tool.value == Tool.DRAW) {
            gesture = Gesture.DRAW
            strokePoints = FloatArray(64)
            strokeSize = 0
            addStrokePoint(event.x, event.y)
            return
        }

        val single = handlingSticker.value
        if (single != null && !single.isLocked && (isCropActive.value != true || single.isCroppable)) {
            val icon = findCurrentIconTouched()
            if (icon != null) {
                gesture = Gesture.ICON
                currentIcon.value = icon
                currentMode.value = ActionMode.ICON
                stickerWorldMatrix.set(single.matrix)
                midPoint = StickerMath.calculateMidPoint(single)
                oldRotation = StickerMath.calculateRotation(midPoint.x, midPoint.y, downXScaled, downYScaled)
                history.begin(items)
                icon.onActionDown(view, this, event)
                scheduleLongPress(view, event)
                return
            }
        }

        // Only selected items can be dragged or pinched; a gesture starting
        // anywhere else (even on an unselected image) moves the board. That
        // keeps the board navigable when images cover it wall to wall.
        selectionBeforePress = LinkedHashSet(selection)
        val hitSelected = findSelectedSticker()
        if (hitSelected != null) {
            gesture = Gesture.PRESS_ITEM
            pressed = hitSelected
            stickerOperationListener.onStickerTouchedDown(hitSelected)
        } else {
            gesture = Gesture.PRESS_CANVAS
            pressed = findHandlingSticker()
        }
        scheduleLongPress(view, event)
    }

    private fun bringToFront(sticker: Sticker) {
        if (bringToFrontCurrentSticker.value != true) return
        val group = groupOf(sticker).toSet()
        val front = items.filter { it in group }
        items.removeAll(group)
        items.addAll(front)
    }

    private fun onPointerDown(event: MotionEvent) {
        cancelLongPress()
        when (gesture) {
            Gesture.PRESS_ITEM, Gesture.MOVE_ITEMS, Gesture.PINCH_ITEMS -> {
                if (movable().isNotEmpty() && pressed?.isLocked != true) {
                    history.begin(items)
                    gesture = Gesture.PINCH_ITEMS
                    startPinch(event)
                } else {
                    gesture = Gesture.PINCH_CANVAS
                    startPinch(event)
                }
            }
            Gesture.DRAW -> {
                // A second finger turns drawing into navigation.
                strokePoints = null
                gesture = Gesture.PINCH_CANVAS
                startPinch(event)
            }
            Gesture.MARQUEE -> {
                marquee = null
                gesture = Gesture.PINCH_CANVAS
                startPinch(event)
            }
            Gesture.PRESS_CANVAS, Gesture.PAN, Gesture.PINCH_CANVAS, Gesture.NONE -> {
                gesture = Gesture.PINCH_CANVAS
                startPinch(event)
            }
            Gesture.ICON, Gesture.PICK -> {}
        }
    }

    private fun movable(): List<Sticker> = selected().filter { !it.isLocked }

    private fun startPinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        pinchStartDistance = max(1f, hypot(x1 - x0, y1 - y0))
        pinchStartAngle = StickerMath.calculateRotation(x0, y0, x1, y1)
        pinchStartMid.set((x0 + x1) / 2, (y0 + y1) / 2)
        startCanvas.set(canvasMatrix.value!!.getMatrix())
        saveStartMatrices()
    }

    private fun saveStartMatrices() {
        startMatrices.clear()
        movable().forEach { startMatrices[it] = Matrix(it.matrix) }
    }

    private fun onMove(view: StickerView, event: MotionEvent) {
        val index = max(0, event.findPointerIndex(activePointerId))
        val x = event.getX(index)
        val y = event.getY(index)
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        val beyondSlop = abs(x - downX) > slop || abs(y - downY) > slop

        when (gesture) {
            Gesture.DRAW -> {
                for (h in 0 until event.historySize) {
                    addStrokePoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                }
                addStrokePoint(x, y)
            }
            Gesture.PICK -> pick(view, x, y)
            Gesture.ICON -> {
                if (beyondSlop) cancelLongPress()
                currentIcon.value?.onActionMove(view, this, event)
            }
            Gesture.PRESS_ITEM -> if (beyondSlop) {
                cancelLongPress()
                if (movable().isEmpty() || pressed?.isLocked == true || pressed !in selection) {
                    // Locked items stay put; dragging them moves the canvas instead.
                    gesture = Gesture.PAN
                } else {
                    history.begin(items)
                    saveStartMatrices()
                    moveAnchor = pressed?.worldBounds
                    gesture = Gesture.MOVE_ITEMS
                }
                onMove(view, event)
            }
            Gesture.MOVE_ITEMS -> moveSelection(x - downX, y - downY)
            Gesture.PRESS_CANVAS -> if (beyondSlop) {
                cancelLongPress()
                if (mustLockToPan.value == true && isLocked.value != true) {
                    gesture = Gesture.NONE
                } else {
                    gesture = Gesture.PAN
                    onMove(view, event)
                }
            }
            Gesture.PAN -> {
                moveMatrix.set(startCanvas)
                moveMatrix.postTranslate(x - downX, y - downY)
                setCanvas(moveMatrix)
            }
            Gesture.MARQUEE -> {
                val rect = RectF(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
                marquee = rect
                canvasMatrix.value!!.invert(inverse)
                val world = RectF(rect)
                inverse.mapRect(world)
                val hits = items.filter { !it.isLocked && it.intersectsWorld(world) }
                setSelection(selectionBeforePress + hits)
            }
            // Pinches only start once the fingers really move, so a
            // two-finger tap (undo) never nudges anything.
            Gesture.PINCH_CANVAS -> if (event.pointerCount >= 2 && fingersMoved) {
                val (scale, _, mid) = pinch(event)
                moveMatrix.set(startCanvas)
                moveMatrix.postTranslate(mid.x - pinchStartMid.x, mid.y - pinchStartMid.y)
                val current = startCanvas.mapRadius(1f)
                val clamped = (current * scale).coerceIn(MIN_ZOOM, MAX_ZOOM) / current
                moveMatrix.postScale(clamped, clamped, mid.x, mid.y)
                setCanvas(moveMatrix)
            }
            Gesture.PINCH_ITEMS -> if (event.pointerCount >= 2 && fingersMoved) {
                val (scale, angle, mid) = pinch(event)
                val pivot = screenToWorld(pinchStartMid.x, pinchStartMid.y)
                val shift = screenToWorldVector(mid.x - pinchStartMid.x, mid.y - pinchStartMid.y)
                for ((sticker, start) in startMatrices) {
                    moveMatrix.set(start)
                    moveMatrix.postScale(scale, scale, pivot.x, pivot.y)
                    if (rotationEnabled.value == true) {
                        moveMatrix.postRotate(angle, pivot.x, pivot.y)
                    }
                    moveMatrix.postTranslate(shift.x, shift.y)
                    sticker.setMatrix(moveMatrix)
                }
            }
            Gesture.NONE -> {}
        }
    }

    private data class Pinch(val scale: Float, val angle: Float, val mid: PointF)

    private fun pinch(event: MotionEvent): Pinch {
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        val distance = max(1f, hypot(x1 - x0, y1 - y0))
        val angle = StickerMath.calculateRotation(x0, y0, x1, y1) - pinchStartAngle
        return Pinch(distance / pinchStartDistance, angle, PointF((x0 + x1) / 2, (y0 + y1) / 2))
    }

    private fun moveSelection(screenDx: Float, screenDy: Float) {
        var delta = screenToWorldVector(screenDx, screenDy)
        val anchor = moveAnchor
        if (snapToGrid.value == true && anchor != null) {
            val left = snap(anchor.left + delta.x)
            val top = snap(anchor.top + delta.y)
            delta = PointF(left - anchor.left, top - anchor.top)
        }
        for ((sticker, start) in startMatrices) {
            moveMatrix.set(start)
            moveMatrix.postTranslate(delta.x, delta.y)
            sticker.setMatrix(moveMatrix)
            stickerOperationListener.onStickerMoved(sticker)
        }
    }

    private fun snap(value: Float) = (value / GRID_SIZE).roundToInt() * GRID_SIZE

    private fun onPointerUp(event: MotionEvent) {
        val lifted = event.actionIndex
        if (event.pointerCount > 2) {
            // Keep pinching with the fingers that are left.
            if (gesture == Gesture.PINCH_CANVAS || gesture == Gesture.PINCH_ITEMS) {
                val remaining = (0 until event.pointerCount).filter { it != lifted }
                val x0 = event.getX(remaining[0])
                val y0 = event.getY(remaining[0])
                val x1 = event.getX(remaining[1])
                val y1 = event.getY(remaining[1])
                pinchStartDistance = max(1f, hypot(x1 - x0, y1 - y0))
                pinchStartAngle = StickerMath.calculateRotation(x0, y0, x1, y1)
                pinchStartMid.set((x0 + x1) / 2, (y0 + y1) / 2)
                startCanvas.set(canvasMatrix.value!!.getMatrix())
                saveStartMatrices()
            }
            return
        }
        val remaining = if (lifted == 0) 1 else 0
        activePointerId = event.getPointerId(remaining)
        downX = event.getX(remaining)
        downY = event.getY(remaining)
        startCanvas.set(canvasMatrix.value!!.getMatrix())
        when (gesture) {
            Gesture.PINCH_CANVAS -> gesture = Gesture.PAN
            Gesture.PINCH_ITEMS -> {
                saveStartMatrices()
                moveAnchor = pressed?.worldBounds
                gesture = Gesture.MOVE_ITEMS
            }
            else -> {}
        }
    }

    private fun onUp(view: StickerView, event: MotionEvent) {
        cancelLongPress()
        if (isMultiFingerTap(event)) {
            history.commit(items)
            resetGesture()
            val redo = maxPointers >= 3
            val done = if (redo) redo() else undo()
            boardListener?.onHistoryGesture(redo, done)
            return
        }
        when (gesture) {
            Gesture.DRAW -> finishStroke()
            Gesture.PICK -> {
                val color = pickedColor
                pickPoint = null
                view.endColorPick()
                boardListener?.onColorPicked(color)
            }
            Gesture.ICON -> {
                if (!longPressFired) {
                    currentIcon.value?.onActionUp(view, this, event)
                }
                history.commit(items)
                items.forEach { it.recalcFinalMatrix() }
            }
            Gesture.PRESS_ITEM -> if (!longPressFired) onTapSelected(pressed!!)
            Gesture.MOVE_ITEMS, Gesture.PINCH_ITEMS -> {
                history.commit(items)
                pressed?.let { stickerOperationListener.onStickerDragFinished(it) }
            }
            Gesture.PRESS_CANVAS -> if (!longPressFired && isLocked.value != true) {
                val item = pressed
                if (item == null) clearSelection() else onTapUnselected(item)
            }
            Gesture.MARQUEE -> marquee = null
            Gesture.PAN, Gesture.PINCH_CANVAS, Gesture.NONE -> {}
        }
        resetGesture()
    }

    private fun resetGesture() {
        gesture = Gesture.NONE
        marquee = null
        currentMode.value = ActionMode.NONE
        currentIcon.value = null
        pressed = null
        moveAnchor = null
        startMatrices.clear()
        onSelectionChanged()
    }

    private fun onCancel() {
        cancelLongPress()
        history.commit(items)
        gesture = Gesture.NONE
        marquee = null
        strokePoints = null
        pickPoint = null
        currentMode.value = ActionMode.NONE
        currentIcon.value = null
    }

    /** Tapping an unselected item selects it (alone). */
    private fun onTapUnselected(sticker: Sticker) {
        setSelection(listOf(sticker))
        bringToFront(sticker)
        stickerOperationListener.onStickerClicked(sticker)
        rememberTap(sticker)
    }

    /** Tapping a selected item deselects it, unless it's the second tap of a double tap. */
    private fun onTapSelected(sticker: Sticker) {
        if (isDoubleTap(sticker)) {
            stickerOperationListener.onStickerDoubleTapped(sticker)
            if (sticker is NoteSticker && !sticker.isLocked) {
                boardListener?.onEditNote(sticker)
            } else {
                focus(listOf(sticker))
            }
            return
        }
        val remaining = LinkedHashSet(selection)
        remaining.removeAll(groupOf(sticker).toSet())
        setSelection(remaining)
        stickerOperationListener.onStickerClicked(sticker)
        rememberTap(sticker)
    }

    private fun rememberTap(sticker: Sticker) {
        lastTapSticker = sticker
        lastTapTime = SystemClock.uptimeMillis()
    }

    private fun isDoubleTap(sticker: Sticker): Boolean {
        val double = lastTapSticker === sticker &&
                SystemClock.uptimeMillis() - lastTapTime < ViewConfiguration.getDoubleTapTimeout()
        if (double) lastTapSticker = null
        return double
    }

    private fun scheduleLongPress(view: View, event: MotionEvent) {
        longPressView = view
        longPressEvent?.recycle()
        longPressEvent = MotionEvent.obtain(event)
        view.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress() {
        longPressView?.removeCallbacks(longPressRunnable)
    }

    private fun onLongPress() {
        val view = longPressView ?: return
        longPressFired = true
        when (gesture) {
            Gesture.ICON -> {
                val event = longPressEvent ?: return
                currentIcon.value?.onActionLongPress(view as StickerView, this, event)
            }
            Gesture.PRESS_ITEM -> {
                // Long press adds an item to (or removes it from) the selection.
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val sticker = pressed ?: return
                val group = groupOf(sticker)
                val updated = LinkedHashSet(selectionBeforePress)
                if (sticker in selectionBeforePress) {
                    updated.removeAll(group.toSet())
                } else {
                    updated.addAll(group)
                }
                setSelection(updated)
                selectionBeforePress = LinkedHashSet(selection)
            }
            Gesture.PRESS_CANVAS -> if (isLocked.value != true && pressed != null) {
                // Long press on an unselected item adds it to the selection;
                // keep holding and drag to move the selection straight away.
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val sticker = pressed!!
                setSelection(selection + groupOf(sticker))
                bringToFront(sticker)
                gesture = Gesture.PRESS_ITEM
            } else if (isLocked.value != true) {
                // Long press on empty canvas starts a rubber-band selection.
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                selectionBeforePress = LinkedHashSet(selection)
                marquee = RectF(downX, downY, downX, downY)
                gesture = Gesture.MARQUEE
            }
            else -> {}
        }
        invalidate()
    }

    private fun addStrokePoint(screenX: Float, screenY: Float) {
        val world = screenToWorld(screenX, screenY)
        var points = strokePoints ?: return
        if (strokeSize >= 2) {
            // Skip points closer than a pixel on screen.
            val lastX = points[strokeSize - 2]
            val lastY = points[strokeSize - 1]
            if (hypot(world.x - lastX, world.y - lastY) * canvasScale() < 1.5f) return
        }
        if (strokeSize + 2 > points.size) {
            points = points.copyOf(points.size * 2)
            strokePoints = points
        }
        points[strokeSize++] = world.x
        points[strokeSize++] = world.y
    }

    /** The stroke being drawn, trimmed to its points. */
    fun currentStroke(): FloatArray? = strokePoints?.copyOf(strokeSize)

    private fun finishStroke() {
        val points = currentStroke()
        strokePoints = null
        if (points == null || points.isEmpty()) return
        val drawing = DrawingSticker.fromWorldPoints(penColor, penWidth / canvasScale(), points)
        drawing.setCanvasMatrix(canvasMatrix.value!!.getMatrix())
        history.record(items) {
            items.add(drawing)
        }
        invalidate()
    }

    private fun pick(view: StickerView, x: Float, y: Float) {
        pickPoint = PointF(x, y)
        pickedColor = view.sampleColor(x, y)
    }

    private fun setCanvas(matrix: Matrix) {
        canvasMatrix.value!!.setMatrix(matrix)
        updateCanvasMatrix()
    }

    // endregion

    // region Icon handles (single selection)

    fun resetCurrentStickerCropping() = resetCrop()

    fun resetCurrentStickerZoom() = resetTransform()

    fun resetCurrentStickerRotation() {
        change {
            movable().forEach { sticker ->
                val b = sticker.worldBounds
                val rotation = if (sticker.isFlippedVertically) sticker.currentAngle else -sticker.currentAngle
                sticker.matrix.postRotate(rotation, b.centerX(), b.centerY())
            }
        }
    }

    fun zoomAndRotateCurrentSticker(event: MotionEvent) {
        zoomAndRotateSticker(handlingSticker.value, event)
    }

    fun zoomAndRotateSticker(sticker: Sticker?, event: MotionEvent) {
        if (sticker == null) return
        val temp = floatArrayOf(event.x, event.y)
        canvasMatrix.value!!.invert(inverse)
        inverse.mapPoints(temp)
        val temp2 = floatArrayOf(sticker.centerPointCropped.x, sticker.centerPointCropped.y)
        stickerWorldMatrix.mapPoints(temp2)
        midPoint.x = temp2[0]
        midPoint.y = temp2[1]
        val oldDistance = StickerMath.calculateDistance(midPoint.x, midPoint.y, downXScaled, downYScaled)
        val newDistance = StickerMath.calculateDistance(midPoint.x, midPoint.y, temp[0], temp[1])
        val newRotation = StickerMath.calculateRotation(midPoint.x, midPoint.y, temp[0], temp[1])
        moveMatrix.set(stickerWorldMatrix)
        moveMatrix.postScale(newDistance / oldDistance, newDistance / oldDistance, midPoint.x, midPoint.y)
        if (rotationEnabled.value == true) {
            moveMatrix.postRotate(newRotation - oldRotation, midPoint.x, midPoint.y)
        }
        sticker.setMatrix(moveMatrix)
    }

    fun cropCurrentSticker(event: MotionEvent, gravity: Int) {
        cropSticker(handlingSticker.value, event, gravity)
    }

    private fun convertFlippedGravity(sticker: Sticker, gravity: Int): Int {
        var result = gravity
        if (sticker.isFlippedHorizontally) {
            result = when (result) {
                BitmapStickerIcon.LEFT_TOP -> BitmapStickerIcon.RIGHT_TOP
                BitmapStickerIcon.LEFT_BOTTOM -> BitmapStickerIcon.RIGHT_BOTTOM
                BitmapStickerIcon.RIGHT_TOP -> BitmapStickerIcon.LEFT_TOP
                BitmapStickerIcon.RIGHT_BOTTOM -> BitmapStickerIcon.LEFT_BOTTOM
                else -> result
            }
        }
        if (sticker.isFlippedVertically) {
            result = when (result) {
                BitmapStickerIcon.LEFT_TOP -> BitmapStickerIcon.LEFT_BOTTOM
                BitmapStickerIcon.LEFT_BOTTOM -> BitmapStickerIcon.LEFT_TOP
                BitmapStickerIcon.RIGHT_TOP -> BitmapStickerIcon.RIGHT_BOTTOM
                BitmapStickerIcon.RIGHT_BOTTOM -> BitmapStickerIcon.RIGHT_TOP
                else -> result
            }
        }
        return result
    }

    protected fun cropSticker(sticker: Sticker?, event: MotionEvent, gravity: Int) {
        if (sticker == null || !sticker.isCroppable) {
            return
        }
        val inv = Matrix()
        sticker.canvasMatrix.invert(inv)
        val inv2 = Matrix()
        sticker.matrix.invert(inv2)
        val temp = floatArrayOf(event.x, event.y)
        inv.mapPoints(temp)
        inv2.mapPoints(temp)
        val cropped = RectF(sticker.croppedBounds)
        val px = temp[0].toInt().toFloat()
        val py = temp[1].toInt().toFloat()

        when (convertFlippedGravity(sticker, gravity)) {
            BitmapStickerIcon.LEFT_TOP -> {
                cropped.left = min(px, cropped.right)
                cropped.top = min(py, cropped.bottom)
            }
            BitmapStickerIcon.RIGHT_TOP -> {
                cropped.right = max(px, cropped.left)
                cropped.top = min(py, cropped.bottom)
            }
            BitmapStickerIcon.LEFT_BOTTOM -> {
                cropped.left = min(px, cropped.right)
                cropped.bottom = max(py, cropped.top)
            }
            BitmapStickerIcon.RIGHT_BOTTOM -> {
                cropped.right = max(px, cropped.left)
                cropped.bottom = max(py, cropped.top)
            }
        }
        sticker.setCroppedBounds(cropped)
    }

    fun duplicateCurrentSticker() = duplicateSelection()

    fun duplicateSticker(sticker: Sticker) {
        setSelection(listOf(sticker))
        duplicateSelection()
    }

    protected fun findCurrentIconTouched(): BitmapStickerIcon? {
        for (icon in activeIcons.value!!) {
            val x: Float = icon.x + icon.iconRadius - downXScaled
            val y: Float = icon.y + icon.iconRadius - downYScaled
            val distancePow2 = x * x + y * y
            if (distancePow2 <= ((icon.iconRadius + icon.iconRadius) * 1.2f.toDouble()).pow(2.0)) {
                return icon
            }
        }
        return null
    }

    /** The topmost selected item under the touch point. */
    private fun findSelectedSticker(): Sticker? {
        tmp[0] = downX
        tmp[1] = downY
        return items.lastOrNull { it in selection && it.isVisible && it.containsCropped(tmp) }
    }

    /** The topmost item under the touch point. */
    protected fun findHandlingSticker(): Sticker? {
        tmp[0] = downX
        tmp[1] = downY
        return items.lastOrNull { it.isVisible && it.containsCropped(tmp) }
    }

    protected fun calculateDown(event: MotionEvent?) {
        if (event == null || event.pointerCount < 1) {
            downX = 0f
            downY = 0f
            downXScaled = 0f
            downYScaled = 0f
            return
        }
        val pts = floatArrayOf(event.getX(0), event.getY(0))
        downX = pts[0]
        downY = pts[1]
        canvasMatrix.value!!.invert(inverse)
        inverse.mapPoints(pts)
        downXScaled = pts[0]
        downYScaled = pts[1]
    }

    fun flipCurrentSticker(direction: Int) {
        handlingSticker.value?.let { sticker -> change { flip(sticker, direction) } }
    }

    private fun flip(sticker: Sticker, @Flip direction: Int) {
        // Mirror around the centre of the visible (cropped) area so a cropped
        // image flips in place instead of jumping to the mirrored position.
        sticker.getCenterPointCropped(midPoint)
        if (direction and StickerView.FLIP_HORIZONTALLY > 0) {
            sticker.matrix.preScale(-1f, 1f, midPoint.x, midPoint.y)
            sticker.isFlippedHorizontally = !sticker.isFlippedHorizontally
        }
        if (direction and StickerView.FLIP_VERTICALLY > 0) {
            sticker.matrix.preScale(1f, -1f, midPoint.x, midPoint.y)
            sticker.isFlippedVertically = !sticker.isFlippedVertically
        }
        sticker.recalcFinalMatrix()
        stickerOperationListener.onStickerFlipped(sticker)
    }

    fun showCurrentSticker() {
        handlingSticker.value?.isVisible = true
    }

    fun hideCurrentSticker() {
        handlingSticker.value?.isVisible = false
    }

    // endregion

    // region Board operations (PureRef's Images menu)

    private fun unionBounds(stickers: Collection<Sticker>): RectF {
        val union = RectF()
        stickers.forEachIndexed { i, s -> if (i == 0) union.set(s.worldBounds) else union.union(s.worldBounds) }
        return union
    }

    private fun viewAspect(): Float {
        val frameHeight = viewHeight - frameInsetTop - frameInsetBottom
        return if (viewWidth > 0 && frameHeight > 0) viewWidth / frameHeight else 1f
    }

    /** Spacing between arranged items, proportional to their typical size. */
    private fun gapFor(bounds: List<RectF>): Float {
        val sides = bounds.map { min(it.width(), it.height()) }.sorted()
        return max(1f, sides[sides.size / 2] * 0.03f)
    }

    fun arrange(arrangement: Arrangement) {
        val targets = targets().filter { !it.isLocked }
        if (targets.isEmpty()) return
        change {
            val bounds = targets.map { it.worldBounds }
            val origin = unionBounds(targets)
            val gap = gapFor(bounds)
            val order: List<Int> = when (arrangement) {
                Arrangement.OPTIMAL -> targets.indices.toList()
                Arrangement.NAME -> targets.indices.sortedWith(
                    compareBy<Int, String?>(NaturalOrder) { targets[it].name }
                        .thenBy { targets[it].addedOrder })
                Arrangement.ORDER -> targets.indices.sortedBy { targets[it].addedOrder }
                Arrangement.RANDOM -> targets.indices.shuffled()
            }
            val boxes = order.map { Arranger.Box(bounds[it].width(), bounds[it].height()) }
            val positions = if (arrangement == Arrangement.OPTIMAL) {
                Arranger.optimal(boxes, gap, viewAspect())
            } else {
                Arranger.rows(boxes, gap, viewAspect())
            }
            order.forEachIndexed { k, i ->
                targets[i].matrix.postTranslate(
                    origin.left + positions[k].x - bounds[i].left,
                    origin.top + positions[k].y - bounds[i].top
                )
            }
        }
        // Like PureRef, frame the result afterwards.
        fitTo(targets)
    }

    fun align(alignment: Alignment) {
        val targets = targets().filter { !it.isLocked }
        if (targets.size < 2) return
        change {
            val bounds = targets.map { it.worldBounds }
            val union = unionBounds(targets)
            val gap = gapFor(bounds)
            when (alignment) {
                Alignment.LEFT -> targets.forEachIndexed { i, s -> s.matrix.postTranslate(union.left - bounds[i].left, 0f) }
                Alignment.RIGHT -> targets.forEachIndexed { i, s -> s.matrix.postTranslate(union.right - bounds[i].right, 0f) }
                Alignment.TOP -> targets.forEachIndexed { i, s -> s.matrix.postTranslate(0f, union.top - bounds[i].top) }
                Alignment.BOTTOM -> targets.forEachIndexed { i, s -> s.matrix.postTranslate(0f, union.bottom - bounds[i].bottom) }
                Alignment.ROW -> {
                    var x = union.left
                    targets.indices.sortedBy { bounds[it].left }.forEach { i ->
                        targets[i].matrix.postTranslate(x - bounds[i].left, union.top - bounds[i].top)
                        x += bounds[i].width() + gap
                    }
                }
                Alignment.COLUMN -> {
                    var y = union.top
                    targets.indices.sortedBy { bounds[it].top }.forEach { i ->
                        targets[i].matrix.postTranslate(union.left - bounds[i].left, y - bounds[i].top)
                        y += bounds[i].height() + gap
                    }
                }
                Alignment.STACK -> targets.forEachIndexed { i, s ->
                    s.matrix.postTranslate(union.left - bounds[i].left, union.top - bounds[i].top)
                }
            }
        }
    }

    /** Scales items to a common size (the average), each around its own centre. */
    fun normalize(normalization: Normalization) {
        val targets = targets().filter { !it.isLocked }
        if (targets.size < 2) return
        change {
            val bounds = targets.map { it.worldBounds }
            val measure: (Int) -> Float = when (normalization) {
                Normalization.HEIGHT -> { i -> bounds[i].height() }
                Normalization.WIDTH -> { i -> bounds[i].width() }
                Normalization.SCALE -> { i -> targets[i].currentScale }
                Normalization.SIZE -> { i -> max(bounds[i].width(), bounds[i].height()) }
                Normalization.AREA -> { i -> sqrt(bounds[i].width() * bounds[i].height()) }
            }
            val reference = targets.indices.map(measure).average().toFloat()
            targets.forEachIndexed { i, s ->
                val m = measure(i)
                if (m > 0f) {
                    val f = reference / m
                    s.matrix.postScale(f, f, bounds[i].centerX(), bounds[i].centerY())
                }
            }
        }
    }

    fun deleteSelection() {
        val doomed = selected()
        if (doomed.isEmpty()) return
        change { items.removeAll(doomed.toSet()) }
        clearSelection()
    }

    fun duplicateSelection() {
        val originals = selected()
        if (originals.isEmpty()) return
        val offset = gapFor(originals.map { it.worldBounds }) * 2
        val groups = HashMap<Long, Long>()
        val copies = originals.map { original ->
            original.copy(false).also {
                it.matrix.postTranslate(offset, offset)
                if (original.groupId != 0L) {
                    it.groupId = groups.getOrPut(original.groupId) { Sticker.newGroupId() }
                }
                it.setCanvasMatrix(canvasMatrix.value!!.getMatrix())
            }
        }
        change { items.addAll(copies) }
        setSelection(copies)
    }

    fun flipSelection(@Flip direction: Int) {
        val targets = movable()
        if (targets.isEmpty()) return
        change { targets.forEach { flip(it, direction) } }
    }

    /** Resets scale and rotation, keeping flips and the item's centre. */
    fun resetTransform() {
        val targets = movable()
        if (targets.isEmpty()) return
        change {
            targets.forEach { s ->
                val center = s.worldBounds
                s.matrix.reset()
                if (s.isFlippedHorizontally) s.matrix.preScale(-1f, 1f, s.width / 2f, s.height / 2f)
                if (s.isFlippedVertically) s.matrix.preScale(1f, -1f, s.width / 2f, s.height / 2f)
                val now = s.worldBounds
                s.matrix.postTranslate(center.centerX() - now.centerX(), center.centerY() - now.centerY())
            }
        }
    }

    fun resetCrop() {
        val targets = movable().filter { it.isCroppable }
        if (targets.isEmpty()) return
        change { targets.forEach { it.setCroppedBounds(RectF(it.realBounds)) } }
    }

    fun sendToFront() {
        val chosen = selected()
        if (chosen.isEmpty()) return
        change {
            items.removeAll(chosen.toSet())
            items.addAll(chosen)
        }
    }

    fun sendToBack() {
        val chosen = selected()
        if (chosen.isEmpty()) return
        change {
            items.removeAll(chosen.toSet())
            items.addAll(0, chosen)
        }
    }

    /** Flips a boolean property on the selection: on for all unless all are already on. */
    private fun toggle(get: (Sticker) -> Boolean, set: (Sticker, Boolean) -> Unit) {
        val chosen = selected()
        if (chosen.isEmpty()) return
        val value = !chosen.all(get)
        change { chosen.forEach { set(it, value) } }
    }

    fun toggleGrayscale() = toggle({ it.isGrayscale }, { s, v -> s.isGrayscale = v })

    fun toggleSmooth() = toggle({ !it.isSmooth }, { s, v -> s.isSmooth = !v })

    fun toggleLocked() = toggle({ it.isLocked }, { s, v -> s.isLocked = v })

    fun setOpacity(opacity: Int) {
        val chosen = selected()
        change { chosen.forEach { it.opacity = opacity } }
    }

    fun group() {
        val chosen = selected()
        if (chosen.size < 2) return
        val id = Sticker.newGroupId()
        change { chosen.forEach { it.groupId = id } }
    }

    fun ungroup() {
        val chosen = selected()
        change { chosen.forEach { it.groupId = 0L } }
    }

    fun cropDestructively(resources: android.content.res.Resources, all: Boolean) {
        val chosen = (if (all) ArrayList(items) else selected()).filterIsInstance<DrawableSticker>()
        change { chosen.forEach { it.cropDestructively(resources) } }
    }

    // endregion

    // region Camera

    private var cameraAnimator: ValueAnimator? = null
    private var focusReturn: Matrix? = null
    private var focusedIds: Set<Long> = emptySet()

    private fun cancelCameraAnimation() {
        cameraAnimator?.cancel()
        cameraAnimator = null
    }

    fun animateCanvasTo(target: Matrix) {
        cancelCameraAnimation()
        val from = FloatArray(9).also { canvasMatrix.value!!.getMatrix().getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        val current = FloatArray(9)
        val m = Matrix()
        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0 until 9) current[i] = from[i] + (to[i] - from[i]) * t
                m.setValues(current)
                setCanvas(m)
            }
            start()
        }
    }

    /** Frames the given items; everything if empty. */
    fun fitTo(stickers: Collection<Sticker>) {
        if (viewWidth == 0 || viewHeight == 0) return
        val chosen = stickers.ifEmpty { items }
        if (chosen.isEmpty()) {
            resetView()
            return
        }
        val bounds = unionBounds(chosen)
        val margin = 0.94f
        // Frame inside the part of the screen the toolbars don't cover.
        val frameHeight = max(1f, viewHeight - frameInsetTop - frameInsetBottom)
        val scale = min(
            viewWidth * margin / max(1f, bounds.width()),
            frameHeight * margin / max(1f, bounds.height())
        ).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val target = Matrix()
        target.setScale(scale, scale)
        target.postTranslate(
            viewWidth / 2f - bounds.centerX() * scale,
            frameInsetTop + frameHeight / 2f - bounds.centerY() * scale
        )
        animateCanvasTo(target)
    }

    /** "Optimize": fit the canvas snugly around all images. */
    fun fitAll() = fitTo(items)

    /** Zooms in on the given items; doing it again returns to the previous view. */
    fun focus(stickers: List<Sticker>) {
        val ids = stickers.map { it.id }.toSet()
        val back = focusReturn
        if (back != null && ids == focusedIds) {
            focusReturn = null
            focusedIds = emptySet()
            animateCanvasTo(back)
            return
        }
        focusReturn = Matrix(canvasMatrix.value!!.getMatrix())
        focusedIds = ids
        fitTo(stickers)
    }

    fun zoomToSelection() {
        val chosen = selected()
        if (chosen.isEmpty()) fitAll() else focus(chosen)
    }

    /** Back to 100% zoom, keeping the centre of the screen in place. */
    fun resetZoom() {
        val center = screenToWorld(viewWidth / 2f, viewHeight / 2f)
        val target = Matrix()
        target.setTranslate(viewWidth / 2f - center.x, viewHeight / 2f - center.y)
        animateCanvasTo(target)
    }

    // endregion

    override fun onCleared() {
        cancelCameraAnimation()
        cancelLongPress()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_BACKGROUND = 0xFF303030.toInt()

        /** World units between grid lines, and the snapping step. */
        const val GRID_SIZE = 50f

        /** Longest a two/three-finger touch can last and still count as a tap. */
        const val MULTI_TAP_TIMEOUT = 400L

        const val MIN_ZOOM = 0.01f
        const val MAX_ZOOM = 50f
    }
}

/** Orders "img2" before "img10". */
object NaturalOrder : Comparator<String?> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String?, b: String?): Int {
        if (a == b) return 0
        if (a == null) return 1
        if (b == null) return -1
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until min(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
