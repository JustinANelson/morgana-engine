package com.morgana.client.editor.forms

/**
 * A live save the server refused. The server says so in a system chat line, `Save rejected for <collection> #<id>: <reasons>`,
 * and the editor panel reads it back so the reason shows in the form instead of only in a chat console that is hidden while
 * the editor is open.
 */
data class SaveRejection(val collection: String, val id: Int, val reason: String) {

    /** True when this refusal is for the [noun] (singular, like "item" or "npc") with [id]. */
    fun isFor(noun: String, id: Int): Boolean = this.id == id && collection == noun + "s"

    companion object {
        private val pattern = Regex("""^Save rejected for (\S+) #(\d+): (.+)$""", RegexOption.DOT_MATCHES_ALL)

        /** The refusal described by a server chat line, or null when the line is anything else. */
        fun parse(message: String): SaveRejection? {
            val m = pattern.matchEntire(message.trim()) ?: return null
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return SaveRejection(m.groupValues[1], id, m.groupValues[3].trim())
        }
    }
}
