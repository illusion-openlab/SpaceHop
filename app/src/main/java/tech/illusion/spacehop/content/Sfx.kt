package tech.illusion.spacehop.content

import android.media.AudioManager
import android.media.ToneGenerator

enum class HopSfxEvent { LAUNCH, LANDED_PERFECT, LANDED_SUCCESS, FELL, NEW_RECORD }

class Sfx {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)

    fun play(event: HopSfxEvent) {
        when (event) {
            HopSfxEvent.LAUNCH -> toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            HopSfxEvent.LANDED_PERFECT -> toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            HopSfxEvent.LANDED_SUCCESS -> toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            HopSfxEvent.FELL -> toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 400)
            HopSfxEvent.NEW_RECORD -> toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
        }
    }

    fun release() {
        toneGenerator.release()
    }
}
