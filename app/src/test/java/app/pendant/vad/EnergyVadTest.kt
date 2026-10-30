package app.pendant.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class EnergyVadTest {

    private val sampleRate = 16_000
    private val frameSamples = 320 // 20 ms

    private fun noiseFrame(rand: Random, level: Double = 30.0): ShortArray =
        ShortArray(frameSamples) { ((rand.nextDouble() - 0.5) * level * 2).toInt().toShort() }

    private fun toneFrame(t: IntArray, freq: Double, level: Double = 3000.0): ShortArray =
        frameSamples.let { ShortArray(it) { i -> (level * sin(2 * PI * freq * (t[0] + i) / sampleRate)).toInt().toShort() } }

    @Test
    fun `silence never triggers`() {
        val vad = EnergyVad(sampleRate)
        val rand = Random(1)
        repeat(500) { assertFalse(vad.process(noiseFrame(rand), frameSamples)) }
    }

    @Test
    fun `custom threshold overrides preset margins`() {
        val vad = EnergyVad(sampleRate, EnergyVad.Sensitivity.CUSTOM, customEnterDb = 20)
        val rand = Random(4)
        repeat(200) { vad.process(noiseFrame(rand), frameSamples) }
        assertEquals(20f, vad.enterThresholdDb - vad.floorDb, 0.01f)
        assertEquals(20f * 0.55f, vad.exitThresholdDb - vad.floorDb, 0.01f)

        // The preset paths stay untouched:
        val strict = EnergyVad(sampleRate, EnergyVad.Sensitivity.LOW)
        repeat(200) { strict.process(noiseFrame(rand), frameSamples) }
        assertEquals(15f, strict.enterThresholdDb - strict.floorDb, 0.01f)

        val mid = EnergyVad(sampleRate)
        repeat(200) { mid.process(noiseFrame(rand), frameSamples) }
        assertEquals(11f, mid.enterThresholdDb - mid.floorDb, 0.01f)
    }

    @Test
    fun `fixed threshold ignores the noise floor`() {
        val vad = EnergyVad(sampleRate, EnergyVad.Sensitivity.MEDIUM, fixedEnterDb = 55f)
        val rand = Random(5)
        val t = intArrayOf(0)
        // Background noise (25 dB) and even a louder burst (31 dB) must not trigger…
        repeat(300) {
            vad.process(noiseFrame(rand, 60.0), frameSamples)
            t[0] += frameSamples
        }
        assertFalse("noise below a fixed threshold must never trigger", vad.speechActive)
        assertEquals(55f, vad.enterThresholdDb, 0.01f)
        // …while loud speech clears it…
        var sawSpeech = false
        repeat(100) {
            if (vad.process(toneFrame(t, 440.0), frameSamples)) sawSpeech = true
            t[0] += frameSamples
        }
        assertTrue("loud tone should cross the fixed threshold", sawSpeech)
        // …and speech ends when energy drops 6 dB below the line again.
        var recovered = false
        repeat(200) {
            if (!vad.process(noiseFrame(rand, 60.0), frameSamples)) recovered = true
        }
        assertTrue("VAD should recover below a fixed threshold", recovered)
    }

    @Test
    fun `speech after silence triggers and recovers`() {
        val vad = EnergyVad(sampleRate)
        val rand = Random(2)
        val t = intArrayOf(0)
        repeat(200) { // 4 s of room noise
            vad.process(noiseFrame(rand, 40.0), frameSamples)
            t[0] += frameSamples
        }
        var sawSpeech = false
        repeat(100) { // 2 s of loud tone
            if (vad.process(toneFrame(t, 440.0), frameSamples)) sawSpeech = true
            t[0] += frameSamples
        }
        assertTrue("loud tone should be detected as speech", sawSpeech)
        var recovered = false
        repeat(200) {
            if (!vad.process(noiseFrame(rand, 40.0), frameSamples)) recovered = true
        }
        assertTrue("VAD should recover to silence", recovered)
    }
}
