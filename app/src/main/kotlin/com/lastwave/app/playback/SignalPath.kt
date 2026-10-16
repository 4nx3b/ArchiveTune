/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.lastwave.app.playback

data class UsbDacInfo(
    val name: String,
    val vendorId: Int = -1,
    val productId: Int = -1,

    val sampleRatesHz: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),

    val usbPermissionGranted: Boolean = false,

    val hasUsbPeripheral: Boolean = false,

    val deviceId: Int = -1,
)
