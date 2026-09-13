/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.luciad.imageio.webp

/**
 * Bridge into the package-private [WebP] encoder of sejda's webp-imageio so
 * GenerateIconPackTask can encode WebP rasters without going through the
 * ImageIO service-registry lookup, which is unreliable inside the Gradle
 * daemon's classloader hierarchy.
 */
object WebPBridge {
    fun encodeRgba(
        options: WebPEncoderOptions,
        rgba: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
    ): ByteArray = WebP.encodeRGBA(options, rgba, width, height, stride)
}
