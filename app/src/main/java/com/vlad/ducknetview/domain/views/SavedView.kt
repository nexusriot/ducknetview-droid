package com.vlad.ducknetview.domain.views

/**
 * A named arrangement of the screens — the TUI's `V`.
 *
 * Getting back to "the established TLS connections leaving over Wi-Fi,
 * busiest first" meant setting five chips, a search and a sort in the right
 * order every time. A view is that whole arrangement under a name: the tab,
 * the search and its mode, the quick filters, the Connections sort and the
 * group / closed / totals / reverse-DNS toggles.
 *
 * Fields are strings and plain values rather than the UI's enums, so the
 * domain layer can encode them without depending on `ui/`; the ViewModel maps
 * them back and ignores a value it no longer recognises rather than failing.
 * A per-app filter is deliberately not recorded: it points at a uid, and a uid
 * is not a stable name for an app across reinstalls.
 */
data class SavedView(
    val name: String,
    val tab: String,
    val query: String = "",
    val highlight: Boolean = false,
    val proto: String = "ALL",
    val ipVersion: String = "ALL",
    val state: String = "ALL",
    val publicOnly: Boolean = false,
    val userAppsOnly: Boolean = false,
    val network: String = "",
    val connsSortCol: String = "rx",
    val connsSortDesc: Boolean = true,
    val groupByHost: Boolean = false,
    val showClosed: Boolean = false,
    val totals: Boolean = false,
    val revDns: Boolean = false,
) {
    fun encode(): String = listOf(
        "name" to name, "tab" to tab, "q" to query, "hl" to flag(highlight),
        "proto" to proto, "ipv" to ipVersion, "state" to state, "pub" to flag(publicOnly),
        "mine" to flag(userAppsOnly), "net" to network, "sort" to connsSortCol,
        "desc" to flag(connsSortDesc), "group" to flag(groupByHost),
        "closed" to flag(showClosed), "totals" to flag(totals), "rdns" to flag(revDns),
    ).joinToString(FIELD.toString()) { (k, v) -> k + EQ + escape(v) }

    companion object {
        private const val FIELD = '\u001f'
        private const val EQ = '='

        fun decode(s: String): SavedView? {
            val m = HashMap<String, String>()
            for (part in s.split(FIELD)) {
                val eq = part.indexOf(EQ)
                if (eq <= 0) continue
                m[part.substring(0, eq)] = unescape(part.substring(eq + 1))
            }
            val name = m["name"]?.takeIf { it.isNotBlank() } ?: return null
            val tab = m["tab"]?.takeIf { it.isNotBlank() } ?: return null
            val d = SavedView(name, tab)
            return SavedView(
                name = name,
                tab = tab,
                query = m["q"] ?: d.query,
                highlight = m["hl"] == "1",
                proto = m["proto"] ?: d.proto,
                ipVersion = m["ipv"] ?: d.ipVersion,
                state = m["state"] ?: d.state,
                publicOnly = m["pub"] == "1",
                userAppsOnly = m["mine"] == "1",
                network = m["net"] ?: d.network,
                connsSortCol = m["sort"] ?: d.connsSortCol,
                connsSortDesc = m["desc"]?.let { it == "1" } ?: d.connsSortDesc,
                groupByHost = m["group"] == "1",
                showClosed = m["closed"] == "1",
                totals = m["totals"] == "1",
                revDns = m["rdns"] == "1",
            )
        }

        /** Saving under an existing name replaces it, as in the TUI. */
        fun upsert(views: List<SavedView>, view: SavedView): List<SavedView> {
            val i = views.indexOfFirst { it.name.equals(view.name, ignoreCase = true) }
            return if (i < 0) views + view else views.toMutableList().also { it[i] = view }
        }

        fun decodeAll(list: List<String>): List<SavedView> = list.mapNotNull(::decode)

        private fun flag(b: Boolean) = if (b) "1" else "0"

        // The separator is a control character no one types, so escaping only
        // has to cover itself — but a pasted query is arbitrary text.
        private fun escape(v: String) = v.replace("\\", "\\\\").replace(FIELD.toString(), "\\u")
        private fun unescape(v: String) = buildString {
            var i = 0
            while (i < v.length) {
                val c = v[i]
                if (c == '\\' && i + 1 < v.length) {
                    when (v[i + 1]) {
                        'u' -> append(FIELD)
                        else -> append(v[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
