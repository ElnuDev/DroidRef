package com.xiaopo.flying.sticker

/**
 * Undo/redo by snapshotting the board. Snapshots are copies that share bitmap
 * data, so they are cheap; they are never mutated once taken.
 */
class History(private val limit: Int = 100) {
    private class Snapshot(val items: List<Sticker>) {
        val signatures: List<String> = items.map { it.stateSignature() }
    }

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var pending: Snapshot? = null

    var onChanged: (() -> Unit)? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    private fun snapshot(items: List<Sticker>) = Snapshot(items.map { it.copy(true) })

    /** Starts a change (e.g. at touch down); [commit] records it only if something changed. */
    fun begin(items: List<Sticker>) {
        if (pending == null) {
            pending = snapshot(items)
        }
    }

    fun commit(items: List<Sticker>) {
        val before = pending ?: return
        pending = null
        if (before.signatures != items.map { it.stateSignature() }) {
            push(before)
        }
    }

    fun cancel() {
        pending = null
    }

    /** Runs a change as one undo step. */
    inline fun <T> record(items: List<Sticker>, change: () -> T): T {
        begin(items)
        try {
            return change()
        } finally {
            commit(items)
        }
    }

    private fun push(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        while (undoStack.size > limit) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        onChanged?.invoke()
    }

    /** Restores the previous state into [items]; returns false if there is nothing to undo. */
    fun undo(items: MutableList<Sticker>): Boolean = swap(items, undoStack, redoStack)

    fun redo(items: MutableList<Sticker>): Boolean = swap(items, redoStack, undoStack)

    private fun swap(
        items: MutableList<Sticker>,
        from: ArrayDeque<Snapshot>,
        to: ArrayDeque<Snapshot>
    ): Boolean {
        pending = null
        val target = from.removeLastOrNull() ?: return false
        to.addLast(snapshot(items))
        items.clear()
        // Copy again so the stored snapshot stays pristine for redo.
        target.items.mapTo(items) { it.copy(true) }
        onChanged?.invoke()
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        pending = null
        onChanged?.invoke()
    }
}
