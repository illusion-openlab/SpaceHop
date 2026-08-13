package tech.illusion.spacehop.game

import android.content.Context

/** Local-only best-score persistence — one number, no cloud, no account (design doc §一:
 * 技术 Demo，不含运营内容). [isNewRecord] is split out as a pure function so the "does this
 * beat the record" rule is unit-testable without an Android `Context`. */
class HighScoreStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Null means no run has ever finished — distinct from a first run scoring 0. */
    fun bestScore(): Int? = if (prefs.contains(KEY_BEST_SCORE)) prefs.getInt(KEY_BEST_SCORE, 0) else null

    /** Returns true if [score] set a new record. */
    fun submitScore(score: Int): Boolean {
        if (!isNewRecord(bestScore(), score)) return false
        prefs.edit().putInt(KEY_BEST_SCORE, score).apply()
        return true
    }

    companion object {
        private const val PREFS_NAME = "space_hop_progress"
        private const val KEY_BEST_SCORE = "best_score"

        fun isNewRecord(current: Int?, candidate: Int): Boolean = current == null || candidate > current
    }
}
