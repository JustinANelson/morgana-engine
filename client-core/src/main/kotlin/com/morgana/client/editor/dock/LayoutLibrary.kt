package com.morgana.client.editor.dock

/** A tiny string store: the platform preferences in the game, a map in tests. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemoryKeyValueStore : KeyValueStore {
    val values = LinkedHashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/**
 * The developer's own saved editor layouts, by name: save the arrangement of panels as it is now, load it later, delete it.
 * Layouts are the text of [DockLayout.serialize], so loading one is [DockLayout.restore] on top of the defaults.
 *
 * Names are tidied (trimmed, inner spaces collapsed) and limited to letters, digits, spaces and `- _ . ( )`, at most
 * [MAX_NAME] long. A name equal (ignoring case) to one of [reserved] (the built-in layouts) is refused, saving under an
 * existing name replaces it, and at most [MAX_LAYOUTS] are kept. A damaged index never breaks anything: names without a
 * stored layout are simply not listed.
 */
class LayoutLibrary(private val store: KeyValueStore, private val reserved: Collection<String> = emptyList()) {

    /** The saved layout names, oldest first. */
    fun names(): List<String> =
        (store.get(INDEX_KEY) ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().filter { store.get(layoutKey(it)) != null }

    fun load(name: String): String? {
        val tidy = tidy(name)
        val match = names().firstOrNull { it.equals(tidy, ignoreCase = true) } ?: return null
        return store.get(layoutKey(match))
    }

    /** Saves [text] as [name]; returns why it could not be saved, or null when it was. */
    fun save(name: String, text: String): String? {
        val tidy = tidy(name)
        nameProblem(tidy)?.let { return it }
        val existing = names()
        val replaced = existing.firstOrNull { it.equals(tidy, ignoreCase = true) }
        if (replaced == null && existing.size >= MAX_LAYOUTS) return "You can keep up to $MAX_LAYOUTS layouts; delete one first"
        val target = replaced ?: tidy
        store.put(layoutKey(target), text)
        if (replaced == null) writeIndex(existing + target)
        return null
    }

    fun delete(name: String) {
        val tidy = tidy(name)
        val existing = names()
        val match = existing.firstOrNull { it.equals(tidy, ignoreCase = true) } ?: return
        store.remove(layoutKey(match))
        writeIndex(existing - match)
    }

    /** Why [name] cannot be used, or null if it is fine. */
    fun nameProblem(name: String): String? {
        val tidy = tidy(name)
        return when {
            tidy.isEmpty() -> "Type a name for the layout"
            tidy.length > MAX_NAME -> "Use at most $MAX_NAME characters"
            tidy.any { !(it.isLetterOrDigit() || it in " -_.()") } -> "Use letters, numbers, spaces and - _ . ( ) only"
            reserved.any { it.equals(tidy, ignoreCase = true) } -> "\"$tidy\" is a built-in layout; pick another name"
            else -> null
        }
    }

    private fun writeIndex(names: List<String>) = store.put(INDEX_KEY, names.joinToString("\n"))

    private fun layoutKey(name: String) = "layout.$name"

    companion object {
        const val MAX_NAME = 32
        const val MAX_LAYOUTS = 20
        private const val INDEX_KEY = "layout-names"

        fun tidy(name: String): String = name.trim().replace(Regex("\\s+"), " ")
    }
}

/** A built-in arrangement: just the panels it shows (everything else is hidden, and everything is at its default place). */
data class LayoutPreset(val name: String, val description: String, val panelIds: List<String>)

/** Whether a panel is on screen, put away on the panel bar, or closed. */
enum class PanelState { SHOWN, MINIMIZED, HIDDEN }

/** What the panel finder lists for one panel. */
data class PanelInfo(val id: String, val title: String, val group: String, val state: PanelState, val description: String = "")

/** Finds panels by what the developer types: every word must appear in the title, group or description, titles that start with the first word first. */
object PanelSearch {
    fun filter(panels: List<PanelInfo>, query: String): List<PanelInfo> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return panels
        val matches = panels.filter { p ->
            val haystack = (p.title + " " + p.group + " " + p.description).lowercase()
            words.all { it in haystack }
        }
        return matches.sortedBy { if (it.title.lowercase().startsWith(words.first())) 0 else 1 }
    }
}
