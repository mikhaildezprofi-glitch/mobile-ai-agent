package com.mikhaildezprofi.mobileaiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubApiRulesTest {
    @Test fun acceptsOnlyCodeAgentBranches() {
        GitHubBranchRules.requireWorkingBranch("code-agent/task-123")
        assertThrows(IllegalArgumentException::class.java) {
            GitHubBranchRules.requireWorkingBranch("main")
        }
    }

    @Test fun rejectsTraversalAndMalformedBranchNames() {
        listOf("code-agent/../main", "code-agent//bad", "code-agent/name/", "code-agent/a b")
            .forEach { branch ->
                assertThrows(IllegalArgumentException::class.java) {
                    GitHubBranchRules.requireWorkingBranch(branch)
                }
            }
    }

    @Test fun validatesRepositoryIdentity() {
        assertEquals("mobile-ai-agent", GitHubRepositoryConfig("mikhaildezprofi-glitch", "mobile-ai-agent").name)
        assertThrows(IllegalArgumentException::class.java) {
            GitHubRepositoryConfig("../owner", "repo")
        }
    }

    @Test fun rejectsUnsafeFilePaths() {
        listOf("../outside.txt", "/absolute.txt", "src/../outside.kt", ".git/config", "src\\outside.kt")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    GitHubFileRules.validate(mapOf(path to "content"))
                }
            }
    }

    @Test fun acceptsBoundedUtf8Files() {
        val files = GitHubFileRules.validate(mapOf("src/Main.kt" to "Привет"))
        assertEquals("Привет", files.getValue("src/Main.kt"))
    }

    @Test fun rejectsTooManyFiles() {
        val files = (1..101).associate { "file$it.txt" to "x" }
        assertThrows(IllegalArgumentException::class.java) { GitHubFileRules.validate(files) }
    }
}
