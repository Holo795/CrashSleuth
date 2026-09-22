package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Report
import dev.holo795.crashsleuth.model.Situation
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareLinkTest {
    @Test
    fun `a report survives the round trip through a link`() {
        val report = Report(
            Environment(Platform.NEOFORGE, "1.21.1", "21.1.233"),
            listOf(Finding(Situation.CLIENT_ONLY_ON_SERVER, Confidence.HIGH, listOf(Culprit(CulpritKind.MOD, "statuseffectbars", "Status Effect Bars", "1.0.2")), listOf("<script>alert(1)</script>"))),
        )
        val analysis = Analysis(Target("/srv/pack.mrpack", TargetKind.PACK), report)
        val shared = ShareLink.build(analysis, Messages(Locale.FRENCH))
        val link = ShareLink.link(shared)
        assertTrue(link.startsWith(ShareLink.VIEWER + "#r="))
        // Only what is safe in a URL fragment.
        assertTrue(link.substringAfter("#r=").all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val back = ShareLink.decode(link.substringAfter("#r="))
        assertEquals("fr", back.lang)
        assertEquals("Status Effect Bars", back.findings.single().culprits.single().name)
        assertEquals(listOf("NeoForge 21.1.233", "Minecraft 1.21.1"), back.facts)
        // Text is carried as text: the viewer never interprets it as HTML.
        assertEquals("<script>alert(1)</script>", back.findings.single().evidence.single())
    }
}
