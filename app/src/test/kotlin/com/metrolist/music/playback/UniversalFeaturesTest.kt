/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.exoplayer.scheduler.Requirements
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalFeaturesTest {
    @Test
    fun `bluetooth device list round trips`() {
        assertTrue(parseBluetoothDevices(null).isEmpty())
        assertTrue(parseBluetoothDevices("").isEmpty())
        val devices = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66")
        assertEquals(devices, parseBluetoothDevices(serializeBluetoothDevices(devices)))
        assertEquals(setOf("AA:BB"), parseBluetoothDevices(" AA:BB , ,"))
    }

    @Test
    fun `download requirements`() {
        assertEquals(Requirements.NETWORK, downloadRequirements(wifiOnly = false, chargingOnly = false))
        assertEquals(Requirements.NETWORK_UNMETERED, downloadRequirements(wifiOnly = true, chargingOnly = false))
        assertEquals(
            Requirements.NETWORK_UNMETERED or Requirements.DEVICE_CHARGING,
            downloadRequirements(wifiOnly = true, chargingOnly = true),
        )
    }

    @Test
    fun `new android auto sections are appended to a saved order`() {
        val sections = deserializeSections("playlists:true,liked:false")
        assertEquals(AndroidAutoSection.PLAYLISTS to true, sections[0])
        assertEquals(AndroidAutoSection.LIKED to false, sections[1])
        assertEquals(AndroidAutoSection.values().size, sections.size)
        assertTrue(sections.contains(AndroidAutoSection.RECENT to true))
        assertTrue(sections.contains(AndroidAutoSection.DOWNLOADED to true))
    }
}
