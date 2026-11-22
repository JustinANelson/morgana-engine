package com.morgana.client.realms

import com.morgana.client.state.RecentRealmEntry
import com.morgana.protocol.realms.RealmSummary

/** How the realm list is ordered once the player stops using the grouped default view. */
enum class RealmSort(val label: String) {
    RECOMMENDED("Recommended"),
    PLAYERS("Most players"),
    NAME("Name A-Z")
}

/** What the player has typed or toggled in the realm browser. */
data class RealmFilter(
    val text: String = "",
    /** Realms must carry every one of these tags. */
    val tags: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false,
    val hideFull: Boolean = false,
    val sort: RealmSort = RealmSort.RECOMMENDED
) {
    /** True when the list is narrowed in any way (the grouped sections only make sense when it is not). */
    val isFiltering: Boolean get() = text.isNotBlank() || tags.isNotEmpty() || favoritesOnly || hideFull
}

/** One realm as the list shows it, with what the player's own history adds. */
data class RealmRow(val realm: RealmSummary, val favorite: Boolean, val lastPlayedMs: Long?, val featured: Boolean) {
    /** All slots taken (a realm that lists no capacity is never full). */
    val full: Boolean get() = realm.maxPlayers > 0 && realm.onlinePlayers >= realm.maxPlayers

    /** 0..1, for the little population bar. */
    val fillFraction: Float get() = if (realm.maxPlayers <= 0) 0f else (realm.onlinePlayers.toFloat() / realm.maxPlayers).coerceIn(0f, 1f)

    /** Whether this client understands the engine API the realm needs. */
    fun compatible(supportedEngineApi: Int): Boolean = realm.engineApi <= supportedEngineApi
}

data class RealmSection(val title: String, val rows: List<RealmRow>)

data class TagCount(val tag: String, val count: Int, val selected: Boolean)

/**
 * What the browser shows for one [RealmFilter]: [sections] (already trimmed to the row cap), how many realms matched
 * ([matched]), how many are in the directory ([total]), how many matched ones were cut off ([hidden]) and the tag chips.
 */
data class RealmBrowserView(
    val sections: List<RealmSection>,
    val matched: Int,
    val total: Int,
    val hidden: Int,
    val facets: List<TagCount>
) {
    val shown: Int get() = sections.sumOf { it.rows.size }
}

/**
 * The logic behind the realm picker, kept free of any widgets so it can be tested. Built for directories of hundreds of realms:
 * multi-word search, tag chips, favourites and recently played pinned on top, a row cap with an honest "N more" count.
 */
class RealmBrowserModel(
    private val realms: List<RealmSummary>,
    private val featuredId: String?,
    private val isFavorite: (String) -> Boolean,
    recents: List<RecentRealmEntry>
) {
    private val lastPlayed: Map<String, Long> = recents.associate { it.realmId.lowercase() to it.lastPlayedEpochMs }

    private fun row(r: RealmSummary) = RealmRow(r, isFavorite(r.id), lastPlayed[r.id.lowercase()], r.id.equals(featuredId, ignoreCase = true))

    private fun matchesText(r: RealmSummary, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val haystack = buildString {
            append(r.name).append(' ').append(r.gameTitle).append(' ').append(r.description).append(' ')
            append(r.tags.joinToString(" ")).append(' ').append(r.host)
        }.lowercase()
        return terms.all { it in haystack }
    }

    private fun recommendedOrder(): Comparator<RealmRow> =
        compareByDescending<RealmRow> { it.favorite }
            .thenByDescending { it.featured }
            .thenByDescending { it.lastPlayedMs ?: Long.MIN_VALUE }
            .thenBy { it.full } // a realm you cannot join right now sorts below one you can
            .thenByDescending { it.realm.onlinePlayers }
            .thenBy { it.realm.name.lowercase() }

    private fun order(sort: RealmSort): Comparator<RealmRow> = when (sort) {
        RealmSort.RECOMMENDED -> recommendedOrder()
        RealmSort.PLAYERS -> compareByDescending<RealmRow> { it.realm.onlinePlayers }.thenBy { it.realm.name.lowercase() }
        RealmSort.NAME -> compareBy { it.realm.name.lowercase() }
    }

    /** The list for [filter], with at most [maxRows] rows shown; [facetCount] tag chips are offered. */
    fun view(filter: RealmFilter, maxRows: Int = 200, facetCount: Int = 8): RealmBrowserView {
        val terms = filter.text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val rows = realms.map { row(it) }
        val beforeTags = rows.filter {
            matchesText(it.realm, terms) && (!filter.favoritesOnly || it.favorite) && (!filter.hideFull || !it.full)
        }
        val wanted = filter.tags.map { it.lowercase() }
        val matched = beforeTags.filter { r -> wanted.all { w -> r.realm.tags.any { it.equals(w, ignoreCase = true) } } }

        val sections: List<RealmSection> =
            if (!filter.isFiltering && filter.sort == RealmSort.RECOMMENDED) groupedSections(matched)
            else listOf(RealmSection("Results", matched.sortedWith(order(filter.sort))))

        var budget = maxRows
        val trimmed = sections.mapNotNull { s ->
            if (budget <= 0 || s.rows.isEmpty()) return@mapNotNull null
            val take = s.rows.take(budget)
            budget -= take.size
            RealmSection(s.title, take)
        }
        val shown = trimmed.sumOf { it.rows.size }
        return RealmBrowserView(trimmed, matched.size, realms.size, matched.size - shown, facets(beforeTags, filter.tags, facetCount))
    }

    /** Favourites, then recently played, then the featured realm, then everything else by population. */
    private fun groupedSections(rows: List<RealmRow>): List<RealmSection> {
        val ordered = rows.sortedWith(recommendedOrder())
        val favorites = ordered.filter { it.favorite }
        val rest1 = ordered.filterNot { it.favorite }
        val recent = rest1.filter { it.lastPlayedMs != null }.sortedByDescending { it.lastPlayedMs }.take(RECENT_SHOWN)
        val rest2 = rest1.filterNot { it in recent }
        val featured = rest2.filter { it.featured }
        val everyone = rest2.filterNot { it.featured }
        return listOf(
            RealmSection("Favorites", favorites),
            RealmSection("Recently played", recent),
            RealmSection("Featured", featured),
            RealmSection("All realms", everyone)
        ).filter { it.rows.isNotEmpty() }
    }

    private fun facets(rows: List<RealmRow>, selected: Set<String>, limit: Int): List<TagCount> {
        val counts = linkedMapOf<String, Pair<String, Int>>()
        for (r in rows) for (t in r.realm.tags.distinctBy { it.lowercase() }) {
            val key = t.lowercase()
            val (shownTag, n) = counts[key] ?: (t to 0)
            counts[key] = shownTag to n + 1
        }
        val selectedKeys = selected.map { it.lowercase() }.toSet()
        val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }.thenBy { it.key })
            .take(limit).map { TagCount(it.value.first, it.value.second, it.key in selectedKeys) }
        // A chosen tag stays visible (so it can be switched off) even if nothing matches it any more.
        val missing = selected.filter { s -> top.none { it.tag.equals(s, ignoreCase = true) } }.map { TagCount(it, counts[it.lowercase()]?.second ?: 0, true) }
        return top + missing
    }

    companion object {
        const val RECENT_SHOWN = 5

        /**
         * The realm [delta] rows after (positive) or before (negative) [currentId] in the order the list shows them, for the arrow
         * keys. Stays on the first or last row at the ends. With nothing selected (or the selection filtered out) it picks the
         * first row going down and the last going up. Null when the list is empty.
         */
        fun step(view: RealmBrowserView, currentId: String?, delta: Int): RealmSummary? {
            val rows = view.sections.flatMap { it.rows }
            if (rows.isEmpty()) return null
            val at = rows.indexOfFirst { it.realm.id == currentId }
            val next = when {
                at < 0 -> if (delta >= 0) 0 else rows.lastIndex
                else -> (at + delta).coerceIn(0, rows.lastIndex)
            }
            return rows[next].realm
        }

        /** "just now", "5 min ago", "3 h ago", "2 days ago", "5 weeks ago". */
        fun agoText(nowMs: Long, thenMs: Long): String {
            val minutes = ((nowMs - thenMs).coerceAtLeast(0L)) / 60_000L
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 60 * 24 -> "${minutes / 60} h ago"
                minutes < 60 * 24 * 14 -> "${minutes / (60 * 24)} days ago"
                else -> "${minutes / (60 * 24 * 7)} weeks ago"
            }
        }
    }
}
