package com.morgana.client.ui

/** What a toast is about; decides its accent colour and prominence. */
enum class ToastKind { INFO, GOOD, WARNING, LEVEL, BROADCAST }

data class Toast(val text: String, val kind: ToastKind, val shownAtSeconds: Float)

/**
 * The pure side of toasts: a short list of recent messages that expire. The overlay draws [visible]; the game feeds it
 * with [offer]. Time is passed in, so the rules are deterministic and unit-tested.
 */
class ToastQueue(private val maxVisible: Int = 4, private val lifetimeSeconds: Float = 4f) {
    private val shown = ArrayList<Toast>()

    /** Toasts added since the overlay last looked; the overlay drains these to start their animations. */
    private val fresh = ArrayList<Toast>()

    val visible: List<Toast> get() = shown

    /** Adds a toast; an identical message already showing just has its timer restarted. */
    fun add(text: String, kind: ToastKind, nowSeconds: Float) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val existing = shown.indexOfFirst { it.text == clean }
        if (existing >= 0) {
            shown[existing] = shown[existing].copy(shownAtSeconds = nowSeconds)
            return
        }
        val toast = Toast(clean, kind, nowSeconds)
        shown += toast
        fresh += toast
        while (shown.size > maxVisible) shown.removeAt(0)
    }

    /** Offers a server system message; shown only when [ToastClassifier] thinks it is worth a toast. */
    fun offer(systemMessage: String, nowSeconds: Float) {
        val kind = ToastClassifier.classify(systemMessage) ?: return
        add(systemMessage, kind, nowSeconds)
    }

    /** Drops expired toasts; true when the list changed. */
    fun expire(nowSeconds: Float): Boolean = shown.removeAll {
        val ttl = if (it.kind == ToastKind.BROADCAST) BROADCAST_LIFETIME_SECONDS else lifetimeSeconds
        nowSeconds - it.shownAtSeconds >= ttl
    }

    companion object {
        const val BROADCAST_LIFETIME_SECONDS = 6.5f
    }

    fun drainFresh(): List<Toast> = fresh.toList().also { fresh.clear() }

    fun clear() {
        shown.clear()
        fresh.clear()
    }
}

/** The toast kind for a server-typed [com.morgana.protocol.NoticeKind]. */
fun com.morgana.protocol.NoticeKind.toToastKind(): ToastKind = when (this) {
    com.morgana.protocol.NoticeKind.INFO -> ToastKind.INFO
    com.morgana.protocol.NoticeKind.GOOD -> ToastKind.GOOD
    com.morgana.protocol.NoticeKind.WARNING -> ToastKind.WARNING
    com.morgana.protocol.NoticeKind.LEVEL -> ToastKind.LEVEL
}

/** Decides which server system messages deserve a toast, and what kind. Chat and plain notices stay in the chat box only. */
object ToastClassifier {
    fun classify(message: String): ToastKind? {
        val m = message.lowercase()
        return when {
            m.startsWith("art import rejected") || m.startsWith("save rejected") -> null
            m.contains("level up") || m.contains("reached level") || m.contains("you are now level") -> ToastKind.LEVEL
            m.contains("not enough") || m.contains("cannot ") || m.contains("can't ") || m.contains("failed") ||
                m.contains("too far") || m.contains("inventory is full") || m.contains("bag is full") || m.contains("cooldown") -> ToastKind.WARNING
            m.contains("quest") || m.contains("you receive") || m.contains("you received") || m.contains("you pick") ||
                m.contains("picked up") || m.contains("you learn") || m.contains("learned") || m.contains("you gain") ||
                m.contains("you found") -> ToastKind.GOOD
            else -> null
        }
    }
}

/** Seconds since the client started; the clock toasts are timed on (wall clock, so it works on every platform). */
object ToastClock {
    private val startMillis = System.currentTimeMillis()
    fun now(): Float = ((System.currentTimeMillis() - startMillis) / 1000.0).toFloat()
}
