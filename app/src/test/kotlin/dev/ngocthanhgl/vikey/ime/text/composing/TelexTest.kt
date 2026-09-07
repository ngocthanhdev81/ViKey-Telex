package dev.ngocthanhgl.vikey.ime.text.composing

import org.junit.Test
import org.junit.Assert.assertEquals

class TelexTest {
    private val telex = AlgorithmicTelex()

    private fun simulate(sequence: String): String {
        var current = ""
        for (char in sequence) {
            val (rm, replacement) = telex.getActions(current, char.toString())
            current = current.dropLast(rm) + replacement
        }
        return current
    }

    @Test
    fun testBasicVowels() {
        assertEquals("â", simulate("aa"))
        assertEquals("ă", simulate("aw"))
        assertEquals("ê", simulate("ee"))
        assertEquals("ô", simulate("oo"))
        assertEquals("ơ", simulate("ow"))
        assertEquals("ư", simulate("uw"))
        assertEquals("đ", simulate("dd"))
    }

    @Test
    fun testComplexVowels() {
        assertEquals("ươ", simulate("uow"))
    }

    @Test
    fun testTones() {
        assertEquals("á", simulate("as"))
        assertEquals("à", simulate("af"))
        assertEquals("ả", simulate("ar"))
        assertEquals("ã", simulate("ax"))
        assertEquals("ạ", simulate("aj"))
    }

    @Test
    fun testTonesOnClusters() {
        assertEquals("ướ", simulate("uows"))
        assertEquals("ứ", simulate("ws"))
    }

    @Test
    fun testToneCancellation() {
        assertEquals("a", simulate("asz"))
        assertEquals("Tại", simulate("Taji"))
    }

    @Test
    fun testCaseSensitivity() {
        assertEquals("Â", simulate("AA"))
        assertEquals("Ă", simulate("AW"))
        assertEquals("W", simulate("WW")) // Standalone W then W undoes to W
    }

    @Test
    fun testSpaceAndTones() {
        assertEquals("Tại s", simulate("Taji s"))
        assertEquals("Tại z", simulate("Taji z"))
    }

    @Test
    fun testDoubleW() {
        assertEquals("ư", simulate("w"))
        assertEquals("w", simulate("ww"))
        // After a space the key goes through raw (getActions short-circuit),
        // so word-initial w does NOT become ư here (known quirk, follow-up)
        assertEquals("ư w", simulate("w" + " " + "w"))
    }

    @Test
    fun testStandaloneW() {
        assertEquals("ư", simulate("w"))
        assertEquals("kư", simulate("kw"))
        assertEquals("ă", simulate("aw"))
    }

    @Test
    fun testToneCancelMultiChar() {
        assertEquals("floris", simulate("florriss"))
    }

    @Test
    fun testDoubleWCancelMultiChar() {
        // "pơlk" is not a Vietnamese rhyme, so the scan refuses and w falls
        // back to standalone-ư (Unikey: "polkw" → "polkư")
        assertEquals("polkư", simulate("polkww"))
        // Pressing w again undoes the appended ư (toggle pair)
        assertEquals("polkw", simulate("polkwww"))
    }

    @Test
    fun testDistantVowelShortcuts() {
        assertEquals("tới", simulate("toiws"))
        assertEquals("tơi", simulate("toiw"))
        assertEquals("lớp", simulate("lopws"))
        assertEquals("lôi", simulate("loio"))
        assertEquals("tới", simulate("towsi")) // adjacent order regression
        // Scan refusal: "tâi"/"lêi" are not Vietnamese rhymes, so the
        // modifier is kept literal (Unikey-verified)
        assertEquals("taia", simulate("taia"))
        assertEquals("leie", simulate("leie"))
        assertEquals("abandona", simulate("abandona"))
        assertEquals("TƠI", simulate("TOIW"))
    }

    @Test
    fun testUnikeyToneEnglish() {
        assertEquals("Lý", simulate("Lys"))
        assertEquals("lý", simulate("lys"))
        assertEquals("lỳ", simulate("lyf"))
        // No rule matched: rightmost 'y' takes the tone (Unikey-verified)
        assertEquals("familý", simulate("familys"))
        assertEquals("quicklý", simulate("quicklys"))
        assertEquals("Ýe", simulate("Yes"))
        // No 'y': last vowel takes the tone
        assertEquals("mailbõ", simulate("mailbox"))
        assertEquals("abandón", simulate("abandons"))
        assertEquals("diét", simulate("diets"))
        assertEquals("intẻ", simulate("inter"))
        assertEquals("ốk", simulate("oks"))
        // Invalid tail coda → tone key stays literal (Unikey-verified)
        assertEquals("edicts", simulate("edicts"))
        assertEquals("tests", simulate("tests"))
    }

    @Test
    fun testUnikeyNoPromotions() {
        // Unikey-verified: the tone key NEVER changes the mark — no
        // ie/ye/uoi/uye/uon-style promotions exist. Position only.
        assertEquals("muói", simulate("muois"))
        assertEquals("khuýen", simulate("khuyens"))
        assertEquals("vuòn", simulate("vuonf"))
        assertEquals("muón", simulate("muons"))
        assertEquals("muọn", simulate("muonj"))
        assertEquals("tién", simulate("tiens"))
        assertEquals("býe", simulate("byes"))
        assertEquals("lòng", simulate("longf"))
        // Unikey-verified position rule: "uoi"→middle o, not last i
        // (field bug: "muois" gave "muoí" instead of "muói")
        assertEquals("tuói", simulate("tuois"))
    }

    @Test
    fun testToneMigrationToMain() {
        // Unikey-verified: a tone stranded off the main vowel migrates to it
        // once the true main appears ("khuýen"+e → "khuyến", not "khuýên")
        assertEquals("khuyến", simulate("khuyense"))
        assertEquals("myế", simulate("myee"))
        // Tone-transfer through adjacent doubling ("thué"+e → "thuế")
        assertEquals("thuế", simulate("thuese"))
        // Derived (same transfer mechanism): "mó"+o keeps its tone
        assertEquals("mố", simulate("móo"))
    }

    @Test
    fun testDistantToggleFixedPoint() {
        // Toggle: revert only undoes the last EFFECTIVE keypress.
        // "lôi"+o → "loio" (4th o converted o→ô, 5th o undoes it)
        assertEquals("loio", simulate("loioo"))
        // "tâi"+a → "tâia" ("tai"+a refused, nothing to undo → append).
        // NOTE: "tâi" itself needs doubling ("taai"), since "taia" refuses.
        assertEquals("tâia", simulate("taaia"))
        // Contrast pair proving strict-validity decides, not position:
        // "ây" is a real rhyme (convert), "âi" is not (literal)
        assertEquals("tây", simulate("taya"))
        assertEquals("taia", simulate("taia"))
    }

    @Test
    fun testDdAdjacentOnlyUnikey() {
        assertEquals("đ", simulate("dd"))
        assertEquals("Đ", simulate("Dd"))
        assertEquals("đau", simulate("ddau"))
        // The new 'd' pairs only with an ADJACENT 'd', never a distant leading one
        assertEquals("duckđ", simulate("duckdd"))
        // Pressing 'd' again undoes đ back to dd
        assertEquals("duckdd", simulate("duckddd"))
    }

    @Test
    fun testWTargetsNearestVowelUnikey() {
        // w modifies the immediately preceding vowel: hoaw → hoă, not hơa
        assertEquals("hoă", simulate("hoaw"))
        assertEquals("hoặc", simulate("hoawjc"))
        assertEquals("hoặc", simulate("hoacj"))
        assertEquals("oắt", simulate("oawts"))
        // Onset-less buffers must also pass validity ("oa"+w→"oă",
        // field bug: splitRhymeBase required an onset)
        assertEquals("oă", simulate("oaw"))
        // Validity still outranks proximity (order-independence preserved)
        assertEquals("mưa", simulate("muaw"))
        // w-undo still reachable
        assertEquals("tuw", simulate("tuww"))
    }
}
