package com.yx37.companion.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log
import com.yx37.companion.data.PreferencesManager
import java.util.concurrent.ConcurrentHashMap

object AudioDspManager {
    private const val TAG = "AudioDspManager"

    // Default fallback bands (5-band standard Android OpenSL/AudioFlinger)
    private val DEFAULT_5_BANDS = intArrayOf(60, 230, 910, 3600, 14000)

    var hardwareBandsCount: Int = 5
        private set

    var hardwareBandFreqs: IntArray = DEFAULT_5_BANDS
        private set

    var minMillibels: Short = -1500
        private set

    var maxMillibels: Short = 1500
        private set

    val minGainDb: Float
        get() = minMillibels / 100f

    val maxGainDb: Float
        get() = maxMillibels / 100f

    private var globalEqualizer: Equalizer? = null
    private val sessionEqualizers = ConcurrentHashMap<Int, Equalizer>()

    var isEqMasterEnabled: Boolean = true
        private set

    var isHeadsetConnected: Boolean = false
        private set

    var currentGains: FloatArray = FloatArray(5) { 0f }
        private set

    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        try {
            val prefs = PreferencesManager(context)
            isEqMasterEnabled = prefs.isEqEnabled
            isHeadsetConnected = prefs.isConnected
            currentGains = prefs.getCustomEqBands(hardwareBandsCount)

            // Try gentle global session init without throwing
            initGlobalSession(context)

            isInitialized = true
            Log.i(TAG, "AudioDspManager successfully initialized.")
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing AudioDspManager", t)
        }
    }

    fun setHeadsetConnection(connected: Boolean) {
        try {
            isHeadsetConnected = connected
            applyToAllActiveSessions()
        } catch (t: Throwable) {
            Log.e(TAG, "Error setting headset connection", t)
        }
    }

    fun setMasterEnabled(enabled: Boolean, context: Context) {
        try {
            isEqMasterEnabled = enabled
            PreferencesManager(context).isEqEnabled = enabled
            applyToAllActiveSessions()
        } catch (t: Throwable) {
            Log.e(TAG, "Error setting master enabled", t)
        }
    }

    fun applyGains(gains: FloatArray, context: Context) {
        try {
            currentGains = gains.clone()
            PreferencesManager(context).saveCustomEqBands(currentGains)
            applyToAllActiveSessions()
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying gains", t)
        }
    }

    fun applyPreset(presetName: String, context: Context): FloatArray {
        val newGains = calculatePresetGains(presetName, hardwareBandFreqs)
        try {
            val prefs = PreferencesManager(context)
            prefs.selectedPreset = presetName
            applyGains(newGains, context)
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying preset", t)
        }
        return newGains
    }

    fun onSessionOpen(sessionId: Int) {
        if (sessionId <= 0) return
        Log.i(TAG, "Audio session opened by media player: $sessionId")
        try {
            if (!sessionEqualizers.containsKey(sessionId)) {
                val eq = Equalizer(1000, sessionId)

                // Probe real hardware bands safely from an actual active session!
                try {
                    val numBands = eq.numberOfBands.toInt()
                    if (numBands > 0) {
                        hardwareBandsCount = numBands
                        val freqs = IntArray(numBands)
                        for (i in 0 until numBands) {
                            freqs[i] = eq.getCenterFreq(i.toShort()) / 1000
                        }
                        hardwareBandFreqs = freqs
                        val range = eq.bandLevelRange
                        if (range.size >= 2) {
                            minMillibels = range[0]
                            maxMillibels = range[1]
                        }
                        if (currentGains.size != numBands) {
                            currentGains = FloatArray(numBands) { 0f }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not query band metadata: ${t.message}")
                }

                sessionEqualizers[sessionId] = eq
                applyToSingleEqualizer(eq)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to attach Equalizer to session $sessionId: ${t.message}")
        }
    }

    fun onSessionClose(sessionId: Int) {
        if (sessionId <= 0) return
        try {
            sessionEqualizers.remove(sessionId)?.release()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun initGlobalSession(context: Context) {
        try {
            if (globalEqualizer == null) {
                globalEqualizer = Equalizer(1000, 0)
                applyToSingleEqualizer(globalEqualizer)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Global session 0 not permitted on this ROM: ${t.message}")
        }

        try {
            val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun applyToAllActiveSessions() {
        globalEqualizer?.let { applyToSingleEqualizer(it) }

        for ((_, eq) in sessionEqualizers) {
            applyToSingleEqualizer(eq)
        }
    }

    private fun applyToSingleEqualizer(eq: Equalizer?) {
        if (eq == null) return
        try {
            val shouldEnable = isHeadsetConnected && isEqMasterEnabled
            if (eq.enabled != shouldEnable) {
                eq.enabled = shouldEnable
            }

            if (!shouldEnable) return

            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minL = range[0].toInt()
            val maxL = range[1].toInt()

            for (b in 0 until numBands) {
                val gainDb = currentGains.getOrElse(b) { 0f }
                val millibels = (gainDb * 100).toInt().coerceIn(minL, maxL).toShort()
                eq.setBandLevel(b.toShort(), millibels)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying band level: ${t.message}")
        }
    }

    fun calculatePresetGains(presetName: String, freqs: IntArray): FloatArray {
        val gains = FloatArray(freqs.size) { 0f }
        when (presetName.lowercase()) {
            "flat" -> {
                // all 0
            }
            "bass boost" -> {
                for (i in freqs.indices) {
                    val f = freqs[i]
                    gains[i] = when {
                        f <= 100 -> 6.0f
                        f <= 250 -> 4.0f
                        f <= 500 -> 1.5f
                        else -> 0.0f
                    }
                }
            }
            "treble boost" -> {
                for (i in freqs.indices) {
                    val f = freqs[i]
                    gains[i] = when {
                        f >= 8000 -> 6.0f
                        f >= 3000 -> 4.0f
                        f >= 1000 -> 1.5f
                        else -> 0.0f
                    }
                }
            }
            "vocal" -> {
                for (i in freqs.indices) {
                    val f = freqs[i]
                    gains[i] = when {
                        f in 500..3500 -> 4.5f
                        f < 200 -> -2.0f
                        else -> 0.0f
                    }
                }
            }
            "gaming" -> {
                for (i in freqs.indices) {
                    val f = freqs[i]
                    gains[i] = when {
                        f in 1000..5000 -> 5.0f
                        f >= 8000 -> 3.0f
                        f <= 150 -> -3.0f
                        else -> 0.0f
                    }
                }
            }
        }
        return gains
    }

    fun release() {
        try {
            globalEqualizer?.release()
            globalEqualizer = null
            for (eq in sessionEqualizers.values) {
                eq.release()
            }
            sessionEqualizers.clear()
            isInitialized = false
        } catch (t: Throwable) {
            // ignore
        }
    }
}
