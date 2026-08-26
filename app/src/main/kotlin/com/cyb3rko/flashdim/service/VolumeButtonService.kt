/*
 * Copyright (c) 2022-2025 Cyb3rKo
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.cyb3rko.flashdim.service

import android.accessibilityservice.AccessibilityService
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

        cameraManager.registerTorchCallback(
            object : CameraManager.TorchCallback() {

                override fun onTorchModeChanged(
                    id: String,
                    enabled: Boolean
                ) {
                    if (id != cameraId) return

                    torchEnabled = enabled
                    if (!enabled) currentLevel = defaultLevel
                    Safe.writeBoolean(Safe.FLASH_ACTIVE, enabled)
                }

                override fun onTorchStrengthLevelChanged(
                    id: String,
                    newStrengthLevel: Int
                ) {
                    if (id != cameraId) return

                    currentLevel = newStrengthLevel
                }
            },
            Handler(Looper.getMainLooper())
        )
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        // Let Android handle volume keys normally while the torch is off.
        if (!torchEnabled) {
            return false
        }

        val isVolumeButton =
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        if (!isVolumeButton) {
            return false
        }

        // Consume ACTION_UP too, otherwise Android may still change the volume.
        if (event.action != KeyEvent.ACTION_DOWN) {
            return true
        }

        // A single press changes exactly one level.
        if (event.repeatCount > 0) {
            return true
        }

        val id = cameraId ?: return false

        val newLevel = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                (currentLevel + 1).coerceAtMost(maxLevel)

            KeyEvent.KEYCODE_VOLUME_DOWN ->
                (currentLevel - 1).coerceAtLeast(1)

            else -> currentLevel
        }

        if (newLevel != currentLevel) {
            try {
                cameraManager.turnOnTorchWithStrengthLevel(
                    id,
                    newLevel
                )

                currentLevel = newLevel

                Log.i(
                    "FlashDim Service",
                    "Torch level: $currentLevel/$maxLevel"
                )
            } catch (e: Exception) {
                Log.e(
                    "FlashDim Service",
                    "Unable to change torch level",
                    e
                )
            }
        }

        return true
    }

    override fun onInterrupt() {
        Log.i(
            "FlashDim Service",
            "VolumeButtonService interrupted"
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {}
}