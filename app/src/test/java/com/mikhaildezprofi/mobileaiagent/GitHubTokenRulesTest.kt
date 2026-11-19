package com.mikhaildezprofi.mobileaiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubTokenRulesTest {
    @Test fun trimsOnlyOuterWhitespace() {
        val token = "ghp_" + "a".repeat(36)
        assertEquals(token, GitHubTokenRules.normalize("  $token\n"))
    }
    @Test fun rejectsTooShortTokens() {
        assertThrows(IllegalArgumentException::class.java) { GitHubTokenRules.normalize("short-token") }
    }
    @Test fun rejectsInternalWhitespace() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubTokenRules.normalize("ghp_" + "a".repeat(18) + " " + "b".repeat(18))
        }
    }
    @Test fun rejectsControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubTokenRules.normalize("ghp_" + "a".repeat(18) + "\u0000" + "b".repeat(18))
        }
    }
}
