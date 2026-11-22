package com.morgana.devtools.wiki

/** Generates the deterministic bundle-root `index.md` from concept frontmatter (OKF section 8). */
object WikiIndex {
    const val OKF_VERSION = "0.2"

    /** Directory display order and headings. Unknown directories sort alphabetically after these. */
    private val GROUPS = listOf(
        "" to "Start Here",
        "architecture" to "Architecture",
        "domain" to "Domain Concepts",
        "systems" to "Simulation Systems",
        "server" to "Server",
        "client" to "Client",
        "protocol" to "Protocol",
        "content" to "Content & Data Pipeline",
        "decisions" to "Decisions",
        "playbooks" to "Playbooks",
        "reference" to "Reference",
        "issues" to "Known Issues"
    )

    private const val INTRO =
        "Maintenance knowledge base for Morgana ORPG, written for human and AI contributors. " +
            "Pages are OKF concepts; see [the wiki guide](../docs/WIKI_GUIDE.md) for conventions and workflows."

    fun generate(concepts: List<Concept>): String {
        val byDir = concepts.groupBy { it.relPath.substringBeforeLast('/', "") }
        val known = GROUPS.map { it.first }.toSet()
        val order = GROUPS.map { it.first } + byDir.keys.filter { it !in known }.sorted()
        val headings = GROUPS.toMap()
        val sb = StringBuilder()
        sb.append("---\nokf_version: \"$OKF_VERSION\"\n---\n")
        sb.append("# Morgana ORPG Wiki\n\n").append(INTRO).append("\n")
        for (dir in order) {
            val list = byDir[dir]?.sortedBy { it.relPath } ?: continue
            val heading = headings[dir] ?: dir.replace('/', ' ').replaceFirstChar { it.uppercase() }
            sb.append("\n## ").append(heading).append("\n\n")
            for (c in list) {
                val desc = c.description.ifBlank { c.type }
                sb.append("* [").append(c.title).append("](").append(c.id).append(".md) - ").append(desc).append("\n")
            }
        }
        return sb.toString()
    }
}
