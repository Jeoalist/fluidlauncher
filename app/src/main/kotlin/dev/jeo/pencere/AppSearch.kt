package dev.jeo.pencere

private val TURKISH_FOLDS = mapOf('ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u')
private const val NO_MATCH = Int.MAX_VALUE

/** Lets "cicek" match "Çiçeksepeti". */
internal fun searchKey(text: String): String = text.lowercase(TURKISH).map { TURKISH_FOLDS[it] ?: it }.joinToString("")

private fun matchRank(label: String, needle: String): Int = when {
    label.startsWith(needle) -> 0
    " $needle" in label -> 1
    needle in label -> 2
    else -> NO_MATCH
}

/** Apps whose names contain [query], names starting with it first. An empty query keeps every app. */
fun matchApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val needle = searchKey(query.trim())
    if (needle.isEmpty()) return apps
    return apps
        .map { it to matchRank(searchKey(it.label), needle) }
        .filter { it.second != NO_MATCH }
        .sortedBy { it.second }
        .map { it.first }
}
