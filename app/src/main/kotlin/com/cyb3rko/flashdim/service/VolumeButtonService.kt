/*
 * Copyright (c) 2022-2025 Cyb3rKo
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.cyb3rko.flashdim.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.cyb3rko.flashdim.utils.Safe

class VolumeButtonService : AccessibilityService() {

    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null

    @Volatile
    private var torchEnabled = false

    @Volatile
    private var currentLevel = 1
    private var defaultLevel = 1
    private var maxLevel = 1

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatingKeyCode: Int? = null
    private var keyFilteringEnabled = false
    private val repeatAction = object : Runnable {
        override fun run() {
            val keyCode = repeatingKeyCode ?: return
            if (!torchEnabled || !changeLevel(keyCode)) {
                stopKeyRepeat()
                return
            }
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        Safe.initialize(applicationContext)

        cameraManager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Only track the camera whose torch FlashDim can actually control.
        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        cameraId?.let { id ->
            val characteristics =
                cameraManager.getCameraCharacteristics(id)

            maxLevel =
                characteristics.get(
                    CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                ) ?: 1

            defaultLevel =
                characteristics.get(
                    CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL
                ) ?: 1

            currentLevel = defaultLevel
        }

        // Do not take part in the volume-key dispatch chain until the torch is
        // actually on. This keeps the system volume controls completely native
        // while the flashlight is off.
        setKeyFilteringEnabled(false)

        cameraManager.registerTorchCallback(
            object : CameraManager.TorchCallback() {

                override fun onTorchModeChanged(
                    id: String,
                    enabled: Boolean
                ) {
                    if (id != cameraId) return

                    torchEnabled = enabled
                    if (!enabled) stopKeyRepeat()
                    setKeyFilteringEnabled(enabled && maxLevel > MIN_LEVEL)
                    currentLevel = if (enabled) {
                        Safe.getInt(Safe.CURRENT_LEVEL, defaultLevel)
                            .coerceIn(MIN_LEVEL, maxLevel)
                    } else {
                        defaultLevel
                    }
                    if (enabled && maxLevel > MIN_LEVEL) {
                        try {
                            cameraManager.turnOnTorchWithStrengthLevel(id, currentLevel)
                        } catch (e: Exception) {
                            Log.e("FlashDim Service", "Unable to restore torch level", e)
                        }
                    }
                    Safe.writeBoolean(Safe.FLASH_ACTIVE, enabled)
                }

                override fun onTorchStrengthLevelChanged(
                    id: String,
                    newStrengthLevel: Int
                ) {
                    if (id != cameraId) return

                    currentLevel = newStrengthLevel
                    Safe.writeInt(Safe.CURRENT_LEVEL, newStrengthLevel)
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        // Let Android handle volume keys normally while the torch is off.
        if (!torchEnabled || maxLevel <= MIN_LEVEL) {
            stopKeyRepeat()
            return false
        }

        val isVolumeButton =
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        if (!isVolumeButton) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && repeatingKeyCode != event.keyCode) {
                    stopKeyRepeat()
                    if (changeLevel(event.keyCode)) {
                        repeatingKeyCode = event.keyCode
                        repeatHandler.postDelayed(repeatAction, LONG_PRESS_DELAY_MS)
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                if (repeatingKeyCode == event.keyCode) stopKeyRepeat()
            }
        }

        return true
    }

    private fun changeLevel(keyCode: Int): Boolean {
        val id = cameraId ?: return false
        val newLevel = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                (currentLevel + LEVEL_STEP).coerceAtMost(maxLevel)

            KeyEvent.KEYCODE_VOLUME_DOWN ->
                (currentLevel - LEVEL_STEP).coerceAtLeast(MIN_LEVEL)

            else -> currentLevel
        }

        if (newLevel == currentLevel) return false

        return try {
            cameraManager.turnOnTorchWithStrengthLevel(id, newLevel)
            currentLevel = newLevel
            Safe.writeInt(Safe.CURRENT_LEVEL, newLevel)
            Log.i("FlashDim Service", "Torch level: $currentLevel/$maxLevel")
            true
        } catch (e: Exception) {
            Log.e("FlashDim Service", "Unable to change torch level", e)
            false
        }
    }

    private fun stopKeyRepeat() {
        repeatHandler.removeCallbacks(repeatAction)
        repeatingKeyCode = null
    }

    private fun setKeyFilteringEnabled(enabled: Boolean) {
        if (keyFilteringEnabled == enabled) return

        val info = serviceInfo
        info.flags = if (enabled) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        serviceInfo = info
        keyFilteringEnabled = enabled
    }

    override fun onInterrupt() {
        stopKeyRepeat()
        Log.i(
            "FlashDim Service",
            "VolumeButtonService interrupted"
        )
    }

    override fun onDestroy() {
        stopKeyRepeat()
        setKeyFilteringEnabled(false)
        super.onDestroy()
    }

    private companion object {
        const val MIN_LEVEL = 1
        const val LEVEL_STEP = 10
        const val LONG_PRESS_DELAY_MS = 350L
        const val REPEAT_INTERVAL_MS = 75L
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {}
}
