package tech.illusion.spacehop.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchStateMachineTest {

    private fun freshMachine() = PinchStateMachine(
        enterThresholdMeters = 0.025f,
        exitThresholdMeters = 0.045f,
        requiredConsistentFrames = 3,
    )

    @Test
    fun update_startsNotPinching() {
        val m = freshMachine()
        assertFalse(m.isPinching())
    }

    @Test
    fun update_needsConsistentFramesBeforeEmittingStarted() {
        val m = freshMachine()
        assertNull(m.update(0.01f)) // frame 1, below enter threshold
        assertNull(m.update(0.01f)) // frame 2
        assertEquals(PinchEvent.STARTED, m.update(0.01f)) // frame 3, flips
        assertTrue(m.isPinching())
    }

    @Test
    fun update_singleNoisyFrame_doesNotFlipState() {
        val m = freshMachine()
        m.update(0.01f); m.update(0.01f); m.update(0.01f) // now pinching
        assertNull(m.update(0.03f)) // one noisy frame inside the hysteresis band, still pinching
        assertTrue(m.isPinching())
    }

    @Test
    fun update_hysteresisBand_holdsCurrentStateWithoutCommittingExit() {
        val m = freshMachine()
        m.update(0.01f); m.update(0.01f); m.update(0.01f) // pinching
        // 0.03 is inside [enter=0.025, exit=0.045]: instantState should hold current (pinching)
        m.update(0.03f); m.update(0.03f); m.update(0.03f)
        assertTrue(m.isPinching())
    }

    @Test
    fun update_sustainedReleaseBeyondExitThreshold_emitsEndedAfterConsistentFrames() {
        val m = freshMachine()
        m.update(0.01f); m.update(0.01f); m.update(0.01f) // pinching
        assertNull(m.update(0.05f))
        assertNull(m.update(0.05f))
        assertEquals(PinchEvent.ENDED, m.update(0.05f))
        assertFalse(m.isPinching())
    }

    @Test
    fun update_intermittentNoise_resetsConsistentFrameCounter() {
        val m = freshMachine()
        // Trying to enter pinch but noise interrupts the run every other frame.
        assertNull(m.update(0.01f)) // candidate=true, count=1
        assertNull(m.update(0.03f)) // inside band, holds current state (not pinching) -> resets candidate tracking
        assertNull(m.update(0.01f)) // candidate=true, count=1 again (not yet 3)
        assertNull(m.update(0.01f)) // count=2
        assertEquals(PinchEvent.STARTED, m.update(0.01f)) // count=3, flips
    }
}
