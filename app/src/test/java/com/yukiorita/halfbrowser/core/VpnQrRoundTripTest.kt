package com.yukiorita.halfbrowser.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Round-trips a wg-quick config through an actual QR bitmap: encodes with the
 * same zxing family the in-app scanner uses, decodes it pixel-by-pixel, and
 * requires the decoded text to pass the strict profile parser.
 *
 * This proves QR payloads need no special import handling beyond decoding:
 * whatever the camera reads flows through exactly the same parse-and-review
 * path as a pasted or file-imported config.
 */
class VpnQrRoundTripTest {

    private fun key(seed: Int): String {
        val bytes = ByteArray(32) { (seed + it).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun encodeThenDecode(text: String): String {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(width, height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.PURE_BARCODE to true)
        val result = MultiFormatReader().decode(bitmap, hints)
        return result.text
    }

    @Test
    fun `wireguard config survives a qr round trip and parses`() {
        val config = """
            [Interface]
            PrivateKey = ${key(0x11)}
            Address = 10.0.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = ${key(0x22)}
            Endpoint = 203.0.113.1:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val decoded = encodeThenDecode(config)
        assertEquals(config, decoded)

        val parsed = VpnConfigParser.parse(decoded)
        assertTrue(parsed is VpnConfigResult.Ok)
        parsed as VpnConfigResult.Ok
        assertEquals(decoded, parsed.config.source)
    }

    @Test
    fun `multi peer config fits in one qr and parses`() {
        val config = """
            [Interface]
            PrivateKey = ${key(0x11)}
            Address = fd00::2/128, 10.0.0.2/32
            ListenPort = 51821

            [Peer]
            PublicKey = ${key(0x22)}
            Endpoint = vpn.example.net:51820
            AllowedIPs = 0.0.0.0/0, ::/0

            [Peer]
            PublicKey = ${key(0x33)}
            Endpoint = [2001:db8::1]:51820
            AllowedIPs = 10.0.8.0/24
        """.trimIndent()
        assertTrue(config.length <= VpnConfigParser.MAX_BYTES)

        val decoded = encodeThenDecode(config)
        assertEquals(config, decoded)
        assertTrue(VpnConfigParser.parse(decoded) is VpnConfigResult.Ok)
    }

    @Test
    fun `a qr that is not a wireguard config is rejected by the parser`() {
        val decoded = encodeThenDecode("https://studio-rizi.pages.dev/projects/half-browser/")
        assertTrue(VpnConfigParser.parse(decoded) !is VpnConfigResult.Ok)
    }
}
