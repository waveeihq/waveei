package app.waveei.android

import android.graphics.Bitmap
import java.io.File

/**
 * Texts and images copied while this keyboard is the selected one, newest first. Texts are kept in
 * memory only; an image is a file in the app's cache, gone when it leaves the list. Copies Android
 * marks as sensitive are never kept.
 */
object ClipboardHistory {
    /** A text, or an image ([image] set, [text] empty) with its type and a small preview. */
    data class Item(
        val text: String,
        val copiedAt: Long,
        val image: File? = null,
        val mime: String? = null,
        val thumbnail: Bitmap? = null,
    )

    private const val CAPACITY = 20
    private const val MAX_CHARS = 5_000
    private val items = ArrayList<Item>()

    @Synchronized
    fun add(text: String, copiedAt: Long) {
        if (text.isBlank() || text.length > MAX_CHARS) return
        if (items.firstOrNull()?.text == text) return
        items.removeAll { it.text == text }
        items.add(0, Item(text, copiedAt))
        trim()
    }

    @Synchronized
    fun addImage(file: File, mime: String, thumbnail: Bitmap?, copiedAt: Long) {
        items.add(0, Item("", copiedAt, file, mime, thumbnail))
        trim()
    }

    @Synchronized
    fun image(file: File): Item? = items.firstOrNull { it.image == file }

    @Synchronized
    fun all(): List<Item> = items.toList()

    @Synchronized
    fun remove(item: Item) {
        items.remove(item)
        item.image?.delete()
    }

    @Synchronized
    fun clear() {
        items.forEach { it.image?.delete() }
        items.clear()
    }

    private fun trim() {
        while (items.size > CAPACITY) items.removeAt(items.lastIndex).image?.delete()
    }
}
