/*
 * Copyright (C) 2021-2022 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView

import androidx.core.graphics.ColorUtils

import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle

import java.io.RandomAccessFile

import javax.inject.Inject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FPSInfoService @Inject constructor(
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    @Main private val handler: Handler,
) : Service() {

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            logD("onStartedGoingToSleep")
            stopReadingInternal()
        }

        override fun onFinishedWakingUp() {
            logD("onFinishedWakingUp")
            startReadingInternal()
        }
    }

    private lateinit var binder: IBinder
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var windowManager: WindowManager
    private lateinit var fpsInfoView: TextView
    private lateinit var fpsInfoNode: RandomAccessFile

    private var fpsReadJob: Job? = null

    private var registeredWakefulnessLifecycleObserver = false

    private val topInset: Int
        get() = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top

    val isReading: Boolean
        get() = fpsReadJob?.isActive == true

    override fun onCreate() {
        super.onCreate()
        logD("onCreate")
        binder = ServiceBinder()
        coroutineScope = CoroutineScope(Dispatchers.IO)

        windowManager = getSystemService(WindowManager::class.java)
        layoutParams.y = topInset

        fpsInfoView = TextView(this).apply {
            text = getString(R.string.fps_text_placeholder, 0)
            val customBG = resources.getDrawable(R.drawable.fpsinfo_bg)
            background = customBG
            val accentColor = resources.getColor(android.R.color.system_accent1_50)
            setTextColor(accentColor)
            typeface = Typeface.DEFAULT_BOLD
            val padding = resources.getDimensionPixelSize(R.dimen.fps_info_text_padding)
            setPadding(padding, padding, padding, padding)
        }

        val nodePath = getString(R.string.config_fpsInfoSysNode)
        val result = runCatching {
            RandomAccessFile(nodePath, "r")
        }
        if (result.isFailure) {
            Log.e(TAG, "Unable to open $nodePath, ${result.exceptionOrNull()?.message}")
            stopSelf()
            return
        } else {
            fpsInfoNode = result.getOrThrow()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onConfigurationChanged(newConfig: Configuration) {
        logD("onConfigurationChanged")
        layoutParams.y = topInset
        if (fpsInfoView.parent != null) {
            handler.post {
                windowManager.updateViewLayout(fpsInfoView, layoutParams)
            }
        }
    }

    fun startReading() {
        if (!registeredWakefulnessLifecycleObserver) {
            wakefulnessLifecycle.addObserver(wakefulnessObserver)
            registeredWakefulnessLifecycleObserver = true
        }
        startReadingInternal()
    }

    private fun startReadingInternal() {
        logD("startReadingInternal, isReading = $isReading")
        if (isReading) return
        if (fpsInfoView.parent == null) {
            handler.post {
                windowManager.addView(fpsInfoView, layoutParams)
            }
        }
        fpsReadJob = coroutineScope.launch {
            do {
                val fps = measureFps()
                handler.post {
                    fpsInfoView.text = getString(R.string.fps_text_placeholder, fps)
                }
                delay(FPS_MEASURE_INTERVAL)
            } while (isActive)
        }
    }

    fun stopReading() {
        stopReadingInternal()
        if (registeredWakefulnessLifecycleObserver) {
            wakefulnessLifecycle.removeObserver(wakefulnessObserver)
            registeredWakefulnessLifecycleObserver = false
        }
    }

    private fun stopReadingInternal() {
        logD("stopReadingInternal, isReading = $isReading")
        if (!isReading) return
        fpsReadJob?.cancel()
        fpsReadJob = null
        if (fpsInfoView.parent != null) {
            handler.post {
                windowManager.removeViewImmediate(fpsInfoView)
            }
        }
    }

    private fun measureFps(): Int = runCatching {
            fpsInfoNode.seek(0L)
            fpsRegex.find(fpsInfoNode.readLine())?.value?.toInt() ?: 0
        }.getOrElse {
            Log.e(TAG, "Failed to parse fps, ${it.message}")
            0
        }

    override fun onDestroy() {
        logD("onDestroy")
        stopReading()
        coroutineScope.cancel()
        super.onDestroy()
    }

    inner class ServiceBinder : Binder() {
        val service: FPSInfoService
            get() = this@FPSInfoService
    }

    private companion object {
        private const val TAG = "FPSInfoService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }

        private const val FPS_MEASURE_INTERVAL = 1000L

        private const val BACKGROUND_ALPHA = 120

        private val fpsRegex = Regex("[0-9]+")
    }
}