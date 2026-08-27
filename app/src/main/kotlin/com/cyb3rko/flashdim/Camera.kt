/*
 * Copyright (c) 2022-2024 Cyb3rKo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyb3rko.flashdim

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.cyb3rko.flashdim.utils.Safe

internal class Camera(activity: AppCompatActivity) {
    private val cameraManager: CameraManager
    private val cameraId: String
    private var torchCallback: CameraManager.TorchCallback? = null
    val idEmpty: Boolean
        get() = cameraId.isEmpty()
    val maxLevel: Int
        get() {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            return characteristics[CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL] ?: -1
        }

    init {
        Safe.initialize(activity.applicationContext)
        cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = findFlashCameraId(activity, cameraManager)
    }

    private fun findFlashCameraId(
        activity: AppCompatActivity,
        cameraManager: CameraManager
    ): String {
        val firstCameraWithFlash = cameraManager.cameraIdList.find { camera ->
            cameraManager.getCameraCharacteristics(camera).keys.any { key ->
                key == CameraCharacteristics.FLASH_INFO_AVAILABLE
            }
        }

        if (firstCameraWithFlash != null) {
            return firstCameraWithFlash
        }
        showErrorDialog(activity, "Camera with flashlight not found") {
            activity.finish()
        }
        return ""
    }

    fun setTorchMode(enabled: Boolean) {
        if (!enabled || maxLevel <= MIN_LEVEL) {
            cameraManager.setTorchMode(cameraId, enabled)
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val defaultLevel = characteristics
            .get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: MIN_LEVEL
        val level = Safe.getInt(Safe.CURRENT_LEVEL, defaultLevel)
            .coerceIn(MIN_LEVEL, maxLevel)

        cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
        Safe.writeInt(Safe.CURRENT_LEVEL, level)
    }

    fun sendLightLevel(activity: AppCompatActivity, currentLevel: Int, level: Int) {
        if (currentLevel != level) {
            try {
                cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
                Safe.writeInt(Safe.CURRENT_LEVEL, level)
            } catch (e: Exception) {
                handleFlashlightException(e, activity)
            }
        }
    }

    fun registerTorchListener(
        onModeChanged: (Boolean) -> Unit,
        onStrengthChanged: (Int) -> Unit
    ) {
        if (torchCallback != null) return

        torchCallback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) onModeChanged(enabled)
            }

            override fun onTorchStrengthLevelChanged(id: String, newStrengthLevel: Int) {
                if (id == cameraId) onStrengthChanged(newStrengthLevel)
            }
        }.also { callback ->
            cameraManager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    fun unregisterTorchListener() {
        torchCallback?.let(cameraManager::unregisterTorchCallback)
        torchCallback = null
    }

    companion object {
        private const val MIN_LEVEL = 1
        fun doesDeviceHaveFlash(packageManager: PackageManager): Boolean =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

        fun getValidFlashLevel(
            cameraManager: CameraManager,
            cameraId: String,
            requestedLevel: Int
        ): Int {
            if (requestedLevel < 1) return -1

            val maxLevel = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                ?: return -1
            return requestedLevel.coerceAtMost(maxLevel)
        }

        fun sendLightLevel(context: Context, level: Int, activate: Boolean) {
            Safe.initialize(context)
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                    as CameraManager
                val cameraId = cameraManager.cameraIdList[0]
                if (activate) {
                    val validLevel = getValidFlashLevel(cameraManager, cameraId, level)
                    if (validLevel == -1) {
                        cameraManager.setTorchMode(cameraId, true)
                    } else {
                        cameraManager.turnOnTorchWithStrengthLevel(cameraId, validLevel)
                        Safe.writeInt(Safe.CURRENT_LEVEL, validLevel)
                    }
                } else {
                    cameraManager.setTorchMode(cameraId, false)
                }
            } catch (e: Exception) {
                handleFlashlightException(e)
            }
        }
    }
}
