package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test

class SpokenEchoesTest {
    @Test fun onlyTheActualPlaybackLanguageTextAndSpeechWindowMatch() {
        val receipts = SpokenEchoes()
        receipts.start("fr-FR", "Où est la gare ?", 3000)
        assertFalse(receipts.independent(Side.FRENCH, "Où est la", 3150))
        assertTrue(receipts.independent(Side.CHINESE, "两张票", 3150))
        assertTrue(receipts.independent(Side.FRENCH, "Le prochain train", 3150))
        assertFalse(receipts.independent(Side.FRENCH, "Le prochain train", 1000))
        receipts.finish(4500)
        assertTrue(receipts.repeated(Side.FRENCH, "Où est la gare.", 3400, 6900))
        assertFalse(receipts.repeated(Side.FRENCH, "Où est la gare ?", 5100, 6900))
        assertFalse(receipts.repeated(Side.CHINESE, "Où est la gare ?", 3400, 6900))
        assertFalse(receipts.repeated(Side.FRENCH, "Où est la grande gare ?", 3400, 6900))
        receipts.reset()
        assertFalse(receipts.repeated(Side.FRENCH, "Où est la gare ?", 3400, 6900))
    }
}
