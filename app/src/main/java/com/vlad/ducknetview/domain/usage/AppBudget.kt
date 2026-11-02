package com.vlad.ducknetview.domain.usage

/**
 * A data allowance for one app: warn — and, with [block], cut it off — once
 * it has used [capBytes] in the period the caller measures.
 *
 * The cycle-wide [Quota] answers "will I run out"; this answers the narrower
 * question that usually follows, "which app is eating it", for the one or two
 * apps a person already suspects.
 */
data class AppBudget(val packageName: String, val capBytes: Long, val block: Boolean)

object AppBudgets {

    private const val SEP = '|'

    /**
     * One `pkg|cap|0/1` string per budget, for a DataStore string set. A
     * package name cannot contain `|` (Android allows letters, digits, `_` and
     * `.`), so the separator needs no escaping; an entry that would not
     * round-trip is dropped rather than written. Budgets are keyed by package,
     * so a duplicate keeps the tighter cap, as [decode] does.
     */
    fun encode(list: List<AppBudget>): Set<String> =
        normalise(list.filter(::isValid)).mapTo(LinkedHashSet()) {
            "${it.packageName}$SEP${it.capBytes}$SEP${if (it.block) 1 else 0}"
        }

    /**
     * The budgets in a stored set, sorted by package. Tolerant by design: the
     * set outlives app versions and a malformed entry must cost that one entry,
     * not every budget. Two entries for one package — a stored set has no
     * order to say which is newer — resolve to the smaller cap, the one that
     * cannot let an app overrun what the user asked for.
     */
    fun decode(set: Set<String>): List<AppBudget> =
        normalise(set.mapNotNull(::decodeOne))

    /**
     * Budgets at or over their cap, with the bytes used. Reaching the cap
     * exactly counts: a budget is "up to N", and a blocking budget that let
     * the app keep going at exactly N would never fire for a cap the usage
     * lands on. An app with no usage entry has used nothing.
     */
    fun breaches(budgets: List<AppBudget>, usedByPackage: Map<String, Long>): List<Pair<AppBudget, Long>> =
        budgets.mapNotNull { b ->
            val used = usedByPackage[b.packageName] ?: 0L
            if (used >= b.capBytes) b to used else null
        }

    private fun decodeOne(s: String): AppBudget? {
        val parts = s.split(SEP)
        if (parts.size != 3) return null
        val cap = parts[1].toLongOrNull() ?: return null
        val block = when (parts[2]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        return AppBudget(parts[0], cap, block).takeIf(::isValid)
    }

    /** A zero cap is not an allowance — blocking an app outright is the firewall's job. */
    private fun isValid(b: AppBudget): Boolean =
        b.capBytes > 0 && b.packageName.isNotBlank() &&
            b.packageName.none { it == SEP || it.isWhitespace() }

    private fun normalise(list: List<AppBudget>): List<AppBudget> =
        list.groupBy { it.packageName }
            .map { (_, same) -> same.minWith(compareBy<AppBudget> { it.capBytes }.thenByDescending { it.block }) }
            .sortedBy { it.packageName }
}
