package app.pendant.vad

/**
 * Common surface shared by all detectors. The capture loop, the settings
 * meter and the diagnostic telemetry only know this interface — swapping the
 * implementation (energy threshold vs. Silero neural net) changes one call
 * site (RecordingEngine.newVad).
 */
interface Vad : AutoCloseable {
    /** Process one frame; returns true while speech is considered active. */
    fun process(frame: ShortArray, count: Int): Boolean

    /**
     * Absolute enter threshold in dB for the settings meter's marker line.
     * <= 0 means "no line to show" (probability-based detectors like Silero
     * have no dB threshold).
     */
    val enterThresholdDb: Float

    /** Absolute exit threshold (hysteresis), same convention as [enterThresholdDb]. */
    val exitThresholdDb: Float

    /** Absolute energy (dB) of the most recent frame — feeds the meter bar. */
    val energyDb: Float

    /**
     * Adaptive noise floor (dB, absolute); equals [energyDb] for detectors
     * that don't track a floor.
     */
    val floorDb: Float

    /** Whether speech is currently active. */
    val speechActive: Boolean

    /** Drop learned state (noise floor tracking, model context). */
    fun reset()

    /** Release native resources (sessions etc.). Must be safe to call twice. */
    override fun close()
}
