package com.yx37.companion.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
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

    private data class SessionHolder(
        val equalizer: Equalizer,
        val loudnessEnhancer: LoudnessEnhancer?
    )

    private var globalEqualizer: Equalizer? = null
    private var globalLoudness: LoudnessEnhancer? = null

    // Track active media player sessions (e.g. Spotify, YouTube)
    // Clean up older sessions to prevent dead effect accumulation in AudioFlinger
    private val sessionHolders = ConcurrentHashMap<Int, SessionHolder>()

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
            val selected = prefs.selectedPreset
            if (selected.equals("Tùy chỉnh", ignoreCase = true)) {
                currentGains = prefs.getCustomEqBands(hardwareBandsCount)
            } else {
                currentGains = calculatePresetGains(selected, hardwareBandFreqs)
            }

            // Init global session 0 fallback safely
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
            applyToAllActiveSessions()
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying gains", t)
        }
    }

    fun applyCustomGains(gains: FloatArray, context: Context) {
        try {
            currentGains = gains.clone()
            val prefs = PreferencesManager(context)
            prefs.selectedPreset = "Tùy chỉnh"
            prefs.updateActiveProfileGains(currentGains, hardwareBandsCount)
            applyToAllActiveSessions()
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying custom gains", t)
        }
    }

    fun applyPreset(presetName: String, context: Context): FloatArray {
        val newGains = calculatePresetGains(presetName, hardwareBandFreqs)
        try {
            val prefs = PreferencesManager(context)
            prefs.selectedPreset = presetName
            currentGains = newGains.clone()
            applyToAllActiveSessions()
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying preset", t)
        }
        return newGains
    }

    fun onSessionOpen(sessionId: Int) {
        if (sessionId <= 0) return
        Log.i(TAG, "Audio session opened by media player: $sessionId")
        try {
            // Prune dead sessions: Media players only have one active playback stream.
            // Release any older session to prevent AudioFlinger from running out of effect resources
            if (sessionHolders.size >= 1 && !sessionHolders.containsKey(sessionId)) {
                val iterator = sessionHolders.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    try {
                        entry.value.equalizer.release()
                        entry.value.loudnessEnhancer?.release()
                    } catch (t: Throwable) {
                        // ignore
                    }
                    iterator.remove()
                }
            }

            if (!sessionHolders.containsKey(sessionId)) {
                val eq = Equalizer(1000, sessionId)
                val le = try {
                    LoudnessEnhancer(sessionId).apply {
                        setTargetGain(200) // +2.0 dB headroom compensation
                    }
                } catch (t: Throwable) {
                    null
                }

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

                val holder = SessionHolder(eq, le)
                sessionHolders[sessionId] = holder
                applyToAllActiveSessions()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to attach Equalizer to session $sessionId: ${t.message}")
        }
    }

    fun onSessionClose(sessionId: Int) {
        if (sessionId <= 0) return
        try {
            val holder = sessionHolders.remove(sessionId)
            holder?.equalizer?.release()
            holder?.loudnessEnhancer?.release()
            // When specific session closes, re-apply so global session 0 takes over seamlessly
            applyToAllActiveSessions()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun initGlobalSession(context: Context) {
        try {
            if (globalEqualizer == null) {
                globalEqualizer = Equalizer(1000, 0)
                globalLoudness = try {
                    LoudnessEnhancer(0).apply { setTargetGain(200) }
                } catch (t: Throwable) {
                    null
                }
                applyToAllActiveSessions()
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
        val shouldEnable = isHeadsetConnected && isEqMasterEnabled
        val hasActiveAppSession = sessionHolders.isNotEmpty()

        // 1. Global Session 0 is active ONLY when NO specific app session is active!
        // This completely prevents Double Processing where sound gets attenuated twice (-12dB) and phase-distorted!
        globalEqualizer?.let { eq ->
            try {
                val enableGlobal = shouldEnable && !hasActiveAppSession
                if (eq.enabled != enableGlobal) {
                    eq.enabled = enableGlobal
                }
                if (enableGlobal) {
                    applyBandsToEqualizer(eq)
                    globalLoudness?.let { le ->
                        if (!le.enabled) le.enabled = true
                        le.setTargetGain(200)
                    }
                } else {
                    globalLoudness?.let { le ->
                        if (le.enabled) le.enabled = false
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error applying global equalizer", t)
            }
        }

        // 2. Specific App Sessions (Spotify, YouTube, Media Players)
        for ((_, holder) in sessionHolders) {
            try {
                val eq = holder.equalizer
                if (eq.enabled != shouldEnable) {
                    eq.enabled = shouldEnable
                }
                if (shouldEnable) {
                    applyBandsToEqualizer(eq)
                    holder.loudnessEnhancer?.let { le ->
                        if (!le.enabled) le.enabled = true
                        le.setTargetGain(200) // Compensate for Android's negative EQ headroom cut
                    }
                } else {
                    holder.loudnessEnhancer?.let { le ->
                        if (le.enabled) le.enabled = false
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error applying session equalizer", t)
            }
        }
    }

    private fun applyBandsToEqualizer(eq: Equalizer) {
        try {
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
            Log.e(TAG, "Error setting band level: ${t.message}")
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
            globalLoudness?.release()
            globalLoudness = null
            for ((_, holder) in sessionHolders) {
                holder.equalizer.release()
                holder.loudnessEnhancer?.release()
            }
            sessionHolders.clear()
            isInitialized = false
        } catch (t: Throwable) {
            // ignore
        }
    }
}
