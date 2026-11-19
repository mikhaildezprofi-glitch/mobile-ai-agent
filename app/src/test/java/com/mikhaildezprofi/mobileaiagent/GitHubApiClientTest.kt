package com.mikhaildezprofi.mobileaiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GitHubApiClientTest {
    private class Token : GitHubTokenReader {
        override fun read(): String = "ghp_test_token_value_1234567890"
    }

    private class Response(
        url: URL,
        private val status: Int,
        private val body: String
    ) : HttpURLConnection(url) {
        val headers = mutableMapOf<String, String>()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() =
            if (status >= 400) ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) else null
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
    }

    @Test
    fun validateConnectionUsesPinnedApiHostAndBearerToken() {
        lateinit var captured: Response
        val client = GitHubApiClient(Token()) { url ->
            Response(url, 200, """{"full_name":"mikhaildezprofi-glitch/mobile-ai-agent","default_branch":"main","private":false}""")
                .also { captured = it }
        }

        val info = client.validateConnection()

        assertEquals("mikhaildezprofi-glitch/mobile-ai-agent", info.fullName)
        assertEquals("main", info.defaultBranch)
        assertEquals("Bearer ghp_test_token_value_1234567890", captured.headers["Authorization"])
        assertTrue(captured.url.host == "api.github.com")
    }

    @Test
    fun mapsUnauthorizedResponseToTypedExceptionWithoutLeakingToken() {
        val client = GitHubApiClient(Token()) { url ->
            Response(url, 401, """{"message":"Bad credentials"}""")
        }

        try {
            client.validateConnection()
            throw AssertionError("Expected GitHubApiException")
        } catch (error: GitHubApiException) {
            assertEquals(401, error.httpStatus)
            assertTrue(error.message.orEmpty().contains("токен"))
            assertFalse(error.message.orEmpty().contains("ghp_test_token"))
            assertTrue(error.responseBody.contains("Bad credentials"))
        }
    }

    @Test
    fun rejectsOptimisticConcurrencyConflictBeforeCreatingBlobs() {
        var requests = 0
        val remoteSha = "b".repeat(40)
        val client = GitHubApiClient(Token()) { url ->
            requests++
            Response(url, 200, """{"object":{"sha":"$remoteSha"}}""")
        }

        try {
            client.commitWorkspaceChanges(
                branch = "code-agent/test-branch",
                expectedHeadSha = "a".repeat(40),
                files = listOf(GitHubWorkspaceFile("README.md", "change")),
                message = "test"
            )
            throw AssertionError("Expected stale-head rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("изменилась"))
            assertEquals(1, requests)
        }
    }

    @Test
    fun rejectsUnsafeBranchNamesAndAcceptsOnlyCodeAgentWriteNamespace() {
        assertTrue(GitHubApiClient.isAgentBranch("code-agent/task-123"))
        assertFalse(GitHubApiClient.isAgentBranch("main"))
        listOf("../main", "code-agent/../main", "code-agent//bad", "code-agent/.hidden", "code-agent/name.lock")
            .forEach { assertFalse("Must reject $it", GitHubApiClient.isSafeBranchName(it)) }
    }

    @Test
    fun logArchiveIsReadAsTextAndNeverExtractedToFilesystem() {
        val bytes = zipOf("../outside.txt" to "log line", "job/step.txt" to "second line")
        val client = GitHubApiClient(Token()) { url -> Response(url, 200, "{}") }

        val extracted = client.extractJobLogArchive(bytes)

        assertTrue(extracted.contains("../outside.txt"))
        assertTrue(extracted.contains("log line"))
        assertTrue(extracted.contains("second line"))
    }

    @Test
    fun rejectsUnsafeAndOverlappingDeletionPathsBeforeNetwork() {
        var requests = 0
        val client = GitHubApiClient(Token()) { url ->
            requests++
            Response(url, 500, "{}")
        }

        try {
            client.commitWorkspaceChanges(
                branch = "code-agent/test",
                expectedHeadSha = "a".repeat(40),
                files = listOf(GitHubWorkspaceFile("README.md", "content")),
                message = "test",
                deletePaths = listOf("README.md")
            )
            throw AssertionError("Expected overlapping path rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, requests)
        }

        try {
            client.commitWorkspaceChanges(
                branch = "code-agent/test",
                expectedHeadSha = "a".repeat(40),
                files = emptyList(),
                message = "test",
                deletePaths = listOf("../outside.txt")
            )
            throw AssertionError("Expected traversal rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, requests)
        }
    }

    @Test
    fun rejectsLogArchivesWithTooManyEntries() {
        val entries = (1..51).map { "job/$it.txt" to "x" }
        val client = GitHubApiClient(Token()) { url -> Response(url, 200, "{}") }

        try {
            client.extractJobLogArchive(zipOf(*entries.toTypedArray()))
            throw AssertionError("Expected entry-count limit")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("слишком много"))
        }
    }

    @Test
    fun rejectsOversizedIndividualLogEntry() {
        val largeText = "x".repeat(2 * 1024 * 1024 + 1)
        val client = GitHubApiClient(Token()) { url -> Response(url, 200, "{}") }

        try {
            client.extractJobLogArchive(zipOf("job/large.txt" to largeText))
            throw AssertionError("Expected entry-size limit")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("размер"))
        }
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
