package app.pendant.vad

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
