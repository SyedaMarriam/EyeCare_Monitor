package com.eyecare.monitor

import android.content.Context
import android.provider.Settings
import android.util.Log
class BrightnessManager(private val context: Context) {

    private val TAG = "BrightnessManager"
    private var originalBrightnessMode: Int = -1
    private var originalBrightnessValue: Int = -1

    fun canWriteSettings(): Boolean {
        return Settings.System.canWrite(context)
    }

    /**
     * Saves the current brightness settings and lowers it to a minimum safe value.
     */
    fun dimScreen() {
        if (!canWriteSettings()) return

        try {
            // Save current settings if not already saved
            if (originalBrightnessMode == -1) {
                originalBrightnessMode = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE
                )
                originalBrightnessValue = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
            }

            // Set to manual mode if it's automatic
            if (originalBrightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }

            // Set brightness to a low value (e.g., 20 out of 255)
            val dimValue = 20
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                dimValue
            )
            Log.d(TAG, "Screen dimmed to $dimValue")
        } catch (e: Settings.SettingNotFoundException) {
            Log.e(TAG, "Error dimming screen: ${e.message}")
        }
    }

    /**
     * Restores the brightness to the user's original settings.
     */
    fun restoreScreen() {
        if (!canWriteSettings() || originalBrightnessMode == -1) return

        try {
            // Restore mode
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                originalBrightnessMode
            )

            // If it was manual, restore the exact value
            if (originalBrightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    originalBrightnessValue
                )
            }
            Log.d(TAG, "Screen brightness restored")
            
            // Reset saved state
            originalBrightnessMode = -1
            originalBrightnessValue = -1
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring screen brightness: ${e.message}")
        }
    }
}
