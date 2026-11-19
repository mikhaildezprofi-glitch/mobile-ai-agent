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
    fun exactReplacementRequiresUniqueMatchAndCurrentSha() {
        workspace.writeFile("src/Feature.kt", "fun first() = 1\\nfun second() = 2\\n")
        val before = workspace.readFile("src/Feature.kt")

        val updatedSha = workspace.replaceText(
            "src/Feature.kt",
            before.sha256,
            "fun second() = 2",
            "fun second() = 42"
        )
        val updated = workspace.readFile("src/Feature.kt")
        assertEquals(updatedSha, updated.sha256)
        assertTrue(updated.content.contains("fun second() = 42"))

        var duplicateRejected = false
        try {
            workspace.replaceText(
                "src/Feature.kt",
                updated.sha256,
                "fun",
                "suspend fun"
            )
        } catch (_: IllegalArgumentException) {
            duplicateRejected = true
        }
        assertTrue(duplicateRejected)

        var staleRejected = false
        try {
            workspace.replaceText(
                "src/Feature.kt",
                before.sha256,
                "fun first() = 1",
                "fun first() = 0"
            )
        } catch (_: IllegalArgumentException) {
            staleRejected = true
        }
        assertTrue(staleRejected)
        assertTrue(workspace.readFile("src/Feature.kt").content.contains("fun first() = 1"))
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
        File(root, "build").apply { mkdirs() }.resolve("generated.kt").writeText("class Generated")
        File(root, ".git").apply { mkdirs() }.resolve("config").writeText("secret = true")

        val files = workspace.listFiles(maxDepth = 5)
        assertTrue(files.contains("src/Main.kt"))
        assertFalse(files.any { it.startsWith("build/") || it.startsWith(".git/") })

        val matches = workspace.search("class")
        assertEquals(1, matches.size)
        assertTrue(matches.single().contains("src/Main.kt:1"))
    }

    @Test
    fun listingDoesNotFollowSymlinkCyclesOrExternalLinks() {
        workspace.writeFile("src/Main.kt", "class Main")
        val sourceDirectory = File(root, "src")
        val loop = File(sourceDirectory, "loop")
        val external = Files.createTempDirectory("jimmy-outside-workspace")
        try {
            Files.createSymbolicLink(loop.toPath(), root.toPath())
            Files.createSymbolicLink(File(root, "outside-link").toPath(), external)
            File(external.toFile(), "secret.kt").writeText("secret")

            val files = workspace.listFiles(maxDepth = 8)

            assertTrue(files.contains("src/Main.kt"))
            assertFalse(files.any { it.contains("loop") || it.contains("outside-link") })
        } finally {
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun refusesToReadOrWriteThroughSymbolicLinks() {
        val outside = Files.createTempDirectory("jimmy-outside-file")
        try {
            File(outside.toFile(), "secret.kt").writeText("private")
            Files.createSymbolicLink(File(root, "linked.kt").toPath(), File(outside.toFile(), "secret.kt").toPath())

            var readRejected = false
            try {
                workspace.readFile("linked.kt")
            } catch (_: IllegalArgumentException) {
                readRejected = true
            }
            assertTrue(readRejected)

            var writeRejected = false
            try {
                workspace.writeFile("linked.kt", "overwritten")
            } catch (_: IllegalArgumentException) {
                writeRejected = true
            }
            assertTrue(writeRejected)
            assertEquals("private", File(outside.toFile(), "secret.kt").readText())
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun toolRegistryExposesOnlySandboxedCodeOperations() {
        val tools = CodeAgentToolRegistry(workspace)
        val definitions = tools.definitions()
        val names = (0 until definitions.length()).map {
            definitions.getJSONObject(it).getJSONObject("function").getString("name")
        }

        assertEquals(
            listOf("code_list_files", "code_read_file", "code_search", "code_replace_text", "code_write_file"),
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
