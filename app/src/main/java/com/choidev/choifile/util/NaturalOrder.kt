package com.choidev.choifile.util

/**
 * "Natural" file-name order: runs of digits compare by their numeric value, so "2.jpg" sorts before "10.jpg"
 * and "ch1 p9" before "ch1 p10". Text compares case-insensitively. Works on arbitrarily long digit runs
 * (no overflow) and treats leading zeros as a tie-breaker only ("01" == "1" numerically, "1" first).
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        var zeroTie = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                if (zeroTie == 0) zeroTie = (i - si) - (j - sj) // fewer leading zeros first
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        if (rest != 0) return rest
        if (zeroTie != 0) return zeroTie
        return a.compareTo(b) // stable, total order for names that differ only in case
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

/** Sorts names (or paths) naturally; for paths compare the whole string so folders inside archives group together. */
fun <T> List<T>.sortedNaturally(key: (T) -> String): List<T> = sortedWith { x, y -> NaturalOrder.compare(key(x), key(y)) }
