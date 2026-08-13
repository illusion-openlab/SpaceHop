package tech.illusion.spacehop.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighScoreStoreTest {

    @Test
    fun isNewRecord_noPriorRecord_anyScoreIsNewRecord() {
        assertTrue(HighScoreStore.isNewRecord(current = null, candidate = 0))
    }

    @Test
    fun isNewRecord_strictlyHigherThanCurrent_isTrue() {
        assertTrue(HighScoreStore.isNewRecord(current = 10, candidate = 11))
    }

    @Test
    fun isNewRecord_equalOrLowerThanCurrent_isFalse() {
        assertFalse(HighScoreStore.isNewRecord(current = 10, candidate = 10))
        assertFalse(HighScoreStore.isNewRecord(current = 10, candidate = 9))
    }
}
