package com.mikhaildezprofi.mobileaiagent

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubRepositoryInfo(
    val fullName: String,
    val defaultBranch: String,
    val isPrivate: Boolean
)

data class GitHubWorkingBranch(
    val name: String,
    val baseBranch: String,
    val baseSha: String
)

data class GitHubCommitResult(
    val branch: String,
    val commitSha: String,
    val changedFiles: Int
)

data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val headSha: String
)

data class GitHubJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?
)

data class GitHubWorkspaceFile(val path: String, val content: String)

class GitHubApiException(
    val httpStatus: Int,
    message: String,
    val responseBody: String = ""
) : IllegalStateException(message)

/**
 * Restricted GitHub API client for one configured repository.
 *
 * All writes are confined to dedicated Code Agent working branches. The client never accepts an arbitrary host,
 * repository, ref namespace, or workflow path from model-generated tool arguments.
 * Call from Dispatchers.IO; methods are synchronous by design.
 */
fun interface GitHubTokenReader { fun read(): String? }

class GitHubApiClient(
    private val tokenStore: GitHubTokenReader,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    private val owner = DEFAULT_OWNER
    private val repository = DEFAULT_REPOSITORY

    fun validateConnection(): GitHubRepositoryInfo {
        val json = request("GET", "/repos/$owner/$repository")
        return GitHubRepositoryInfo(
            fullName = json.getString("full_name"),
            defaultBranch = json.getString("default_branch"),
            isPrivate = json.optBoolean("private", false)
        )
    }

    fun createWorkingBranch(baseBranch: String? = null): GitHubWorkingBranch {
        val repoInfo = validateConnection()
        val selectedBase = baseBranch ?: repoInfo.defaultBranch
        require(isSafeBranchName(selectedBase)) { "Недопустимое имя базовой ветки." }

        val baseRef = request("GET", "/repos/$owner/$repository/git/ref/heads/${encodeRef(selectedBase)}")
        val baseSha = baseRef.getJSONObject("object").getString("sha")
        val branchName = "code-agent/${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"

        request(
            "POST",
            "/repos/$owner/$repository/git/refs",
            JSONObject()
                .put("ref", "refs/heads/$branchName")
                .put("sha", baseSha)
        )
        return GitHubWorkingBranch(branchName, selectedBase, baseSha)
    }

    /**
     * Pushes a bounded set of UTF-8 files as one atomic Git commit.
     * expectedHeadSha prevents overwriting a branch changed by another actor.
     */
    fun commitWorkspaceChanges(
        branch: String,
        expectedHeadSha: String,
        files: List<GitHubWorkspaceFile>,
        message: String,
        deletePaths: List<String> = emptyList()
    ): GitHubCommitResult {
        require(isAgentBranch(branch)) { "Запись разрешена только в code-agent/*." }
        require(expectedHeadSha.matches(Regex("[a-fA-F0-9]{40}"))) { "Некорректный SHA исходного коммита." }
        require(files.size + deletePaths.size in 1..MAX_FILES) { "Количество изменений должно быть от 1 до $MAX_FILES." }
        require(message.isNotBlank() && message.length <= 200) { "Некорректное сообщение коммита." }

        val normalized = files.map { file ->
            val path = normalizeWorkspacePath(file.path)
            val bytes = file.content.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FILE_BYTES) { "Файл слишком большой: $path" }
            path to bytes
        }
        require(normalized.map { it.first }.distinct().size == normalized.size) {
            "В наборе есть повторяющиеся пути."
        }
        val normalizedDeletes = deletePaths.map(::normalizeWorkspacePath)
        require(normalizedDeletes.distinct().size == normalizedDeletes.size) {
            "В списке удаления есть повторяющиеся пути."
        }
        require(normalizedDeletes.none { path -> normalized.any { it.first == path } }) {
            "Нельзя одновременно изменить и удалить один файл."
        }
        require(normalized.sumOf { it.second.size.toLong() } <= MAX_TOTAL_BYTES) {
            "Общий размер изменений превышает допустимый лимит."
        }

        val currentRef = request(
            "GET",
            "/repos/$owner/$repository/git/ref/heads/${encodeRef(branch)}"
        )
        val currentSha = currentRef.getJSONObject("object").getString("sha")
        require(currentSha == expectedHeadSha) {
            "Рабочая ветка изменилась после подготовки diff. Обновите состояние и повторите."
        }

        val parentCommit = request("GET", "/repos/$owner/$repository/git/commits/$currentSha")
        val baseTreeSha = parentCommit.getJSONObject("tree").getString("sha")

        val treeEntries = JSONArray()
        normalized.forEach { (path, bytes) ->
            val blob = request(
                "POST",
                "/repos/$owner/$repository/git/blobs",
                JSONObject()
                    .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("encoding", "base64")
            )
            treeEntries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blob.getString("sha"))
            )
        }
        normalizedDeletes.forEach { path ->
            // A null SHA tells GitHub's Git Trees API to remove this path.
            treeEntries.put(JSONObject().put("path", path).put("sha", JSONObject.NULL))
        }

        val tree = request(
            "POST",
            "/repos/$owner/$repository/git/trees",
            JSONObject().put("base_tree", baseTreeSha).put("tree", treeEntries)
        )
        val commit = request(
            "POST",
            "/repos/$owner/$repository/git/commits",
            JSONObject()
                .put("message", message)
                .put("tree", tree.getString("sha"))
                .put("parents", JSONArray().put(currentSha))
        )
        val commitSha = commit.getString("sha")

        // force=false: GitHub rejects a non-fast-forward update if the branch moved meanwhile.
        request(
            "PATCH",
            "/repos/$owner/$repository/git/refs/heads/${encodeRef(branch)}",
            JSONObject().put("sha", commitSha).put("force", false)
        )
        return GitHubCommitResult(branch, commitSha, normalized.size + normalizedDeletes.size)
    }

    fun dispatchUnitTests(branch: String) {
        require(isAgentBranch(branch)) { "Тесты Code Agent запускаются только для code-agent/*." }
        request(
            "POST",
            "/repos/$owner/$repository/actions/workflows/agent-unit-tests.yml/dispatches",
            JSONObject().put("ref", branch)
        )
    }

    fun listUnitTestRuns(branch: String, limit: Int = 10): List<GitHubWorkflowRun> {
        require(isAgentBranch(branch))
        val safeLimit = limit.coerceIn(1, 30)
        val json = request(
            "GET",
            "/repos/$owner/$repository/actions/workflows/agent-unit-tests.yml/runs?branch=${encodeQuery(branch)}&event=workflow_dispatch&per_page=$safeLimit"
        )
        val runs = json.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until runs.length()).mapNotNull { index ->
            val item = runs.optJSONObject(index) ?: return@mapNotNull null
            GitHubWorkflowRun(
                id = item.optLong("id"),
                name = item.optString("name", "Agent Unit Tests"),
                status = item.optString("status", "unknown"),
                conclusion = item.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
                htmlUrl = item.optString("html_url"),
                headSha = item.optString("head_sha")
            )
        }
    }

    fun listRunJobs(runId: Long): List<GitHubJob> {
        require(runId > 0)
        val json = request("GET", "/repos/$owner/$repository/actions/runs/$runId/jobs?per_page=100")
        val jobs = json.optJSONArray("jobs") ?: JSONArray()
        return (0 until jobs.length()).mapNotNull { index ->
            val item = jobs.optJSONObject(index) ?: return@mapNotNull null
            GitHubJob(
                id = item.optLong("id"),
                name = item.optString("name", "job"),
                status = item.optString("status", "unknown"),
                conclusion = item.optString("conclusion").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    fun getRunJobLog(runId: Long, jobId: Long): String {
        require(runId > 0 && jobId > 0)
        require(listRunJobs(runId).any { it.id == jobId }) {
            "Указанная job не относится к выбранному workflow run."
        }
        val connection = openConnection(
            "GET",
            "/repos/$owner/$repository/actions/jobs/$jobId/logs"
        )
        val status = connection.responseCode
        if (status in 300..399) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank() && location.startsWith("https://")) {
                "GitHub не вернул безопасную HTTPS-ссылку на архив журналов."
            }
            return downloadAndExtractJobLogs(location)
        }
        if (status !in 200..299) {
            val body = readBounded(connection.errorStream, MAX_RESPONSE_BYTES)
            connection.disconnect()
            throw GitHubApiException(status, "Не удалось получить журнал GitHub Actions.", body)
        }
        val bytes = readBoundedBytes(connection.inputStream, MAX_LOG_BYTES)
        connection.disconnect()
        return extractJobLogArchive(bytes)
    }

    /**
     * GitHub redirects job logs to a short-lived signed URL. The PAT is deliberately not copied
     * to that second request, because the redirect host is outside api.github.com.
     */
    private fun downloadAndExtractJobLogs(location: String): String {
        val connection = connectionFactory(URL(location)).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/zip")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw GitHubApiException(status, "Не удалось скачать архив журналов GitHub Actions.")
            }
            return extractJobLogArchive(readBoundedBytes(connection.inputStream, MAX_LOG_BYTES))
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractJobLogArchive(archive: ByteArray): String {
        val output = StringBuilder()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries++
                require(entries <= MAX_LOG_ENTRIES) { "В архиве слишком много файлов журналов." }
                val bytes = readBoundedBytes(zip, MAX_LOG_ENTRY_BYTES)
                if (output.isNotEmpty()) output.append('\n')
                output.append("===== ").append(entry.name.take(160)).append(" =====\n")
                output.append(bytes.toString(Charsets.UTF_8))
                require(output.length <= MAX_LOG_TEXT_CHARS) { "Распакованный журнал превышает лимит." }
                zip.closeEntry()
            }
        }
        return output.toString()
    }

    fun compareBranchWithBase(baseBranch: String, headBranch: String): String {
        require(isSafeBranchName(baseBranch) && isAgentBranch(headBranch))
        val json = request(
            "GET",
            "/repos/$owner/$repository/compare/${encodeRef(baseBranch)}...${encodeRef(headBranch)}"
        )
        return json.optString("html_url")
    }

    private fun request(method: String, path: String, payload: JSONObject? = null): JSONObject {
        val connection = openConnection(method, path)
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = readBounded(stream, MAX_RESPONSE_BYTES)
        connection.disconnect()
        if (status !in 200..299) {
            val safeMessage = when (status) {
                401 -> "GitHub отклонил токен. Проверьте токен и его срок действия."
                403 -> "GitHub запретил действие. Проверьте разрешения токена и лимиты API."
                404 -> "Репозиторий, ветка или workflow не найдены либо недоступны токену."
                409, 422 -> "GitHub отклонил изменение из-за конфликта или неверного состояния."
                else -> "Ошибка GitHub API (HTTP $status)."
            }
            throw GitHubApiException(status, safeMessage, body)
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private fun openConnection(method: String, path: String): HttpURLConnection {
        require(path.startsWith("/repos/$owner/$repository/") || path == "/repos/$owner/$repository") {
            "Запрос за пределы настроенного репозитория запрещён."
        }
        val token = tokenStore.read() ?: throw IllegalStateException("GitHub-токен не настроен или не читается.")
        val connection = connectionFactory(URL("https://api.github.com$path"))
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        connection.setRequestProperty("User-Agent", "Jimmy-Mobile-AI-Agent")
        return connection
    }

    private fun readBounded(stream: java.io.InputStream?, maxBytes: Int): String =
        readBoundedBytes(stream, maxBytes).toString(Charsets.UTF_8)

    private fun readBoundedBytes(stream: java.io.InputStream?, maxBytes: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) {
                throw IllegalStateException("Ответ GitHub превышает допустимый размер.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val DEFAULT_OWNER = "mikhaildezprofi-glitch"
        const val DEFAULT_REPOSITORY = "mobile-ai-agent"
        const val REQUIRED_TOKEN_PERMISSIONS =
            "Fine-grained PAT: Contents (Read and write), Actions (Read and write), Metadata (Read-only)."
        private const val API_VERSION = "2022-11-28"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_FILES = 100
        private const val MAX_FILE_BYTES = 1 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 5 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_LOG_BYTES = 5 * 1024 * 1024
        private const val MAX_LOG_ENTRY_BYTES = 2 * 1024 * 1024
        private const val MAX_LOG_ENTRIES = 50
        private const val MAX_LOG_TEXT_CHARS = 5 * 1024 * 1024

        fun isAgentBranch(branch: String): Boolean =
            branch.startsWith("code-agent/") && isSafeBranchName(branch)

        fun isSafeBranchName(branch: String): Boolean {
            if (branch.length !in 1..180 || !branch.matches(Regex("[A-Za-z0-9._/-]+"))) return false
            if (branch.startsWith("/") || branch.endsWith("/") || branch.contains("..") ||
                branch.contains("//") || branch.contains("@{")) return false
            return branch.split('/').all { segment ->
                segment.isNotBlank() && segment != "." && !segment.startsWith(".") &&
                    !segment.endsWith(".") && !segment.endsWith(".lock", ignoreCase = true)
            }
        }

        fun normalizeWorkspacePath(raw: String): String {
            val path = raw.replace('\\', '/')
            require(path.isNotBlank() && path.length <= 240) { "Некорректный путь файла." }
            require(!path.startsWith("/") && !path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
                "Путь выходит за пределы рабочей области."
            }
            val lower = path.lowercase()
            val segments = lower.split('/')
            val name = segments.last()
            val sensitiveDirectory = segments.dropLast(1).any { segment ->
                segment in setOf("secrets", "credentials", ".ssh", ".aws") ||
                    segment.contains("secret") || segment.contains("credential")
            }
            val forbidden = sensitiveDirectory ||
                name == ".env" || name.startsWith(".env.") ||
                name in setOf(".npmrc", ".pypirc", ".netrc", "id_rsa", "id_ed25519",
                    "google-services.json", "service-account.json") ||
                name.startsWith("firebase-adminsdk") ||
                name.endsWith(".keystore") || name.endsWith(".jks") ||
                name.endsWith(".p12") || name.endsWith(".pfx") ||
                name.endsWith(".pem") || name.endsWith(".key") ||
                name.endsWith(".gguf") || name.endsWith(".apk") ||
                name == "local.properties" ||
                name.contains("secret") || name.contains("credential")
            require(!forbidden) { "Файл с секретами или крупными бинарными данными нельзя отправлять в GitHub: $path" }
            require(!lower.split('/').any { it in setOf(".git", "build", ".gradle", "node_modules") }) {
                "Служебные и генерируемые каталоги нельзя отправлять в GitHub."
            }
            return path
        }

        private fun encodeRef(ref: String): String =
            ref.split('/').joinToString("/") { encodeQuery(it) }

        private fun encodeQuery(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
