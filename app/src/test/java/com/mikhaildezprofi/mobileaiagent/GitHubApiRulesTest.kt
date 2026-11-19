package com.mikhaildezprofi.mobileaiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubApiRulesTest {
    @Test fun acceptsOnlyCodeAgentBranchesForWrites() {
        assertEquals(true, GitHubApiClient.isAgentBranch("code-agent/task-123"))
        assertEquals(false, GitHubApiClient.isAgentBranch("main"))
        assertEquals(false, GitHubApiClient.isAgentBranch("integration/agent-consolidated-20261119"))
    }

    @Test fun rejectsTraversalAndMalformedBranchNames() {
        listOf("code-agent/../main", "code-agent//bad", "code-agent/name/", "code-agent/a b")
            .forEach { branch ->
                assertEquals(false, GitHubApiClient.isAgentBranch(branch))
            }
    }

    @Test fun acceptsSafeNestedWorkspacePaths() {
        assertEquals("app/src/Main.kt", GitHubApiClient.normalizeWorkspacePath("app/src/Main.kt"))
    }

    @Test fun rejectsTraversalAndAbsoluteWorkspacePaths() {
        listOf("../outside.txt", "/absolute.txt", "src/../outside.kt", ".git/config")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    GitHubApiClient.normalizeWorkspacePath(path)
                }
            }
    }

    @Test fun rejectsSecretsAndGeneratedFiles() {
        listOf(".env", "release.keystore", "signing.p12", "local.properties", "model.gguf", "app.apk")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    GitHubApiClient.normalizeWorkspacePath(path)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubApiClient.normalizeWorkspacePath("app/build/output.txt")
        }
    }
}
