package io.github.jrtutts.looseleaf.modules.pages

/** Page ranges as people type them: "1-3, 5, 8-". Page numbers are 1-based; indices returned are 0-based. */
object PageRanges {
    /** Returns the page indices in the order given, or null if [text] is not a valid range list for [pageCount] pages. */
    fun parse(text: String, pageCount: Int): List<Int>? {
        val out = LinkedHashSet<Int>()
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        for (part in parts) {
            val m = Regex("""^(\d+)?\s*(-|–)?\s*(\d+)?$""").matchEntire(part) ?: return null
            val a = m.groupValues[1].toIntOrNull()
            val dash = m.groupValues[2].isNotEmpty()
            val b = m.groupValues[3].toIntOrNull()
            val from = a ?: if (dash) 1 else return null
            val to = if (!dash) from else b ?: pageCount
            if (from < 1 || to > pageCount || from > to) return null
            for (p in from..to) out += p - 1
        }
        return out.toList()
    }

    /** The shortest text for a set of page indices: [0, 1, 2, 4] becomes "1-3, 5". */
    fun format(indices: Collection<Int>): String {
        val sorted = indices.sorted()
        val parts = ArrayList<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (i == j) "${sorted[i] + 1}" else "${sorted[i] + 1}-${sorted[j] + 1}"
            i = j + 1
        }
        return parts.joinToString(", ")
    }
}
