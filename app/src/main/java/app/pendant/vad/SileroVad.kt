package app.pendant.vad

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.log10
import kotlin.math.max

/**
 * Silero VAD (v5 ONNX) wrapped behind the app's [Vad] surface.
 *
 * A ~2 MB speech-trained neural net shipped as an app asset and run fully
 * on-device via ONNX Runtime. Human speech scores high probabilities; desk
 * bumps, HVAC, traffic and music score near zero — so only speech opens
 * segments (the tradeoff: non-speech noises are not captured either).
 *
 * The model consumes 512-sample windows at 16 kHz (~32 ms) PLUS 64 samples
 * of carry-over context from the previous window (the official python
 * wrapper prepends `context` to each chunk, so every graph call sees
 * [1, 576]). Omitting the context makes the graph effectively deaf — it runs
 * cleanly but scores ~0.001 for everything, real speech included.
 * 48 kHz input is decimated to 16 kHz on the fly. Onset needs [ENTER_CONFIRM] windows above
 * [threshold] and offset 2 below, mirroring the energy detector's confirm
 * pre-window. A few ms of CPU per window on a mid-range core.
 *
 * The dB getters feed the settings-screen meter with the same scale the
 * energy detector uses, so the live meter keeps working unchanged — but
 * probability has no dB threshold, so [enterThresholdDb] reports -1, which
 * the meter interprets as "hide the threshold line".
 */
class SileroVad(
    context: Context,
    sampleRate: Int,
    /** Enter probability threshold (0..1); the settings slider writes 20–90 %. */
    private val threshold: Float = DEFAULT_ENTER_PROB,
) : Vad {

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val closeLock = Any()

    // Input/output names, resolved once (matched defensively so a rebuilt
    // model variant can't wedge the meter).
    private val inputName: String
    private val stateName: String
    private val srName: String
    private val probOutputName: String
    private val stateOutputName: String

    // Runtime state ------------------------------------------------------

    private val window = FloatArray(WINDOW_SAMPLES) // 512 samples = 16 kHz * 32 ms
    private var windowSamples = 0
    private val context = FloatArray(CONTEXT_SAMPLES) // last 64 carried between windows
    private val state = FloatArray(2 * LSTM_HIDDEN) // GRU state [2,1,128]
    private val decim = max(sampleRate / METER_RATE_HZ, 1) // 48 kHz -> every 3rd sample

    // Per-frame meter values, computed with the same high-pass + formula as
    // EnergyVad so the settings meter reads an identical scale either way.
    private val hpPrevX = DoubleArray(1)
    private val hpPrevY = DoubleArray(1)
    private var frameEnergy = -120f

    private var speech = false
    private var aboveCount = 0
    private var belowCount = 0

    // Prob telemetry accumulators (reset every PROB_LOG_EVERY windows).
    private var telemetryCount = 0
    private var telemetrySum = 0f
    private var telemetryPeak = 0f

    private val inputPing = FloatArray(WINDOW_SAMPLES + CONTEXT_SAMPLES)
    private val sr = LongArray(1) // the model always runs internally at 16 kHz

    // Direct-memory tensor buffers. This ORT Android build silently zeroes
    // heap-backed buffers toward native code (discovered via the sr scalar;
    // NDR: a silent probe window passed with dead heap input bindings too),
    // so every binding is backed by native memory instead.
    private val inputView = ByteBuffer
        .allocateDirect(INPUT_SAMPLES * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val stateView = ByteBuffer
        .allocateDirect(2 * LSTM_HIDDEN * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /** sr tensor binding form that this device's ORT build accepted (see init). */
    private var srStrategy = 0

    /** 0 = default graph optimization, 1 = NO_OPT (probed in init). */
    private var optStrategy = 0

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        // Resolve I/O names once (matched defensively so a rebuilt model
        // variant can't wedge the meter).
        val namer = env.createSession(bytes, sessionOptions(noOpt = false))
        val ins = namer.inputNames.toList()
        val outs = namer.outputNames.toList()
        inputName = ins.first { it.contains("input", ignoreCase = true) }
        stateName = ins.first { it.contains("state", ignoreCase = true) }
        srName = ins.first { it.contains("sr", ignoreCase = true) }
        stateOutputName = outs.first { it.contains("state", ignoreCase = true) }
        probOutputName = outs.first { !it.contains("state", ignoreCase = true) }
        namer.close()
        // The Android native build differs from desktop around this If/LSTM
        // model: a broken sr binding OR an over-eager graph optimizer sends
        // the graph into the wrong sample-rate branch and the decoder LSTM
        // fails. Probe (optimization level x sr binding form) once with a
        // silent window and keep whichever combination the device accepts.
        var won = false
        outer@ for (opt in 0..1) {
            val probe = env.createSession(bytes, sessionOptions(noOpt = opt == 1))
            try {
                for (s2 in 0..3) {
                    try {
                        srStrategy = s2
                        synchronized(closeLock) {
                            runInference(probe, FloatArray(INPUT_SAMPLES))
                        }
                        optStrategy = opt // remember the winning optimization level
                        won = true
                        break@outer
                    } catch (e: Throwable) {
                        Log.w(TAG, "probe failed (opt=$opt, sr=$s2): ${e.message}")
                    }
                }
            } finally {
                try {
                    probe.close()
                } catch (_: Throwable) {
                }
            }
        }
        session = env.createSession(bytes, sessionOptions(noOpt = optStrategy == 1))
        // Sanity: a loud tone must score differently from silence. With a
        // dead input binding (the heap-buffer bug this runtime has shown
        // before), every window scores the silence value — surface that here
        // instead of discovering it live.
        try {
            telemetryCount = 0; telemetrySum = 0f; telemetryPeak = 0f
            val sess = session!!
            val tone = FloatArray(INPUT_SAMPLES) { i ->
                (0.4 * kotlin.math.sin(2 * Math.PI * 440.0 * i / 16000.0)).toFloat()
            }
            synchronized(closeLock) { runInference(sess, tone) }
            Log.i(TAG, "silero sanity: tone prob=%.3f (silence ~0.0005)".format(telemetryPeak))
        } catch (e: Throwable) {
            Log.w(TAG, "silero sanity failed: ${e.message}")
        }
        telemetryCount = 0; telemetrySum = 0f; telemetryPeak = 0f
        state.fill(0f)
        speech = false
        aboveCount = 0
        belowCount = 0
        windowSamples = 0
        println(
            "PendantEngine: silero vad ready (net ${bytes.size / 1024} KB, thr=%.2f, sr=%d, opt=%d, won=%b)"
                .format(threshold, srStrategy, optStrategy, won),
        )
    }

    /**
     * Single-threaded: one ~1 ms inference per 32 ms window gains nothing from
     * ORT's default per-core pool, whose idle workers spin and burn several cores.
     */
    private fun sessionOptions(noOpt: Boolean) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        if (noOpt) setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
    }

    // Frame processing ----------------------------------------------------

    override fun process(frame: ShortArray, count: Int): Boolean {
        frameEnergy = frameEnergyDb(frame, count)
        var i = 0
        while (i < count) {
            window[windowSamples++] = frame[i] / 32768f // normalize to [-1, 1]
            if (windowSamples == WINDOW_SAMPLES) {
                windowSamples = 0
                infer()
            }
            i += decim
        }
        return speech
    }

    private fun infer() {
        val sess = session ?: return
        synchronized(closeLock) {
            if (sess !is OrtSession) return
            try {
                System.arraycopy(context, 0, inputPing, 0, CONTEXT_SAMPLES)
                System.arraycopy(window, 0, inputPing, CONTEXT_SAMPLES, WINDOW_SAMPLES)
                runInference(sess, inputPing)
            } catch (e: Throwable) {
                // Never kill the capture loop over one failed inference; the
                // VAD simply stays in its current state until the next window.
                Log.w(TAG, "silero inference failed: ${e.message}")
            }
        }
    }

    private fun runInference(sess: OrtSession, samples: FloatArray) {
        // samples = [previous 64-sample carry-over] ++ [current 512 window],
        // exactly the layout the official wrapper feeds the graph.
        inputView.clear()
        inputView.put(samples)
        inputView.flip()
        stateView.clear()
        stateView.put(state)
        stateView.flip()
        val input = OnnxTensor.createTensor(env, inputView, longArrayOf(1, INPUT_SAMPLES.toLong()))
        val stateIn = OnnxTensor.createTensor(env, stateView, longArrayOf(2, 1, LSTM_HIDDEN.toLong()))
        val srIn = createSrTensor()
        val result = sess.run(mapOf(inputName to input, stateName to stateIn, srName to srIn))
        var prob = 0f
        try {
            for ((name, value) in result) {
                if (name == stateOutputName) {
                    (value as? OnnxTensor)?.getFloatBuffer()?.get(state)
                } else {
                    // Speech probability arrives as [n,1] (float[1][1]);
                    // some exports deliver a plain scalar instead.
                    val v = value.value
                    prob = when (v) {
                        is Number -> v.toFloat()
                        is Array<*> -> (v[0] as? FloatArray)?.get(0) ?: 0f
                        else -> 0f
                    }
                }
            }
            updateSpeech(prob)
            // The context the wrapper carries forward is the LAST 64 samples
            // of what the graph just saw (i.e. the tail of the current window).
            System.arraycopy(samples, INPUT_SAMPLES - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES)
        } finally {
            result.close()
            input.close()
            stateIn.close()
            srIn.close()
        }
    }

    /**
     * sr tensor binding forms, probed at init (see there):
     * 0: int64 scalar via LongBuffer; 1: int64 scalar via ByteBuffer+type;
     * 2: int64 [1] vector (in case the build rejects scalar buffers).
     */
    private fun createSrTensor(): OnnxTensor = when (srStrategy) {
        1 -> {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(16_000L)
            bb.rewind()
            OnnxTensor.createTensor(env, bb, longArrayOf(), OnnxJavaType.INT64)
        }
        // Direct heap-free byte buffer — bypasses ORT's heap-buffer handling
        // chain (which mangles int64 scalars on this Android build).
        3 -> {
            val bb = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            bb.putLong(16_000L)
            bb.rewind()
            OnnxTensor.createTensor(env, bb, longArrayOf(), OnnxJavaType.INT64)
        }
        2 -> OnnxTensor.createTensor(env, sr)
        else -> OnnxTensor.createTensor(env, LongBuffer.wrap(sr), longArrayOf())
    }

    private fun updateSpeech(prob: Float) {
        // Temporary telemetry: what does the net actually score on live audio?
        telemetrySum += prob
        if (prob > telemetryPeak) telemetryPeak = prob
        if (++telemetryCount >= PROB_LOG_EVERY) {
            Log.i(
                TAG,
                "silero prob avg=%.3f peak=%.3f thr=%.2f en=%.1f speech=%b"
                    .format(telemetrySum / telemetryCount, telemetryPeak, threshold, frameEnergy, speech),
            )
            telemetryCount = 0
            telemetrySum = 0f
            telemetryPeak = 0f
        }
        if (prob >= threshold) {
            aboveCount++
            belowCount = 0
        } else {
            belowCount++
            aboveCount = 0
        }
        if (!speech && aboveCount >= ENTER_CONFIRM) speech = true
        if (speech && belowCount >= EXIT_CONFIRM) speech = false
    }

    // Meter surface -------------------------------------------------------

    override val enterThresholdDb: Float
        get() = -1f // probability-based: the meter hides the threshold line

    override val exitThresholdDb: Float
        get() = -1f

    override val energyDb: Float
        get() = frameEnergy

    override val floorDb: Float
        get() = frameEnergy

    override val speechActive: Boolean
        get() = speech

    override fun reset() {
        windowSamples = 0
        context.fill(0f)
        state.fill(0f)
        frameEnergy = -120f
        speech = false
        aboveCount = 0
        belowCount = 0
        telemetryCount = 0
        telemetrySum = 0f
        telemetryPeak = 0f
    }

    override fun close() {
        synchronized(closeLock) {
            val s = session
            session = null
            if (s != null) {
                try {
                    s.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // Same formula as EnergyVad.frameEnergyDb (identical hp filter keeps the
    // meter bar directly comparable between the two detectors).
    private fun frameEnergyDb(frame: ShortArray, count: Int): Float {
        var acc = 0.0
        for (i in 0 until count) {
            val y = hpFilter(frame[i].toDouble())
            acc += y * y
        }
        val rms = acc / max(count, 1)
        return (10.0 * log10(max(rms, 1e-10))).toFloat()
    }

    private fun hpFilter(x: Double): Double {
        val prevX = hpPrevX[0]
        val y = (x - prevX) + EnergyVad.HIGHPASS_DECAY * hpPrevY[0]
        hpPrevX[0] = x
        hpPrevY[0] = y
        return y
    }

    companion object {
        private const val TAG = "PendantEngine"
        const val MODEL_ASSET = "silero_vad.onnx"
        const val DEFAULT_ENTER_PROB = 0.5f
        const val ENTER_CONFIRM = 3 // 3 windows ≈ 96 ms of speech before onset
        const val EXIT_CONFIRM = 2
        const val WINDOW_SAMPLES = 512
        /** Carry-over from the previous window; the graph input is [1, 576]. */
        const val CONTEXT_SAMPLES = 64
        const val INPUT_SAMPLES = WINDOW_SAMPLES + CONTEXT_SAMPLES
        const val LSTM_HIDDEN = 128
        const val METER_RATE_HZ = 16_000
        const val PROB_LOG_EVERY = 50 // ~1.6 s of windows per telemetry line
    }
}
