/*
 * opentvcast — open-source casting receiver for Android TV
 * Copyright (C) 2026 opentvcast contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version. See <https://www.gnu.org/licenses/>.
 */

package tv.opentvcast

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The network policy the media players depend on: cleartext HTTP must stay permitted.
 *
 * WHY A TEST FOR A CONFIG FILE: without it, every `http://` media URL fails inside `MediaPlayer`
 * with a `Cleartext HTTP traffic … not permitted` IOException. The user sees a black screen, the
 * receiver logs an error nobody reads, and *nothing else in the suite notices* — the mirroring path
 * does not use HTTP, so the whole test suite stays green while URL playback and DLNA playback are
 * both dead. That is exactly how the defect reached a real TV.
 *
 * The hosts cannot be narrowed to a list: the URL is chosen by the AirPlay sender or the DLNA
 * control point, not by us. See the comment in the config file itself.
 */
class MediaNetworkPolicyTest {

    private val manifest = moduleFile("src/main/AndroidManifest.xml")
    private val config = moduleFile("src/main/res/xml/network_security_config.xml")

    @Test
    fun `the manifest points at the network security config`() {
        val text = manifest.readText()

        assertTrue(
            "without this attribute Android ignores the config and blocks http media URLs",
            text.contains("""android:networkSecurityConfig="@xml/network_security_config""""),
        )
    }

    @Test
    fun `that config permits cleartext for every host`() {
        val text = config.readText()

        assertTrue("media URLs are plain http on a LAN", text.contains("cleartextTrafficPermitted=\"true\""))
        // A domain-scoped override cannot work here: the hosts are named by the sender.
        assertTrue("a domain list would re-break every other sender", !text.contains("<domain-config"))
    }

    /**
     * Locates a source file from wherever the test JVM was started.
     *
     * These sources are compiled twice: by `:app`, whose working directory is `app/`, and by
     * `:test-runner`, whose working directory is `test-runner/`. Neither is a stable base, so the
     * search walks up from wherever it starts instead of assuming one.
     */
    private fun moduleFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}
