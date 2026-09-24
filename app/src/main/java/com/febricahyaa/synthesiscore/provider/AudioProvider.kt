/*
 * Copyright (C) 2026 FebriCahyaa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.febricahyaa.synthesiscore.provider

import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build

import com.febricahyaa.synthesiscore.core.Log
import com.febricahyaa.synthesiscore.core.Protocol
import com.febricahyaa.synthesiscore.core.ProviderContext
import com.febricahyaa.synthesiscore.core.StateProvider
import com.febricahyaa.synthesiscore.core.TriggerMode

/**
 * `audio_active` and `call_active`.
 *
 * - audio_active: [AudioManager.isMusicActive], re-read whenever
 *   [AudioManager.AudioPlaybackCallback] reports a playback change (API 26).
 * - call_active: [AudioManager.getMode] is a call/VoIP mode, pushed by
 *   [AudioManager.addOnModeChangedListener] on API 31+.
 */
class AudioProvider : StateProvider {
    override val name = "audio"

    private lateinit var audioManager: AudioManager
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null

    override fun start(ctx: ProviderContext): TriggerMode {
        audioManager = ctx.context.getSystemService(AudioManager::class.java)
            ?: error("AudioManager unavailable")

        val playbackEvents = try {
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    ctx.invalidate(this@AudioProvider)
                }
            }
            audioManager.registerAudioPlaybackCallback(callback, ctx.handler)
            playbackCallback = callback
            true
        } catch (t: Throwable) {
            Log.w(name, "Playback callback unavailable, polling instead: ${t.message}")
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val listener = AudioManager.OnModeChangedListener { ctx.invalidate(this) }
                audioManager.addOnModeChangedListener(ctx.executor, listener)
                modeListener = listener
            } catch (t: Throwable) {
                Log.w(name, "Mode listener unavailable, polling instead: ${t.message}")
            }
        }

        return if (playbackEvents) TriggerMode.EVENT else TriggerMode.POLL
    }

    override fun sample(out: MutableMap<String, String>) {
        out[Protocol.AUDIO_ACTIVE] = Protocol.flag(audioManager.isMusicActive)
        out[Protocol.CALL_ACTIVE] = Protocol.flag(isCallMode(audioManager.mode))
    }

    override fun pollIntervalMs(mode: TriggerMode, interactive: Boolean): Long = when {
        !interactive -> 10_000L
        mode == TriggerMode.EVENT && modeListener != null -> 5_000L
        else -> 1_000L
    }

    override fun stop() {
        playbackCallback?.let { audioManager.unregisterAudioPlaybackCallback(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let { audioManager.removeOnModeChangedListener(it) }
        }
        playbackCallback = null
        modeListener = null
    }

    companion object {
        /** Telephony call, VoIP/communication, or call screening. */
        fun isCallMode(mode: Int): Boolean = when (mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING
        }
    }
}
