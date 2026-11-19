package com.mikhaildezprofi.mobileaiagent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodeWorkspaceTest {
    private val root = Files.createTempDirectory("jimmy-code-workspace").toFile()
    private val workspace = CodeWorkspace(root)

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun createsAndReadsSourceFileWithSha256() {
        val sha = workspace.writeFile("src/Main.kt", "fun main() = Unit")
        val read = workspace.readFile("src/Main.kt")

        assertEquals("fun main() = Unit", read.content)
        assertEquals(sha, read.sha256)
        assertEquals("src/Main.kt", read.path)
    }

    @Test
    fun replacementRequiresMatchingCurrentHash() {
        workspace.writeFile("Main.kt", "val value = 1")
        val current = workspace.readFile("Main.kt")

        var staleRejected = false
        try {
            workspace.writeFile("Main.kt", "val value = 2", "stale-hash")
        } catch (_: IllegalArgumentException) {
            staleRejected = true
        }
        assertTrue(staleRejected)
        assertEquals(current.content, workspace.readFile("Main.kt").content)

        workspace.writeFile("Main.kt", "val value = 2", current.sha256)
        assertEquals("val value = 2", workspace.readFile("Main.kt").content)
    }

    @Test
    fun rejectsAbsoluteAndParentTraversalPaths() {
        val paths = listOf("../outside.txt", "/sdcard/private.txt", "src/../../outside.txt")
        paths.forEach { path ->
            var rejected = false
            try {
                workspace.readFile(path)
            } catch (_: IllegalArgumentException) {
                rejected = true
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Path should be rejected: $path", rejected)
        }
    }

    @Test
    fun listingAndSearchSkipBuildAndGitDirectories() {
        workspace.writeFile("src/Main.kt", "class Main")
        workspace.writeFile("build/generated.kt", "class Generated")
        workspace.writeFile(".git/config", "secret = true")

        val files = workspace.listFiles(maxDepth = 5)
        assertTrue(files.contains("src/Main.kt"))
        assertFalse(files.any { it.startsWith("build/") || it.startsWith(".git/") })

        val matches = workspace.search("class")
        assertEquals(1, matches.size)
        assertTrue(matches.single().contains("src/Main.kt:1"))
    }

    @Test
    fun toolRegistryExposesOnlySandboxedCodeOperations() {
        val tools = CodeAgentToolRegistry(workspace)
        val definitions = tools.definitions()
        val names = (0 until definitions.length()).map {
            definitions.getJSONObject(it).getJSONObject("function").getString("name")
        }

        assertEquals(
            listOf("code_list_files", "code_read_file", "code_search", "code_write_file"),
            names
        )
        assertFalse(names.any { it.contains("shell") || it.contains("exec") || it.contains("delete") })

        val result = JSONObject(tools.execute(
            "code_write_file",
            JSONObject().put("path", "app/src/Main.kt").put("content", "package sample")
        ))
        assertEquals("saved_in_private_workspace", result.getString("status"))
        assertEquals("package sample", workspace.readFile("app/src/Main.kt").content)
    }

    @Test
    fun toolWriteReturnsStructuredErrorForStaleHash() {
        workspace.writeFile("Main.kt", "one")
        val result = JSONObject(tools().execute(
            "code_write_file",
            JSONObject()
                .put("path", "Main.kt")
                .put("content", "two")
                .put("expected_sha256", "not-the-current-hash")
        ))

        assertEquals("not_executed", result.getString("status"))
        assertTrue(result.getString("error").contains("изменился"))
        assertEquals("one", workspace.readFile("Main.kt").content)
    }

    private fun tools() = CodeAgentToolRegistry(workspace)
}
