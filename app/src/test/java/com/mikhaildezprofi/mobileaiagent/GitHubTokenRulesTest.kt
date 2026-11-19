package com.mikhaildezprofi.mobileaiagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTokenRulesTest {
    @Test fun acceptsClassicAndFineGrainedTokenFormats() {
        assertTrue(GitHubTokenStore.isValidTokenFormat("ghp_" + "a".repeat(36)))
        assertTrue(GitHubTokenStore.isValidTokenFormat("github_pat_" + "a".repeat(60)))
    }

    @Test fun rejectsTokensOutsideSupportedLength() {
        assertFalse(GitHubTokenStore.isValidTokenFormat("short"))
        assertFalse(GitHubTokenStore.isValidTokenFormat("a".repeat(513)))
    }

    @Test fun rejectsWhitespaceAndControlCharacters() {
        assertFalse(GitHubTokenStore.isValidTokenFormat("ghp_" + "a".repeat(20) + " b"))
        assertFalse(GitHubTokenStore.isValidTokenFormat("ghp_" + "a".repeat(20) + "\n"))
    }

    @Test fun rejectsUnsupportedCharacters() {
        assertFalse(GitHubTokenStore.isValidTokenFormat("ghp_" + "a".repeat(20) + "-"))
    }
}
