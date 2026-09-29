/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.metrolist.music.constants.BluetoothAutoPlayDevicesKey
import com.metrolist.music.constants.ResumeOnBluetoothConnectKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Starts playback when a Bluetooth audio device (car, headphones) connects, even when
 * Metrolist is not running. While MusicService is running, its AudioDeviceCallback handles
 * the same event; MusicService ignores the request if music is already playing.
 */
class BluetoothAutoPlayReceiver : BroadcastReceiver() {
    // The broadcast itself is only delivered when BLUETOOTH_CONNECT is granted.
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
        if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val address = runCatching { device?.address }.getOrNull()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.dataStore.data.first()
                if (prefs[ResumeOnBluetoothConnectKey] != true) return@launch
                val allowedDevices = parseBluetoothDevices(prefs[BluetoothAutoPlayDevicesKey])
                if (allowedDevices.isNotEmpty() && address !in allowedDevices) return@launch

                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MusicService::class.java).setAction(MusicService.ACTION_BLUETOOTH_AUTOPLAY),
                )
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException on some OEM builds
                Timber.w(e, "Bluetooth auto play failed")
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Addresses of the devices chosen for auto play. Empty means every device. */
fun parseBluetoothDevices(raw: String?): Set<String> =
    raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

fun serializeBluetoothDevices(addresses: Set<String>): String = addresses.sorted().joinToString(",")
