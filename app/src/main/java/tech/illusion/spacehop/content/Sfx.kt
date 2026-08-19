package tech.illusion.spacehop.content

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator

enum class HopSfxEvent { LANDED_PERFECT, LANDED_SUCCESS, NEW_RECORD }

private const val ASSET_COUNTDOWN = "audio/countdown.mp3"
private const val ASSET_CHARGE = "audio/charge.mp3"
private const val ASSET_LOTUS_LANDING = "audio/lotus_landing.mp3"
private const val ASSET_STREAM = "audio/stream.mp3"
private const val LOAD_PRIORITY = 1
private const val NO_STREAM = 0
private const val LOOP_FOREVER = -1

/**
 * Non-spatial state cues via Android's native `SoundPool`/`ToneGenerator` — per the Spatial
 * SDK's own audio-overview guidance, un-positioned UI/state sounds don't need the ECS spatial
 * audio components' positional cost ("system prompts and interface sound effects... typically
 * does not rely on spatial positioning"). The jump and fall stingers both need to originate from
 * and follow the frog/plate, so they live on `CharacterController`/`StageBoardAnimator` instead,
 * via `ObjectAudioComponent` bound to those entities.
 */
class Sfx(context: Context) {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    private val pool = SoundPool.Builder()
        .setMaxStreams(4) // countdown/stream + charge + a one-shot landing cue can overlap
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val countdownSoundId = pool.loadAsset(context, ASSET_COUNTDOWN)
    private val chargeSoundId = pool.loadAsset(context, ASSET_CHARGE)
    private val lotusLandingSoundId = pool.loadAsset(context, ASSET_LOTUS_LANDING)
    private val streamSoundId = pool.loadAsset(context, ASSET_STREAM)
    private var countdownStreamId = NO_STREAM
    private var chargeStreamId = NO_STREAM
    private var streamStreamId = NO_STREAM

    private fun SoundPool.loadAsset(context: Context, assetPath: String): Int =
        context.assets.openFd(assetPath).use { load(it, LOAD_PRIORITY) }

    fun play(event: HopSfxEvent) {
        when (event) {
            HopSfxEvent.LANDED_PERFECT -> toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            HopSfxEvent.LANDED_SUCCESS -> toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            HopSfxEvent.NEW_RECORD -> toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
        }
    }

    /** Starts the per-round countdown tick. Stops any still-playing previous tick first — a
     * stale stream must never be left racing a new one. Kitchen-stage only (V2: the lotus-pond
     * stage has no timer, see [startStream]). */
    fun startCountdown() {
        stopCountdown()
        countdownStreamId = pool.play(countdownSoundId, 1f, 1f, LOAD_PRIORITY, 0, 1f)
    }

    /** A genuine player jump calls this directly (HomeStage's onRelease); the app's own
     * forced-miss catch on timeout deliberately does not, so the cue keeps playing through it. */
    fun stopCountdown() {
        if (countdownStreamId != NO_STREAM) pool.stop(countdownStreamId)
        countdownStreamId = NO_STREAM
    }

    fun startCharge() {
        stopCharge()
        chargeStreamId = pool.play(chargeSoundId, 1f, 1f, LOAD_PRIORITY, 0, 1f)
    }

    fun stopCharge() {
        if (chargeStreamId != NO_STREAM) pool.stop(chargeStreamId)
        chargeStreamId = NO_STREAM
    }

    /** V2: the lotus-pond stage's ambient loop, standing in for the kitchen stage's countdown
     * tick — that stage has no time limit, so this is atmosphere, not pressure. Restarted each
     * new round the same way the countdown is; unlike the countdown, must be explicitly stopped
     * on a miss/game-over, since a looping stream never ends on its own. */
    fun startStream() {
        stopStream()
        streamStreamId = pool.play(streamSoundId, 1f, 1f, LOAD_PRIORITY, LOOP_FOREVER, 1f)
    }

    fun stopStream() {
        if (streamStreamId != NO_STREAM) pool.stop(streamStreamId)
        streamStreamId = NO_STREAM
    }

    /** V2: the lotus-pond stage's one-shot landing cue, replacing LANDED_PERFECT/LANDED_SUCCESS
     * there — that stage doesn't distinguish perfect from ordinary landings by sound. */
    fun playLotusLanding() {
        pool.play(lotusLandingSoundId, 1f, 1f, LOAD_PRIORITY, 0, 1f)
    }

    /** Pauses every currently-playing stream in the pool (countdown tick, charge loop, and the
     * lotus stage's [startStream] water loop alike) — the one built-in SoundPool primitive that
     * covers whichever of those happens to be active without tracking each one by hand. */
    fun pause() {
        pool.autoPause()
    }

    /** Resumes whatever [pause] paused. A no-op if nothing was playing. */
    fun resume() {
        pool.autoResume()
    }

    fun release() {
        toneGenerator.release()
        pool.release()
    }
}
