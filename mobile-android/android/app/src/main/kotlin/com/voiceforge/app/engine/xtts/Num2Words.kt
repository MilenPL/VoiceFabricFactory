package com.voiceforge.app.engine.xtts

import kotlin.math.abs
import kotlin.math.pow

/**
 * Kotlin port of the `num2words` code paths exercised by
 * `TTS.tts.layers.xtts.tokenizer.multilingual_cleaners` — i.e. exactly what the
 * Linux/Python app produces for a given input:
 *
 *  - Polish (`pl`): `lang_PL.Num2Word_PL` — cardinal (incl. the string-based
 *    float path with "przecinek"), ordinals and currency (EUR/USD/PLN — GBP is
 *    intentionally NOT implemented, matching `lang_PL.CURRENCY_FORMS`; the
 *    tokenizer's try/except then skips ALL currency expansion — see
 *    `expandNumbers` in [XttsTokenizer]).
 *  - English (`en`): `lang_EN.Num2Word_EN` on top of `Num2Word_Base`
 *    splitnum/clean/merge — ported as the same ordered-cards algorithm.
 *
 * Other XTTS languages are NOT ported here (num2words ships ~17 hand-written
 * language modules); their digit runs are left untouched by the tokenizer —
 * see the TODO(owner) in [XttsTokenizer.expandNumbers].
 *
 * Validated against the installed num2words via the vectors.json JVM unit test.
 * Pure Kotlin (no android.* imports) so JVM unit tests can run it.
 */
object Num2Words {

    // ===================================================================== pl
    // Exact tables from num2words/lang_PL.py.

    private const val PL_ZERO = "zero"
    private val PL_ONES = arrayOf(
        "", "jeden", "dwa", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem", "dziewięć",
    )
    private val PL_TENS = arrayOf(
        "dziesięć", "jedenaście", "dwanaście", "trzynaście", "czternaście", "piętnaście",
        "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście",
    )
    private val PL_TWENTIES = arrayOf(
        "", "", "dwadzieścia", "trzydzieści", "czterdzieści", "pięćdziesiąt",
        "sześćdziesiąt", "siedemdziesiąt", "osiemdziesiąt", "dziewięćdziesiąt",
    )
    private val PL_HUNDREDS = arrayOf(
        "", "sto", "dwieście", "trzysta", "czterysta", "pięćset", "sześćset",
        "siedemset", "osiemset", "dziewięćset",
    )
    // index 0 unused; entries are "level0|level1" (HUNDREDS_ORDINALS n3 → 1..9)
    private val PL_HUNDREDS_ORD = arrayOf(
        "", "setny|stu", "dwusetny|dwustu", "trzysetny|trzystu", "czterysetny|czterystu",
        "pięćsetny|pięcset", "sześćsetny|sześćset", "siedemsetny|siedemset",
        "osiemsetny|ośiemset", "dziewięćsetny|dziewięćset",
    )
    // ONES_ORDINALS: index = 0..19 (lastTwo)
    private val PL_ONES_ORD = arrayOf(
        "", "pierwszy|pierwszo", "drugi|dwu", "trzeci|trzy", "czwarty|cztero",
        "piąty|pięcio", "szósty|sześcio", "siódmy|siedmio", "ósmy|ośmio",
        "dziewiąty|dziewięcio", "dziesiąty|dziesięcio", "jedenasty|jedenasto",
        "dwunasty|dwunasto", "trzynasty|trzynasto", "czternasty|czternasto",
        "piętnasty|piętnasto", "szesnasty|szesnasto", "siedemnasty|siedemnasto",
        "osiemnasty|osiemnasto", "dziewiętnasty|dziewiętnasto",
    )
    // TWENTIES_ORDINALS: index = tens digit 0..9
    private val PL_TWENTIES_ORD = arrayOf(
        "", "", "dwudziesty|dwudziesto", "trzydziesty|trzydiesto", "czterdziesty|czterdziesto",
        "pięćdziesiąty|pięćdziesięcio", "sześćdziesiąty|sześćdziesięcio",
        "siedemdziesiąty|siedemdziesięcio", "osiemdziesiąty|osiemdziesięcio",
        "dziewięćdziesiąty|dziewięćdziesięio".replace("dziewięćdziesięio", "dziewięćdziesięcio"),
    )
    private val PL_PREFIX_ORD = arrayOf("", "tysięczny", "milionowy", "milairdowy") // source spelling kept

    /** lang_PL THOUSANDS: [0]=10^3, then ("lion","liard") pairs over prefixes. */
    private val PL_THOUSANDS: Array<Triple<String, String, String>> = run {
        val list = ArrayList<Triple<String, String, String>>(42)
        list.add(Triple("tysiąc", "tysiące", "tysięcy"))
        for (p in arrayOf("mi", "bi", "try", "kwadry", "kwinty", "seksty", "septy", "okty", "nony", "decy")) {
            for (s in arrayOf("lion", "liard")) {
                val name = p + s
                list.add(Triple(name, name + "y", name + "ów"))
            }
        }
        list.toTypedArray()
    }

    /** `utils.get_digits`: reversed(('%03d' % n)[-3:]) → (ones, tens, hundreds). */
    private fun plGetDigits(n: Int): Triple<Int, Int, Int> {
        val s = String.format(java.util.Locale.ROOT, "%03d", n)
        return Triple(s[2] - '0', s[1] - '0', s[0] - '0')
    }

    /** `utils.splitbyx(str(n), 3)` — groups of 3 digits, left remainder first. */
    private fun splitBy3(s: String): List<Int> {
        if (s.length <= 3) return listOf(s.toInt())
        val out = ArrayList<Int>()
        val start = s.length % 3
        if (start > 0) out.add(s.substring(0, start).toInt())
        var i = start
        while (i < s.length) {
            out.add(s.substring(i, i + 3).toInt())
            i += 3
        }
        return out
    }

    /** lang_PL.pluralize — the Polish 3-form rule. */
    private fun plPick(forms: List<String>, n: Int): String = when {
        n == 1 -> forms[0]
        n % 10 in 2..4 && (n % 100 < 10 || n % 100 > 20) -> forms[1]
        else -> forms[2]
    }

    /** lang_PL._int2word. */
    private fun plInt2word(n: Long): String {
        if (n == 0L) return PL_ZERO
        val words = ArrayList<String>()
        val chunks = splitBy3(n.toString())
        var i = chunks.size
        for (x in chunks) {
            i--
            if (x == 0) continue
            val (n1, n2, n3) = plGetDigits(x)
            if (n3 > 0) words.add(PL_HUNDREDS[n3])
            if (n2 > 1) words.add(PL_TWENTIES[n2])
            if (n2 == 1) words.add(PL_TENS[n1])
            else if (n1 > 0 && !(i > 0 && x == 1)) words.add(PL_ONES[n1])
            if (i > 0) words.add(plPick3(PL_THOUSANDS[i - 1], x))
        }
        return words.joinToString(" ")
    }

    private fun plPick3(forms: Triple<String, String, String>, n: Int): String = when {
        n == 1 -> forms.first
        n % 10 in 2..4 && (n % 100 < 10 || n % 100 > 20) -> forms.second
        else -> forms.third
    }

    /** lang_PL.to_cardinal — exact string-split float path ("… przecinek …"). */
    fun plCardinal(value: Double): String {
        val n = pyStr(value)
        return if (n.contains('.')) {
            val left = n.substringBefore('.')
            val right = n.substringAfter('.')
            val leadingZeros = right.length - right.trimStart('0').length
            val decimalPart = (PL_ZERO + " ").repeat(leadingZeros) + plInt2word(right.toLong())
            "${plInt2word(left.toLong())} przecinek $decimalPart"
        } else {
            plInt2word(n.toLong())
        }
    }

    fun plCardinalInt(value: Long): String = plInt2word(value)

    /** lang_PL.to_ordinal — exact fragment/level algorithm incl. prefixes_ordinal. */
    fun plOrdinal(number: Long): String {
        require(number >= 0) { "pl ordinal of negatives not supported" }
        if (number == 0L) return "zerowy" // lang_PL IndexErrors on 0; defensive guard (Android must not crash)
        var frags = splitBy3(number.toString())
        var level = 0
        var last = frags.last()
        while (last == 0 && frags.size > 1) {
            level++
            frags = frags.subList(0, frags.size - 1)
            last = frags.last()
        }
        if (last == 0) return PL_ZERO // number == 0 handled above; unreachable otherwise
        val words = ArrayList<String>()
        if (frags.size > 1) {
            words.add(plInt2word(number - last * 1000.0.pow(level).toLong()))
        }
        plLastFragmentToOrdinal(last, level, words)
        var output = words.joinToString(" ")
        if (last == 1 && level > 0 && output.isNotEmpty()) output += " "
        if (level > 0) output += PL_PREFIX_ORD.getOrElse(level) { "" } // >3 not reachable for Long inputs
        return output
    }

    private fun plLastFragmentToOrdinal(last: Int, level: Int, words: MutableList<String>) {
        val (n1, n2, n3) = plGetDigits(last)
        val lastTwo = n2 * 10 + n1
        when {
            lastTwo == 0 -> words.add(plOrd(PL_HUNDREDS_ORD[n3], level))
            level == 1 && last == 1 -> return
            lastTwo < 20 -> {
                if (n3 > 0) words.add(PL_HUNDREDS[n3])
                words.add(plOrd(PL_ONES_ORD[lastTwo], level))
            }
            lastTwo % 10 == 0 -> {
                if (n3 > 0) words.add(PL_HUNDREDS[n3])
                words.add(plOrd(PL_TWENTIES_ORD[n2], level))
            }
            else -> {
                if (n3 > 0) words.add(PL_HUNDREDS[n3])
                words.add(plOrd(PL_TWENTIES_ORD[n2], 0))
                words.add(plOrd(PL_ONES_ORD[n1], 0))
            }
        }
    }

    /** Picks HUNDREDS_ORDINALS[n][level] from "level0|level1" pairs. */
    private fun plOrd(pair: String, level: Int): String {
        val parts = pair.split("|")
        return if (level == 1 && parts.size > 1) parts[1] else parts[0]
    }

    // ===================================================================== en

    /**
     * `Num2Word_EN` cards in EXACT insertion order (set_high → set_mid →
     * set_low) as required by `Num2Word_Base.splitnum`. Only powers reachable
     * with Long are materialised (10^6..10^18); higher cardinals fall back
     * through split division anyway (num2words' "illion" list only adds more).
     */
    private val enCards: LinkedHashMap<Long, String> = run {
        val m = LinkedHashMap<Long, String>()
        for ((n, w) in listOf(
            Triple(18, "quintillion", ""),
            Triple(15, "quadrillion", ""),
            Triple(12, "trillion", ""),
            Triple(9, "billion", ""),
            Triple(6, "million", ""),
        )) {
            m[10.0.pow(n).toLong()] = w
        }
        // set_mid_numwords — insertion order of lang_EN.mid_numwords
        m[1000] = "thousand"
        m[100] = "hundred"
        m[90] = "ninety"; m[80] = "eighty"; m[70] = "seventy"; m[60] = "sixty"
        m[50] = "fifty"; m[40] = "forty"; m[30] = "thirty"
        // set_low_numwords — zip(low_numwords, range(len-1, -1, -1)) → keys ascending
        val low = listOf(
            "twenty", "nineteen", "eighteen", "seventeen", "sixteen", "fifteen",
            "fourteen", "thirteen", "twelve", "eleven", "ten", "nine", "eight",
            "seven", "six", "five", "four", "three", "two", "one", "zero",
        )
        for ((i, w) in low.withIndex()) m[(low.size - 1 - i).toLong()] = w
        m
    }

    private fun enMerge(l: Pair<String, Long>, r: Pair<String, Long>): Pair<String, Long> {
        val (ltext, lnum) = l
        val (rtext, rnum) = r
        return when {
            lnum == 1L && rnum < 100 -> Pair(rtext, rnum)
            lnum < 100 && rnum < 100 && lnum > rnum -> Pair("$ltext-$rtext", lnum + rnum)
            lnum >= 100 && rnum < 100 -> Pair("$ltext and $rtext", lnum + rnum)
            rnum > lnum -> Pair("$ltext $rtext", lnum * rnum)
            else -> Pair("$ltext, $rtext", lnum + rnum)
        }
    }

    /** `Num2Word_Base.splitnum` over the ordered cards (nested lists of pairs). */
    private fun enSplitnum(value: Long): List<Any> { // elements: Pair<String,Long> | List<Any>
        for (elem in enCards.keys) {
            if (elem > value) continue
            if (value == 0L) {
                return arrayListOf<Any>(Pair(enCards[1]!!, 1L), Pair(enCards[0]!!, 0L))
            }
            val div = value / elem
            val mod = value % elem
            val out = ArrayList<Any>()
            if (div == 1L) {
                out.add(Pair(enCards[1]!!, 1L))
            } else {
                out.add(enSplitnum(div))
            }
            out.add(Pair(enCards[elem]!!, elem))
            if (mod != 0L) out.add(enSplitnum(mod))
            return out
        }
        return arrayListOf(Pair(enCards[0]!!, 0L))
    }

    /** `Num2Word_Base.clean` — pairwise merge until one pair remains. */
    private fun enClean(v0: List<Any>): Pair<String, Long> {
        var v = v0
        while (v.size != 1) {
            val out = ArrayList<Any>()
            val left = v[0]
            val right = v[1]
            if (left is Pair<*, *> && right is Pair<*, *>) {
                @Suppress("UNCHECKED_CAST")
                out.add(enMerge(left as Pair<String, Long>, right as Pair<String, Long>))
                if (v.size > 2) out.add(v.subList(2, v.size))
            } else {
                for (elem in v) {
                    if (elem is List<*>) {
                        if (elem.size == 1) out.add(elem[0]!!)
                        else {
                            @Suppress("UNCHECKED_CAST")
                            out.add(enClean(elem as List<Any>))
                        }
                    } else out.add(elem)
                }
            }
            v = out
        }
        @Suppress("UNCHECKED_CAST")
        return v[0] as Pair<String, Long>
    }

    private val EN_ORDS = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "four" to "fourth",
        "five" to "fifth", "six" to "sixth", "seven" to "seventh", "eight" to "eighth",
        "nine" to "ninth", "ten" to "tenth", "eleven" to "eleventh", "twelve" to "twelfth",
    )

    /** `Num2Word_EN.to_cardinal` for integers. */
    fun enCardinal(value: Long): String {
        var v = value
        var out = ""
        if (v < 0) {
            v = -v
            out = "minus "
        }
        return out + enClean(enSplitnum(v)).first
    }

    /**
     * `num2words(x, lang="en")` for a float — mirrors the dispatcher:
     * integral floats go through the integer path (base asserts int(v)==v).
     */
    fun enNum2Words(value: Double): String =
        if (value == kotlin.math.floor(value) && abs(value) < 9.2e18) enCardinal(value.toLong())
        else enCardinalFloat(value)

    /** `Num2Word_Base.to_cardinal_float` (via float2tuple) for `en`. */
    fun enCardinalFloat(value: Double): String {
        val repr = pyStr(value)
        val pre = repr.substringBefore('.').toLong()
        val fracDigits = if (repr.contains('.')) repr.substringAfter('.') else ""
        val precision = fracDigits.length
        val parts = ArrayList<String>()
        parts.add(enCardinal(pre))
        if (precision > 0) {
            parts.add("point")
            for (ch in fracDigits) parts.add(enCardinal((ch - '0').toLong()))
        }
        return parts.joinToString(" ")
    }

    /** `Num2Word_EN.to_ordinal` (ints ≥ 0). */
    fun enOrdinal(value: Long): String {
        val outWords = enCardinal(value).split(" ").toMutableList()
        var lastWord = outWords.last().substringAfterLast('-').lowercase()
        lastWord = EN_ORDS[lastWord] ?: if (lastWord.endsWith("y")) {
            lastWord.dropLast(1) + "ieth"
        } else {
            lastWord + "th"
        }
        val lastParts = outWords.last().split("-").toMutableList()
        lastParts[lastParts.size - 1] = lastWord
        outWords[outWords.size - 1] = lastParts.joinToString("-")
        return outWords.joinToString(" ")
    }

    // =============================================================== currency

    class CurrencyForms(val major: List<String>, val minor: List<String>)

    /** `lang_PL.CURRENCY_FORMS` — NOTE: GBP deliberately absent (crash parity). */
    private val PL_CURRENCY = mapOf(
        "EUR" to CurrencyForms(listOf("euro", "euro", "euro"), listOf("cent", "centy", "centów")),
        "USD" to CurrencyForms(
            listOf("dolar amerykański", "dolary amerykańskie", "dolarów amerykańskich"),
            listOf("cent", "centy", "centów"),
        ),
        "PLN" to CurrencyForms(listOf("złoty", "złote", "złotych"), listOf("grosz", "grosze", "groszy")),
    )

    /** `lang_EU.CURRENCY_FORMS` (the subset the tokenizer can produce for `en`). */
    private val EN_CURRENCY = mapOf(
        "EUR" to CurrencyForms(listOf("euro"), listOf("cent", "cents")),
        "USD" to CurrencyForms(listOf("dollar", "dollars"), listOf("cent", "cents")),
        "GBP" to CurrencyForms(listOf("pound sterling", "pounds sterling"), listOf("penny", "pence")),
    )

    /** `currency.parse_currency_parts` for floats: quantize(ROUND_HALF_UP, .01). */
    private fun parseCurrencyParts(value: Double): Triple<Long, Int, Boolean> {
        val negative = value < 0
        val cents = kotlin.math.round(abs(value) * 100.0).toLong()
        return Triple(cents / 100, (cents % 100).toInt(), negative)
    }

    /** `Num2Word_PL.to_currency` — throws for unknown codes (e.g. GBP), like Python. */
    fun plCurrency(value: Double, currency: String): String {
        val forms = PL_CURRENCY[currency]
            ?: throw NotImplementedError("Currency code \"$currency\" not implemented")
        val (left, cents, negative) = parseCurrencyParts(value)
        val minus = if (negative) "minus " else ""
        return "$minus${plInt2word(left)} ${plPick(forms.major, left.toInt())}, " +
            "${plInt2word(cents.toLong())} ${plPick(forms.minor, cents)}"
    }

    /** `Num2Word_EU.to_currency` with the `en` pluralize rule (0 or 2+ → form 1). */
    fun enCurrency(value: Double, currency: String): String {
        val forms = EN_CURRENCY[currency]
            ?: throw NotImplementedError("Currency code \"$currency\" not implemented")
        val (left, cents, negative) = parseCurrencyParts(value)
        val minus = if (negative) "minus " else ""
        val majorForm = if (left == 1L) forms.major[0] else forms.major.getOrElse(1) { forms.major[0] }
        val minorForm = if (cents == 1) forms.minor[0] else forms.minor.getOrElse(1) { forms.minor[0] }
        return "$minus${enCardinal(left)} $majorForm, ${enCardinal(cents.toLong())} $minorForm"
    }

    // ================================================================= helpers

    /**
     * Python `str(float)` (repr) — shortest round-trip digits (same as Java's
     * Double.toString) but Python's plain-vs-exponent layout: plain for
     * -4 ≤ exp < 16, else `d.ddde+NN`. Needed because `lang_PL.to_cardinal`
     * string-splits `str(number)`.
     */
    fun pyStr(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val java = value.toString()
        if (!java.contains('E') && !java.contains('e')) return java
        val mantissa = java.substringBefore('E').substringBefore('e')
        val exp = java.substringAfter('E').substringAfter('e').toInt()
        val neg = mantissa.startsWith('-')
        val digits = mantissa.filter { it.isDigit() || it == '.' }
        val intPart = digits.substringBefore('.')
        var fracPart = digits.substringAfter('.', "")
        // Java's E-notation forces a fractional part ("1.0E7"); a lone "0" is
        // NOT a real fractional digit — Python would print '10000000.0' /
        // '0.0001', so drop it and let the expansion below re-add ".0" only
        // when the value is integral.
        if (fracPart == "0") fracPart = ""
        if (exp in -4 until 16) {
            val all = intPart + fracPart
            val pointPos = intPart.length + exp
            val sb = StringBuilder()
            if (neg) sb.append('-')
            when {
                pointPos <= 0 -> {
                    sb.append("0.")
                    repeat(-pointPos) { sb.append('0') }
                    sb.append(all)
                }
                pointPos >= all.length -> {
                    sb.append(all)
                    repeat(pointPos - all.length) { sb.append('0') }
                    sb.append(".0")
                }
                else -> {
                    sb.append(all, 0, pointPos)
                    sb.append('.')
                    sb.append(all, pointPos, all.length)
                }
            }
            return sb.toString()
        }
        val head = intPart.firstOrNull() ?: "0"
        val tail = (intPart.drop(1) + fracPart).trimEnd('0')
        val e = if (exp >= 0) "e+$exp" else "e$exp"
        return (if (neg) "-" else "") + head + (if (tail.isNotEmpty()) ".$tail" else "") + e
    }
}
