package com.synora.app

import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.synora.core.sync.ClockSync
import com.synora.core.sync.Command
import com.synora.core.sync.SessionState
import com.synora.core.sync.Timeline
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * SYNORA Android - toolchain check (A0.2).
 * Created by Joe (Creator / Builder). Original concept: Sai Krishna.
 * Prototype code: shows this phone's audio facts, proves the sync core is linked,
 * and plays a short click test. No network or sync yet.
 */
class MainActivity : Activity() {
    private var track: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "SYNORA"
        title.textSize = 30f
        title.gravity = Gravity.CENTER
        title.setTextColor(0xFFD9B86C.toInt())

        val info = TextView(this)
        info.text = report()
        info.textSize = 15f
        info.setTextColor(0xFFEDEAE4.toInt())
        info.setPadding(0, pad, 0, pad)

        val button = Button(this)
        button.text = "Play 1 s click test"
        button.setOnClickListener { playClicks() }

        root.addView(title)
        root.addView(info)
        root.addView(button)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF0B0B10.toInt())
        scroll.addView(root)
        setContentView(scroll)
    }

    /** Facts about this phone's audio output, plus a check that the sync core works. */
    private fun report(): String {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val pm = packageManager
        val lowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val proAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)

        val sample = ClockSync.sample(1000.0, 1520.0, 1521.0, 1041.0)
        val state = Timeline.reduce(SessionState(), Command.Play(startHostMs = 1000.0, posSec = 10.0), 120.0)
        val pos = Timeline.positionAt(state, 3500.0, 120.0)

        return buildString {
            appendLine("Android toolchain check (A0.2)")
            appendLine()
            appendLine("Phone: " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Android version: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            appendLine()
            appendLine("Device audio facts")
            appendLine("Output sample rate: " + sampleRate + " Hz")
            appendLine("Output frames per buffer: " + framesPerBuffer)
            appendLine("Low-latency audio feature: " + lowLatency)
            appendLine("Pro audio feature: " + proAudio)
            appendLine()
            appendLine("Sync core linked")
            appendLine("Clock offset check: " + sample.offsetMs + " ms (expected 500.0)")
            appendLine("Round trip check: " + sample.rttMs + " ms (expected 40.0)")
            appendLine("Timeline check: " + pos + " s (expected 12.5)")
            appendLine()
            appendLine("Nothing here claims any speaker or phone is compatible. These are facts read from this phone.")
        }
    }

    /** Four short 1 kHz clicks in one second, played with the low-latency performance mode requested. */
    private fun playClicks() {
        track?.release()
        val rate = 48000
        val data = ShortArray(rate)
        val len = (0.02 * rate).toInt()
        for (k in 0 until 4) {
            val base = k * rate / 4
            for (i in 0 until len) {
                if (base + i < data.size) {
                    val v = 0.8 * 32767.0 * sin(2.0 * PI * 1000.0 * (i + 1) / rate) * exp(-6.0 * i / len)
                    data[base + i] = v.toInt().toShort()
                }
            }
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.write(data, 0, data.size)
        t.play()
        track = t
    }

    override fun onDestroy() {
        track?.release()
        super.onDestroy()
    }
}
