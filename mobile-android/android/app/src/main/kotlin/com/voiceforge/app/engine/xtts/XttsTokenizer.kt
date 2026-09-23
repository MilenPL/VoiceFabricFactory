package com.voiceforge.app.engine.xtts

import com.voiceforge.app.engine.EngineException
import org.json.JSONObject
import java.io.File

/**
 * Kotlin port of `TTS.tts.layers.xtts.tokenizer.VoiceBpeTokenizer.encode`
 * running on the exported HF `tokenizer.json` (named vocab.json):
 *
 *   lang = lang.split("-")[0]
 *   txt  = multilingual_cleaners(txt, lang)          // preprocess_text
 *   txt  = f"[{lang}]{txt}".replace(" ", "[SPACE]")
 *   ids  = tokenizer.encode(txt).ids                 // HF `tokenizers` pipeline
 *
 * The HF `tokenizers` pipeline for this file is:
 *   normalizer = null
 *   added tokens split out first (longest match) — [STOP] [UNK] [SPACE] [pl] …
 *   pre_tokenizer = Whitespace  → regex `\w+|\S` (Unicode), gaps dropped
 *   model = BPE (vocab + merges order = rank, unk_token "[UNK]", no byte-fallback)
 *   post_processor = null
 *
 * Text normalization (`multilingual_cleaners`) is ported 1:1: quotes removal,
 * Turkish specials, lowercase, number expansion (num2words — see [Num2Words]),
 * abbreviation + symbol tables for every XTTS language, whitespace collapse.
 *
 * Languages whose Python cleaners depend on packages unavailable on Android
 * (zh → pypinyin, ja → cutlet, ko → ko_speech_tools) throw
 * [EngineException] instead of producing garbage.
 *
 * Pure Kotlin (no android.* imports) so the JVM unit tests can run it.
 */
class XttsTokenizer(vocabFile: File) {

    // ------------------------------------------------------------- vocab state
    private val pieceToId = HashMap<String, Int>(8192)
    private val addedTokenId = HashMap<String, Int>(32)
    private val mergeRank = HashMap<String, Int>(16384)
    private val unkId: Int
    private val addedTokensByLength: List<String>

    init {
        val root = JSONObject(vocabFile.readText())
        val model = root.getJSONObject("model")
        val vocab = model.getJSONObject("vocab")
        for (key in vocab.keys()) pieceToId[key] = vocab.getInt(key)
        val merges = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            val m = merges.getString(i)
            mergeRank[m] = i
        }
        var unk = model.optString("unk_token", "[UNK]")
        val added = root.optJSONArray("added_tokens")
        if (added != null) {
            for (i in 0 until added.length()) {
                val o = added.getJSONObject(i)
                val content = o.getString("content")
                val id = o.getInt("id")
                addedTokenId[content] = id
                pieceToId.putIfAbsent(content, id)
            }
        }
        unkId = pieceToId[unk] ?: 1
        addedTokensByLength = addedTokenId.keys.sortedByDescending { it.length }
    }

    val vocabSize: Int get() = pieceToId.size

    // =============================================================== encode

    fun encode(txt: String, lang: String): List<Int> {
        val baseLang = lang.split("-")[0]
        // check_input_length(txt, baseLang) → Python only logs a warning; no-op here.
        val cleaned = preprocessText(txt, baseLang)
        val langTag = if (baseLang == "zh") "zh-cn" else baseLang
        val tagged = "[$langTag]$cleaned".replace(" ", "[SPACE]")
        return bpeEncode(tagged)
    }

    // -------------------------------------------------------- preprocess_text

    internal fun preprocessText(txt: String, lang: String): String = when (lang) {
        "ar", "cs", "de", "en", "es", "fr", "hi", "hu", "it", "nl", "pl", "pt", "ru", "tr" ->
            multilingualCleaners(txt, lang)
        // zh/ja/ko need pypinyin / cutlet / ko_speech_tools on the Python side —
        // not available on-device; fail loudly instead of emitting garbage.
        "zh" -> throw EngineException("Chinese text normalization (pypinyin) is not available on-device yet")
        "ja" -> throw EngineException("Japanese text normalization (cutlet) is not available on-device yet")
        "ko" -> throw EngineException("Korean text normalization (ko_speech_tools) is not available on-device yet")
        else -> throw EngineException("Language '$lang' is not supported")
    }

    /** `TTS.tts.utils.text.cleaners` + `multilingual_cleaners`, exact order. */
    private fun multilingualCleaners(text: String, lang: String): String {
        var t = text.replace("\"", "")
        if (lang == "tr") {
            t = t.replace("İ", "i").replace("Ö", "ö").replace("Ü", "ü")
        }
        t = t.lowercase()                       // cleaners.lowercase = str.lower
        t = expandNumbers(t, lang)
        t = expandAbbreviations(t, lang)
        t = expandSymbols(t, lang)
        t = collapseWhitespace(t)
        return t
    }

    private fun collapseWhitespace(text: String): String =
        UNICODE_WS.replace(text, " ").trim { it.isWhitespace() }

    // ------------------------------------------------------------ expand nums

    /**
     * `expand_numbers_multilingual`. Fully ported for `pl` and `en` (the
     * languages validated against the Python reference).
     *
     * TODO(owner): num2words has per-language modules for the remaining XTTS
     * languages (de/fr/es/…); digit runs for those are currently left as-is —
     * safer than inserting wrong-language number words. Extend [Num2Words]
     * when a language needs it.
     */
    private fun expandNumbers(textIn: String, lang: String): String {
        if (lang != "pl" && lang != "en") return textIn // TODO(owner) — see KDoc
        var text = textIn
        // thousand separators (Python: en|ru use \b\d{1,3}(,\d{3})*(\.\d+)?\b,
        // everyone else \b\d{1,3}(.\d{3})*(\,\d+)?\b with the UNESCAPED '.')
        if (lang == "en") {
            text = COMMA_NUM.replace(text) { m -> m.value.replace(",", "") }
        } else {
            text = DOT_NUM.replace(text) { m -> m.value.replace(".", "") }
        }
        // currencies — one try/except for GBP→USD→EUR (exact Python semantics:
        // an unsupported GBP *match* aborts the whole block for that language).
        // NOTE: Kotlin's NotImplementedError is an Error — catch Throwable to
        // mirror Python's bare `except:`.
        try {
            text = GBP_NUM.replace(text) { m -> currencyWords(m.value, "GBP", lang) }
            text = USD_NUM.replace(text) { m -> currencyWords(m.value, "USD", lang) }
            text = EUR_NUM.replace(text) { m -> currencyWords(m.value, "EUR", lang) }
        } catch (_: Throwable) {
            // Python: except: pass — currency expansion skipped entirely
        }
        // decimal point: ([0-9]+[.,][0-9]+) — skipped for lang "tr" in Python;
        // tr is not ported here anyway (returns above), pl/en both run it.
        text = DECIMAL_NUM.replace(text) { m ->
            val amount = m.groupValues[1].replace(",", ".")
            val d = amount.toDoubleOrNull()
            if (d == null) m.value else num2wordsDouble(d, lang)
        }
        // ordinals (per-language regex from tokenizer.py)
        val ordRe = ORDINAL_RE[lang]
        if (ordRe != null) {
            text = ordRe.replace(text) { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
                num2wordsOrdinal(n, lang)
            }
        }
        // plain integers
        text = NUMBER_RE.replace(text) { m ->
            val n = m.groupValues[0].toLongOrNull() ?: return@replace m.value
            num2wordsInt(n, lang)
        }
        return text
    }

    private fun num2wordsDouble(v: Double, lang: String): String = when (lang) {
        "pl" -> Num2Words.plCardinal(v)
        else -> Num2Words.enNum2Words(v)
    }

    private fun num2wordsInt(n: Long, lang: String): String = when (lang) {
        "pl" -> Num2Words.plCardinalInt(n)
        else -> Num2Words.enCardinal(n)
    }

    private fun num2wordsOrdinal(n: Long, lang: String): String = when (lang) {
        "pl" -> Num2Words.plOrdinal(n)
        else -> Num2Words.enOrdinal(n)
    }

    /** `num2words(float(amount), to="currency", currency=cur, lang=lang)`. */
    private fun currencyWords(match: String, currency: String, lang: String): String {
        val amount = match.replace(Regex("[^0-9.]"), "").replace(",", ".").toDoubleOrNull()
            ?: return match
        val full = if (lang == "pl") Num2Words.plCurrency(amount, currency)
        else Num2Words.enCurrency(amount, currency)
        // tokenizer._expand_currency: integer amounts drop the last ", <cents>"
        if (amount == kotlin.math.floor(amount)) {
            val idx = full.lastIndexOf(", ")
            return if (idx >= 0) full.substring(0, idx) else full
        }
        return full
    }

    // ------------------------------------------------------- abbrev + symbols

    private data class Rule(val regex: Regex, val replacement: String)

    private fun expandAbbreviations(textIn: String, lang: String): String {
        var text = textIn
        for (r in ABBREVIATIONS[lang] ?: return text) text = r.regex.replace(text, r.replacement)
        return text
    }

    private fun expandSymbols(textIn: String, lang: String): String {
        var text = textIn
        val list = SYMBOLS[lang] ?: return text.trim { it.isWhitespace() }   // Python: strip() after loop
        for (r in list) {
            text = r.regex.replace(text, r.replacement)
            text = text.replace("  ", " ")
        }
        return text.trim { it.isWhitespace() }                                // Python: .strip()
    }

    // ------------------------------------------------------------------- BPE

    /**
     * HF `Tokenizer.encode` for this model: split added tokens (longest match)
     * first, then per normal span: Whitespace pre-tokenizer (`\w+|\S`) and BPE
     * merges in `merges`-file order (min-rank first, all occurrences of the
     * chosen pair), unmapped symbols → [UNK].
     */
    fun bpeEncode(input: String): List<Int> {
        val ids = ArrayList<Int>(input.length)
        var i = 0
        val n = input.length
        val normal = StringBuilder()
        while (i < n) {
            val added = matchAdded(input, i)
            if (added != null) {
                flushNormal(normal, ids)
                ids.add(addedTokenId[added]!!)
                i += added.length
            } else {
                normal.append(input[i])
                i++
            }
        }
        flushNormal(normal, ids)
        return ids
    }

    private fun flushNormal(sb: StringBuilder, ids: MutableList<Int>) {
        if (sb.isEmpty()) return
        val span = sb.toString()
        sb.setLength(0)
        // pre_tokenizers.Whitespace: find(r"\w+|\S") — gaps (whitespace) dropped
        val matcher = WHITESPACE_SPLIT.find(span)
        var m = matcher
        while (m != null) {
            for (piece in bpe(m.value)) ids.add(pieceToId[piece] ?: unkId)
            m = m.next()
        }
    }

    private fun matchAdded(s: String, pos: Int): String? {
        for (candidate in addedTokensByLength) {   // longest first
            if (s.regionMatches(pos, candidate, 0, candidate.length)) return candidate
        }
        return null
    }

    /** Classic BPE: min-rank pair first, replace all its occurrences, repeat. */
    private fun bpe(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        var symbols = word.codePoints().mapToObj { String(Character.toChars(it)) }.toList()
        if (symbols.size == 1) return symbols
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestLeft = ""
            var bestRight = ""
            for (k in 0 until symbols.size - 1) {
                val rank = mergeRank[symbols[k] + " " + symbols[k + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestLeft = symbols[k]
                    bestRight = symbols[k + 1]
                }
            }
            if (bestRank == Int.MAX_VALUE) break
            val merged = ArrayList<String>(symbols.size)
            var k = 0
            while (k < symbols.size) {
                if (k + 1 < symbols.size && symbols[k] == bestLeft && symbols[k + 1] == bestRight) {
                    merged.add(bestLeft + bestRight)
                    k += 2
                } else {
                    merged.add(symbols[k])
                    k++
                }
            }
            symbols = merged
        }
        return symbols
    }

    // ------------------------------------------------------------- regex data

    companion object {
        // (?U) = Pattern.UNICODE_CHARACTER_CLASS (Kotlin's RegexOption lacks it)
        private val UNICODE_WS = Regex("(?U)\\s+")
        private val WHITESPACE_SPLIT = Regex("(?U)\\w+|\\S")

        // tokenizer.py number regexes (kept verbatim — including the unescaped
        // '.' in _dot_number_re, which is intentional for 1:1 behaviour)
        private val COMMA_NUM = Regex("""\b\d{1,3}(,\d{3})*(\.\d+)?\b""")
        private val DOT_NUM = Regex("""\b\d{1,3}(.\d{3})*(\,\d+)?\b""")
        private val DECIMAL_NUM = Regex("""([0-9]+[.,][0-9]+)""")
        private val NUMBER_RE = Regex("""[0-9]+""")
        private val GBP_NUM = Regex("""((£[0-9.,]*[0-9]+)|([0-9.,]*[0-9]+£))""")
        private val USD_NUM = Regex("""((\$[0-9.,]*[0-9]+)|([0-9.,]*[0-9]+\$))""")
        private val EUR_NUM = Regex("""(([0-9.,]*[0-9]+€)|((€[0-9.,]*[0-9]+)))""")

        /** `_ordinal_re` — only languages with a ported num2words. */
        private val ORDINAL_RE: Map<String, Regex> = mapOf(
            "en" to Regex("""([0-9]+)(st|nd|rd|th)"""),
            "pl" to Regex("""([0-9]+)(º|ª|st|nd|rd|th)"""),
        )

        private fun icase(p: String): Regex = Regex(p, RegexOption.IGNORE_CASE)

        // ---- _abbreviations_multilingual (all XTTS languages) ----
        private fun abbr(pairs: List<Pair<String, String>>): List<Rule> =
            pairs.map { Rule(icase(it.first), it.second) }

        private val ABBREVIATIONS: Map<String, List<Rule>> = mapOf(
            "en" to abbr(listOf(
                icasePair("\\bmrs\\.", "misess"), icasePair("\\bmr\\.", "mister"),
                icasePair("\\bdr\\.", "doctor"), icasePair("\\bst\\.", "saint"),
                icasePair("\\bco\\.", "company"), icasePair("\\bjr\\.", "junior"),
                icasePair("\\bmaj\\.", "major"), icasePair("\\bgen\\.", "general"),
                icasePair("\\bdrs\\.", "doctors"), icasePair("\\brev\\.", "reverend"),
                icasePair("\\blt\\.", "lieutenant"), icasePair("\\bhon\\.", "honorable"),
                icasePair("\\bsgt\\.", "sergeant"), icasePair("\\bcapt\\.", "captain"),
                icasePair("\\besq\\.", "esquire"), icasePair("\\bltd\\.", "limited"),
                icasePair("\\bcol\\.", "colonel"), icasePair("\\bft\\.", "fort"),
            )),
            "es" to abbr(listOf(
                icasePair("\\bsra\\.", "señora"), icasePair("\\bsr\\.", "señor"),
                icasePair("\\bdr\\.", "doctor"), icasePair("\\bdra\\.", "doctora"),
                icasePair("\\bst\\.", "santo"), icasePair("\\bco\\.", "compañía"),
                icasePair("\\bjr\\.", "junior"), icasePair("\\bltd\\.", "limitada"),
            )),
            "fr" to abbr(listOf(
                icasePair("\\bmme\\.", "madame"), icasePair("\\bmr\\.", "monsieur"),
                icasePair("\\bdr\\.", "docteur"), icasePair("\\bst\\.", "saint"),
                icasePair("\\bco\\.", "compagnie"), icasePair("\\bjr\\.", "junior"),
                icasePair("\\bltd\\.", "limitée"),
            )),
            "de" to abbr(listOf(
                icasePair("\\bfr\\.", "frau"), icasePair("\\bdr\\.", "doktor"),
                icasePair("\\bst\\.", "sankt"), icasePair("\\bco\\.", "firma"),
                icasePair("\\bjr\\.", "junior"),
            )),
            "pt" to abbr(listOf(
                icasePair("\\bsra\\.", "senhora"), icasePair("\\bsr\\.", "senhor"),
                icasePair("\\bdr\\.", "doutor"), icasePair("\\bdra\\.", "doutora"),
                icasePair("\\bst\\.", "santo"), icasePair("\\bco\\.", "companhia"),
                icasePair("\\bjr\\.", "júnior"), icasePair("\\bltd\\.", "limitada"),
            )),
            "it" to abbr(listOf(
                icasePair("\\bsig\\.", "signore"), icasePair("\\bdr\\.", "dottore"),
                icasePair("\\bst\\.", "santo"), icasePair("\\bco\\.", "compagnia"),
                icasePair("\\bjr\\.", "junior"), icasePair("\\bltd\\.", "limitata"),
            )),
            "pl" to abbr(listOf(
                icasePair("\\bp\\.", "pani"), icasePair("\\bm\\.", "pan"),
                icasePair("\\bdr\\.", "doktor"), icasePair("\\bsw\\.", "święty"),
                icasePair("\\bjr\\.", "junior"),
            )),
            "cs" to abbr(listOf(
                icasePair("\\bdr\\.", "doktor"), icasePair("\\bing\\.", "inženýr"),
                icasePair("\\bp\\.", "pan"),
            )),
            "ru" to abbr(listOf(
                // NOTE: this table matches \b WITHOUT a trailing dot (verbatim)
                icasePair("\\bг-жа", "госпожа"), icasePair("\\bг-н", "господин"),
                icasePair("\\bд-р", "доктор"),
            )),
            "nl" to abbr(listOf(
                icasePair("\\bdhr\\.", "de heer"), icasePair("\\bmevr\\.", "mevrouw"),
                icasePair("\\bdr\\.", "dokter"), icasePair("\\bjhr\\.", "jonkheer"),
            )),
            "tr" to abbr(listOf(
                icasePair("\\bb\\.", "bay"), icasePair("\\bbyk\\.", "büyük"),
                icasePair("\\bdr\\.", "doktor"),
            )),
            "hu" to abbr(listOf(
                icasePair("\\bdr\\.", "doktor"), icasePair("\\bb\\.", "bácsi"),
                icasePair("\\bnőv\\.", "nővér"),
            )),
            // ar / zh / ko / hi: empty tables in tokenizer.py
            "ar" to emptyList(), "zh" to emptyList(), "ko" to emptyList(), "hi" to emptyList(),
        )

        private fun icasePair(pattern: String, repl: String): Pair<String, String> =
            Pair(pattern, repl)

        // ---- _symbols_multilingual ----
        private fun syms(pairs: List<Pair<String, String>>): List<Rule> =
            pairs.map { Rule(Regex(Regex.escape(it.first), RegexOption.IGNORE_CASE), it.second) }

        private val SYMBOLS: Map<String, List<Rule>> = mapOf(
            "en" to syms(listOf("&" to " and ", "@" to " at ", "%" to " percent ", "#" to " hash ", "$" to " dollar ", "£" to " pound ", "°" to " degree ")),
            "es" to syms(listOf("&" to " y ", "@" to " arroba ", "%" to " por ciento ", "#" to " numeral ", "$" to " dolar ", "£" to " libra ", "°" to " grados ")),
            "fr" to syms(listOf("&" to " et ", "@" to " arobase ", "%" to " pour cent ", "#" to " dièse ", "$" to " dollar ", "£" to " livre ", "°" to " degrés ")),
            "de" to syms(listOf("&" to " und ", "@" to " at ", "%" to " prozent ", "#" to " raute ", "$" to " dollar ", "£" to " pfund ", "°" to " grad ")),
            "pt" to syms(listOf("&" to " e ", "@" to " arroba ", "%" to " por cento ", "#" to " cardinal ", "$" to " dólar ", "£" to " libra ", "°" to " graus ")),
            "it" to syms(listOf("&" to " e ", "@" to " chiocciola ", "%" to " per cento ", "#" to " cancelletto ", "$" to " dollaro ", "£" to " sterlina ", "°" to " gradi ")),
            "pl" to syms(listOf("&" to " i ", "@" to " małpa ", "%" to " procent ", "#" to " krzyżyk ", "$" to " dolar ", "£" to " funt ", "°" to " stopnie ")),
            "ar" to syms(listOf("&" to " و ", "@" to " على ", "%" to " في المئة ", "#" to " رقم ", "$" to " دولار ", "£" to " جنيه ", "°" to " درجة ")),
            "zh" to syms(listOf("&" to " 和 ", "@" to " 在 ", "%" to " 百分之 ", "#" to " 号 ", "$" to " 美元 ", "£" to " 英镑 ", "°" to " 度 ")),
            "cs" to syms(listOf("&" to " a ", "@" to " na ", "%" to " procento ", "#" to " krížok ", "$" to " dolár ", "£" to " libra ", "°" to " stupne ")),
            "ru" to syms(listOf("&" to " и ", "@" to " собака ", "%" to " процентов ", "#" to " номер ", "$" to " доллар ", "£" to " фунт ", "°" to " градусов ")),
            "nl" to syms(listOf("&" to " en ", "@" to " bij ", "%" to " procent ", "#" to " hekje ", "$" to " dollar ", "£" to " pond ", "°" to " graden ")),
            "tr" to syms(listOf("&" to " ve ", "@" to " virgül ", "%" to " yüzde ", "#" to " diyez ", "$" to " dolar ", "£" to " sterlin ", "°" to " derece ")),
            "hu" to syms(listOf("&" to " és ", "@" to " kukac ", "%" to " százalék ", "#" to " kettőskereszt ", "$" to " dollár ", "£" to " font ", "°" to " fok ")),
            "ko" to syms(listOf("&" to " 그리고 ", "@" to " 골뱅이 ", "%" to " 퍼센트 ", "#" to " 번호 ", "$" to " 달러 ", "£" to " 파운드 ", "°" to " 도 ")),
            "hi" to syms(listOf("&" to " और ", "@" to " ऐट दी रेट ", "%" to " प्रतिशत ", "#" to " हैश ", "$" to " डॉलर ", "£" to " पाउंड ", "°" to " डिग्री ")),
        )
    }
}
