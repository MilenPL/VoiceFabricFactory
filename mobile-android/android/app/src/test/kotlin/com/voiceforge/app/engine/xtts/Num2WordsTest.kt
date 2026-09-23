package com.voiceforge.app.engine.xtts

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * num2words parity — every expected string below was produced by the INSTALLED
 * Python num2words (the exact code path `multilingual_cleaners` calls).
 */
class Num2WordsTest {

    private fun checkPl(value: Double, expected: String) {
        val got = Num2Words.plCardinal(value)
        assertEquals(expected, got, "plCardinal($value)")
    }

    private fun checkPlInt(value: Long, expected: String) {
        assertEquals(expected, Num2Words.plCardinalInt(value), "plCardinalInt($value)")
    }

    private fun checkEn(value: Double, expected: String) {
        assertEquals(expected, Num2Words.enNum2Words(value), "enNum2Words($value)")
    }

    @Test
    fun plCardinals() {
        val table = mapOf(
            0L to "zero", 1L to "jeden", 2L to "dwa", 5L to "pięć", 10L to "dziesięć",
            11L to "jedenaście", 15L to "piętnaście", 20L to "dwadzieścia",
            21L to "dwadzieścia jeden", 22L to "dwadzieścia dwa",
            30L to "trzydzieści", 31L to "trzydzieści jeden",
            40L to "czterdzieści", 41L to "czterdzieści jeden",
            50L to "pięćdziesiąt", 51L to "pięćdziesiąt jeden",
            99L to "dziewięćdziesiąt dziewięć", 100L to "sto", 101L to "sto jeden",
            110L to "sto dziesięć", 111L to "sto jedenaście",
            121L to "sto dwadzieścia jeden", 200L to "dwieście", 201L to "dwieście jeden",
            999L to "dziewięćset dziewięćdziesiąt dziewięć",
            1000L to "tysiąc", 1001L to "tysiąc jeden", 1100L to "tysiąc sto",
            1234L to "tysiąc dwieście trzydzieści cztery",
            2000L to "dwa tysiące", 2025L to "dwa tysiące dwadzieścia pięć",
            10000L to "dziesięć tysięcy", 100000L to "sto tysięcy",
            1000000L to "milion",
            1234567L to "milion dwieście trzydzieści cztery tysiące pięćset sześćdziesiąt siedem",
            999999L to "dziewięćset dziewięćdziesiąt dziewięć tysięcy dziewięćset dziewięćdziesiąt dziewięć",
        )
        for ((v, expected) in table) checkPlInt(v, expected)
    }

    @Test
    fun plFloats() {
        checkPl(12.5, "dwanaście przecinek pięć")
        checkPl(0.5, "zero przecinek pięć")
        checkPl(3.14, "trzy przecinek czternaście")
        checkPl(100000.5, "sto tysięcy przecinek pięć")
        checkPl(12.05, "dwanaście przecinek zero pięć")
        checkPl(0.07, "zero przecinek zero siedem")
        checkPl(7.0, "siedem przecinek zero zero")
    }

    @Test
    fun plOrdinals() {
        assertEquals("pierwszy", Num2Words.plOrdinal(1))
        assertEquals("drugi", Num2Words.plOrdinal(2))
        assertEquals("piąty", Num2Words.plOrdinal(5))
        assertEquals("dwudziesty pierwszy", Num2Words.plOrdinal(21))
        assertEquals("dwudziesty drugi", Num2Words.plOrdinal(22))
        assertEquals("sto pierwszy", Num2Words.plOrdinal(101))
        assertEquals("tysięczny", Num2Words.plOrdinal(1000))
    }

    @Test
    fun plCurrency() {
        assertEquals(
            "dwadzieścia dolarów amerykańskich, zero centów",
            Num2Words.plCurrency(20.0, "USD"),
        )
        assertEquals(
            "dwadzieścia dolarów amerykańskich, piętnaście centów",
            Num2Words.plCurrency(20.15, "USD"),
        )
        assertEquals("dwadzieścia euro, zero centów", Num2Words.plCurrency(20.0, "EUR"))
        assertEquals(
            "pięć dolarów amerykańskich, dziewięćdziesiąt dziewięć centów",
            Num2Words.plCurrency(5.99, "USD"),
        )
        assertEquals("zero euro, pięćdziesiąt centów", Num2Words.plCurrency(0.5, "EUR"))
        assertEquals("jeden złoty, zero groszy", Num2Words.plCurrency(1.0, "PLN"))
        // GBP is NOT implemented in lang_PL — Python raises → tokenizer skips all currency
        assertFailsWith<NotImplementedError> { Num2Words.plCurrency(20.15, "GBP") }
    }

    @Test
    fun enCardinals() {
        val table = mapOf(
            0L to "zero", 1L to "one", 5L to "five", 13L to "thirteen",
            20L to "twenty", 21L to "twenty-one", 50L to "fifty",
            99L to "ninety-nine", 100L to "one hundred",
            101L to "one hundred and one", 110L to "one hundred and ten",
            123L to "one hundred and twenty-three",
            999L to "nine hundred and ninety-nine",
            1000L to "one thousand", 1001L to "one thousand and one",
            1250L to "one thousand, two hundred and fifty",
            2025L to "two thousand and twenty-five",
            10000L to "ten thousand", 1000000L to "one million",
            1234567L to "one million, two hundred and thirty-four thousand, five hundred and sixty-seven",
        )
        for ((v, expected) in table) assertEquals(expected, Num2Words.enCardinal(v), "en($v)")
        checkEn(12.5, "twelve point five")
        checkEn(0.5, "zero point five")
        checkEn(100000.5, "one hundred thousand point five")
        checkEn(7.0, "seven")   // integral floats take the integer path (base assert)
    }

    @Test
    fun enOrdinals() {
        assertEquals("first", Num2Words.enOrdinal(1))
        assertEquals("second", Num2Words.enOrdinal(2))
        assertEquals("third", Num2Words.enOrdinal(3))
        assertEquals("fifth", Num2Words.enOrdinal(5))
        assertEquals("tenth", Num2Words.enOrdinal(10))
        assertEquals("eleventh", Num2Words.enOrdinal(11))
        assertEquals("twelfth", Num2Words.enOrdinal(12))
        assertEquals("twentieth", Num2Words.enOrdinal(20))
        assertEquals("twenty-first", Num2Words.enOrdinal(21))
        assertEquals("one hundredth", Num2Words.enOrdinal(100))
        assertEquals("one hundred and first", Num2Words.enOrdinal(101))
    }

    @Test
    fun enCurrency() {
        assertEquals("twenty dollars, zero cents", Num2Words.enCurrency(20.0, "USD"))
        assertEquals("twenty dollars, fifteen cents", Num2Words.enCurrency(20.15, "USD"))
        assertEquals("twenty pounds sterling, zero pence", Num2Words.enCurrency(20.0, "GBP"))
        assertEquals("zero euro, fifty cents", Num2Words.enCurrency(0.5, "EUR"))
    }

    @Test
    fun pyStrMatchesPython() {
        assertEquals("7.0", Num2Words.pyStr(7.0))
        assertEquals("12.05", Num2Words.pyStr(12.05))
        assertEquals("0.07", Num2Words.pyStr(0.07))
        assertEquals("100000.5", Num2Words.pyStr(100000.5))
        assertEquals("10000000.0", Num2Words.pyStr(1.0E7))   // python plain until 1e16
        assertEquals("1e+16", Num2Words.pyStr(1.0e16))        // exponent form from 1e16
        assertEquals("0.0001", Num2Words.pyStr(0.0001))       // Java "1.0E-4" forced ".0" dropped
    }

    @Test
    fun pythonRoundMatchesPython() {
        // int(round()) semantics — banker's rounding at exact .5
        assertEquals(52, GptSegmentGenerator.pythonRound(52.5))   // round-half-even → 52
        assertEquals(54, GptSegmentGenerator.pythonRound(53.5))   // → 54
        assertEquals(105, GptSegmentGenerator.pythonRound(105.0))
        assertEquals(105, GptSegmentGenerator.pythonRound(104.9999))
        assertEquals(8, GptSegmentGenerator.pythonRound(7.5))
        assertEquals(2, GptSegmentGenerator.pythonRound(2.5))
    }
}
