package com.quirogaj.vistio

import com.quirogaj.vistio.data.update.GitHubUpdateSource
import com.quirogaj.vistio.data.update.UpdateChannel
import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {
    @Test fun configuredRepositoryCannotInjectUrlParts() {
        assertNotNull(UpdateChannel.from("quirogajqa", "vistio-updates", ""))
        assertNull(UpdateChannel.from("owner/repo", "vistio-updates", ""))
        assertNull(UpdateChannel.from("owner", "../other-repo", ""))
        assertNull(UpdateChannel.from("owner", "..", ""))
    }

    @Test fun releasesAllowOnlyHttpsToGitHubHosts() {
        assertTrue(GitHubUpdateSource.allowed(url("https://api.github.com/repos/owner/vistio/releases/latest")))
        assertTrue(GitHubUpdateSource.allowed(url("https://release-assets.githubusercontent.com/asset")))
        assertFalse(GitHubUpdateSource.allowed(url("http://github.com/file")))
        assertFalse(GitHubUpdateSource.allowed(url("https://github.com.attacker.example/file")))
        assertFalse(GitHubUpdateSource.allowed(url("https://user@github.com/file")))
        assertFalse(GitHubUpdateSource.allowed(url("https://github.com:4444/file")))
    }

    private fun url(value: String) = URI.create(value).toURL()
}
