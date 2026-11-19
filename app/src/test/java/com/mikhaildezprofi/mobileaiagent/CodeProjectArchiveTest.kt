package com.mikhaildezprofi.mobileaiagent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CodeProjectArchiveTest {
    private val root = Files.createTempDirectory("jimmy-project-archive").toFile()
    private val workspace = CodeWorkspace(root)

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun importsAndExportsProjectArchive() {
        val archive = zipOf(
            "src/Main.kt" to "fun main() = Unit",
            "settings.gradle.kts" to "rootProject.name = \"Sample\""
        )

        val imported = CodeProjectArchive.importZip(
            workspace,
            "sample",
            ByteArrayInputStream(archive)
        )

        assertEquals(2, imported.filesImported)
        assertEquals("fun main() = Unit", workspace.readFile("sample/src/Main.kt").content)

        val exported = ByteArrayOutputStream()
        assertEquals(2, CodeProjectArchive.exportZip(workspace, "sample", exported))

        val copied = CodeProjectArchive.importZip(
            workspace,
            "copy",
            ByteArrayInputStream(exported.toByteArray())
        )
        assertEquals(2, copied.filesImported)
        assertEquals(
            "rootProject.name = \"Sample\"",
            workspace.readFile("copy/settings.gradle.kts").content
        )
    }

    @Test
    fun rejectsArchivePathTraversalAndCleansStagingDirectory() {
        val outsideName = "jimmy-archive-escape-${root.name}.txt"
        val archive = zipOf("../$outsideName" to "not allowed")
        var rejected = false
        try {
            CodeProjectArchive.importZip(workspace, "bad", ByteArrayInputStream(archive))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(!java.io.File(root.parentFile, outsideName).exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".jimmy-import-") })
    }

    @Test
    fun skipsGitBuildAndDependencyDirectories() {
        val archive = zipOf(
            "src/Main.kt" to "class Main",
            ".git/config" to "secret",
            "build/generated.kt" to "generated",
            "node_modules/package/index.js" to "dependency"
        )
        val result = CodeProjectArchive.importZip(workspace, "clean", ByteArrayInputStream(archive))

        assertEquals(1, result.filesImported)
        assertEquals(listOf("clean/src/Main.kt"), workspace.listFiles(maxDepth = 8))
    }

    @Test
    fun rejectsDuplicateProjectNamesAndInvalidNames() {
        CodeProjectArchive.importZip(
            workspace,
            "sample",
            ByteArrayInputStream(zipOf("Main.kt" to "one"))
        )
        var duplicateRejected = false
        try {
            CodeProjectArchive.importZip(
                workspace,
                "sample",
                ByteArrayInputStream(zipOf("Main.kt" to "two"))
            )
        } catch (_: IllegalArgumentException) {
            duplicateRejected = true
        }
        assertTrue(duplicateRejected)

        var invalidNameRejected = false
        try {
            CodeProjectArchive.importZip(
                workspace,
                "../escape",
                ByteArrayInputStream(zipOf("Main.kt" to "x"))
            )
        } catch (_: IllegalArgumentException) {
            invalidNameRejected = true
        }
        assertTrue(invalidNameRejected)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
