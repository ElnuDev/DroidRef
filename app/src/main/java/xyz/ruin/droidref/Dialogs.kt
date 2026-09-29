package xyz.ruin.droidref

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.xiaopo.flying.sticker.BoardRenderer
import com.xiaopo.flying.sticker.NoteSticker

/** Small dialogs built in code; none of them need a layout file. */
object Dialogs {
    val PALETTE = intArrayOf(
        Color.WHITE, 0xFFBDBDBD.toInt(), 0xFF616161.toInt(), 0xFF303030.toInt(), 0xFF1E1E1E.toInt(), Color.BLACK,
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(),
        0xFF8E24AA.toInt(),
    )

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun Context.themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = context.dp(20)
        setPadding(pad, context.dp(8), pad, 0)
    }

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setPadding(0, context.dp(12), 0, context.dp(4))
    }

    /** A grid of color swatches; the chosen one gets a ring. */
    private class Swatches(context: Context, colors: IntArray, initial: Int, onPick: (Int) -> Unit) {
        val view = GridLayout(context).apply { columnCount = 6 }
        private val cells = ArrayList<Pair<Int, View>>()

        init {
            val size = context.dp(36)
            val margin = context.dp(4)
            for (color in colors) {
                val cell = View(context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = size
                        height = size
                        setMargins(margin, margin, margin, margin)
                    }
                    contentDescription = BoardRenderer.hex(color)
                    setOnClickListener {
                        select(color)
                        onPick(color)
                    }
                }
                cells.add(color to cell)
                view.addView(cell)
            }
            select(initial)
        }

        fun select(chosen: Int) {
            for ((color, cell) in cells) {
                cell.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    val ring = if (color == chosen) cell.context.themeColor(R.attr.droidrefPrimary) else Color.GRAY
                    setStroke(cell.context.dp(if (color == chosen) 3 else 1), ring)
                }
            }
        }
    }

    fun text(
        context: Context,
        title: String,
        initial: String?,
        hint: String? = null,
        multiLine: Boolean = false,
        onDone: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            setText(initial)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or
                    (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0)
            initial?.let { setSelection(it.length) }
        }
        val layout = column(context).apply { addView(input) }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    fun note(
        context: Context,
        existing: NoteSticker?,
        onDone: (text: String, textColor: Int, backgroundColor: Int) -> Unit
    ) {
        var textColor = existing?.textColor ?: Color.WHITE
        val input = EditText(context).apply {
            setText(existing?.text)
            hint = "Note text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            existing?.text?.let { setSelection(it.length) }
        }
        val background = CheckBox(context).apply {
            text = "Background"
            isChecked = existing == null || Color.alpha(existing.backgroundColor) > 0
        }
        val layout = column(context).apply {
            addView(input)
            addView(label(context, "Text color"))
            addView(Swatches(context, PALETTE, textColor) { textColor = it }.view)
            addView(background)
        }
        AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Add note" else "Edit note")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    // Contrasting translucent box behind the text.
                    val box = if (!background.isChecked) Color.TRANSPARENT
                    else if (luminance(textColor) > 0.5) 0x99000000.toInt() else 0x99FFFFFF.toInt()
                    onDone(text, textColor, box)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun luminance(color: Int) =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255

    /** A 0–255 slider that previews live; [onDone] gets null on cancel. */
    fun slider(
        context: Context,
        title: String,
        initial: Int,
        max: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
        onDone: (Int?) -> Unit
    ) {
        val value = TextView(context).apply { gravity = Gravity.CENTER; text = format(initial) }
        val bar = SeekBar(context).apply {
            this.max = max
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = format(progress)
                    onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        val layout = column(context).apply {
            addView(value)
            addView(bar)
        }
        var finished = false
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ -> finished = true; onDone(bar.progress) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!finished) onDone(null) }
            .show()
    }

    /** Palette plus a hex field. */
    fun color(context: Context, title: String, initial: Int, onDone: (Int) -> Unit) {
        var chosen = initial
        val hex = EditText(context).apply {
            setText(BoardRenderer.hex(initial))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val swatches = Swatches(context, PALETTE, initial) {
            chosen = it
            hex.setText(BoardRenderer.hex(it))
        }
        val layout = column(context).apply {
            addView(swatches.view)
            addView(label(context, "Hex"))
            addView(hex)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(parseColor(hex.text.toString()) ?: chosen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun parseColor(text: String): Int? = try {
        val t = text.trim().let { if (it.startsWith("#")) it else "#$it" }
        Color.parseColor(t) or (if (t.length == 7) 0xFF000000.toInt() else 0)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun pen(context: Context, color: Int, width: Float, onDone: (Int, Float) -> Unit) {
        var chosen = color
        val size = TextView(context).apply { text = "Width: ${width.toInt()} px" }
        val bar = SeekBar(context).apply {
            max = 63
            progress = width.toInt() - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    size.text = "Width: ${progress + 1} px"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        val layout = column(context).apply {
            addView(label(context, "Color"))
            addView(Swatches(context, PALETTE, color) { chosen = it }.view)
            addView(size)
            addView(bar)
        }
        AlertDialog.Builder(context)
            .setTitle("Pen")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(chosen, (bar.progress + 1).toFloat()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun pickedColor(
        context: Context,
        color: Int,
        onCopy: () -> Unit,
        onUseForPen: () -> Unit,
        onUseForBackground: () -> Unit
    ) {
        val swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(64))
            setBackgroundColor(color or 0xFF000000.toInt())
        }
        val details = TextView(context).apply {
            text = "${BoardRenderer.hex(color)}\nRGB ${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)}"
            setTextIsSelectable(true)
            setPadding(0, context.dp(8), 0, 0)
        }
        val layout = column(context).apply {
            addView(swatch)
            addView(details)
        }
        AlertDialog.Builder(context)
            .setTitle("Picked color")
            .setView(layout)
            .setPositiveButton("Copy hex") { _, _ -> onCopy() }
            .setNeutralButton("Use for pen") { _, _ -> onUseForPen() }
            .setNegativeButton("Use as background") { _, _ -> onUseForBackground() }
            .show()
    }
}
