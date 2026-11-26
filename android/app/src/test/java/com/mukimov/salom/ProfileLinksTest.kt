package com.mukimov.salom

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileLinksTest {
    @Test fun shortAndAppLinksResolveToTheSameUsername() {
        val url = "https://mukimov.site/mukimovmuhammad"
        assertEquals(url, ProfileLinks.shareUrl(" @MukimovMuhammad "))
        assertEquals("mukimovmuhammad", ProfileLinks.username(url))
        assertEquals("mukimovmuhammad", ProfileLinks.username("https://mukimov.site/u/MukimovMuhammad"))
        assertEquals("mukimovmuhammad", ProfileLinks.username("salom://profile/MukimovMuhammad"))
        assertEquals(listOf(url), ProfileLinks.inMessage.findAll("Вот ссылка: $url.").map { it.value }.toList())
    }

    @Test fun websiteRoutesAndUntrustedUrlsCannotOpenAChat() {
        listOf("https://mukimov.site/privacy", "https://mukimov.site/terms", "https://mukimov.site/salom",
            "https://mukimov.site.evil.example/mukimovmuhammad", "https://evil.example/u/mukimovmuhammad",
            "https://name@mukimov.site/mukimovmuhammad", "https://mukimov.site:9999/mukimovmuhammad",
            "https://mukimov.site/u/mukimovmuhammad/other", "https://mukimov.site/u/%61bcd",
            "salom://another/mukimovmuhammad").forEach { assertNull(it, ProfileLinks.username(it)) }
        assertNull(ProfileLinks.shareUrl("невалидный"))
        assertEquals("https://mukimov.site/u/terms", ProfileLinks.shareUrl("Terms"))
        assertEquals("terms", ProfileLinks.username("https://mukimov.site/u/terms"))
    }

    @Test fun everyQrStyleDecodesToTheSameLink() {
        val url = "https://mukimov.site/mukimovmuhammad"
        val edge = ProfileQr.EDGE
        ProfileQrStyle.entries.forEach { style ->
            val pixels = ProfileQr.pixels(url, style)
            val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(edge, edge, pixels)))
            assertEquals(style.name, url, QRCodeReader().decode(image).text)
        }
    }
}
