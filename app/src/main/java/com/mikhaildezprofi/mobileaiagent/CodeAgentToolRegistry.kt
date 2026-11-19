package com.mikhaildezprofi.mobileaiagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured, allow-listed Code Agent tools.
 * They operate only inside CodeWorkspace and never execute arbitrary commands.
 */
internal class CodeAgentToolRegistry(private val workspace: CodeWorkspace) {
    fun definitions(): JSONArray = JSONArray().apply {
        put(tool(
            "code_list_files",
            "List source and project files in the private Jimmy workspace. Does not access arbitrary device storage.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("directory", stringProperty("Relative directory; empty means workspace root"))
                .put("max_depth", integerProperty("Maximum recursion depth, 0 to 8"))
                .put("max_files", integerProperty("Maximum entries, 1 to 500")))
                .put("additionalProperties", false)
        ))
        put(tool(
            "code_read_file",
            "Read a UTF-8 text source/configuration file from the private Jimmy workspace. Returns its SHA-256 for safe edits.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("path", stringProperty("Relative file path")))
                .put("required", JSONArray().put("path"))
                .put("additionalProperties", false)
        ))
        put(tool(
            "code_search",
            "Search text in source/configuration files in the private Jimmy workspace.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", stringProperty("Text to find"))
                .put("directory", stringProperty("Optional relative directory"))
                .put("max_results", integerProperty("Maximum matches, 1 to 100")))
                .put("required", JSONArray().put("query"))
                .put("additionalProperties", false)
        ))
        put(tool(
            "code_write_file",
            "Create a UTF-8 source/configuration file or replace a previously read file using its exact expected_sha256. Writes only to the private Jimmy workspace; never to arbitrary device paths.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("path", stringProperty("Relative file path"))
                .put("content", stringProperty("Complete UTF-8 file content"))
                .put("expected_sha256", stringProperty("SHA-256 returned by code_read_file; required when replacing an existing file")))
                .put("required", JSONArray().put("path").put("content"))
                .put("additionalProperties", false)
        ))
    }

    fun canExecute(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, arguments: JSONObject): String = runCatching {
        when (name) {
            "code_list_files" -> JSONObject()
                .put("files", JSONArray(workspace.listFiles(
                    relativeDirectory = arguments.optString("directory"),
                    maxDepth = arguments.optInt("max_depth", 3),
                    maxFiles = arguments.optInt("max_files", 300)
                )))
                .toString()
            "code_read_file" -> {
                val result = workspace.readFile(arguments.getString("path"))
                JSONObject()
                    .put("path", result.path)
                    .put("content", result.content)
                    .put("sha256", result.sha256)
                    .toString()
            }
            "code_search" -> JSONObject()
                .put("matches", JSONArray(workspace.search(
                    query = arguments.getString("query"),
                    relativeDirectory = arguments.optString("directory"),
                    maxResults = arguments.optInt("max_results", 40)
                )))
                .toString()
            "code_write_file" -> JSONObject()
                .put("path", arguments.getString("path"))
                .put("sha256", workspace.writeFile(
                    relativePath = arguments.getString("path"),
                    content = arguments.getString("content"),
                    expectedSha256 = arguments.optString("expected_sha256").takeIf(String::isNotBlank)
                ))
                .put("status", "saved_in_private_workspace")
                .toString()
            else -> error("Неизвестный инструмент Code Agent: $name")
        }
    }.getOrElse { error ->
        JSONObject()
            .put("error", error.message ?: error.javaClass.simpleName)
            .put("status", "not_executed")
            .toString()
    }

    private fun tool(name: String, description: String, parameters: JSONObject) =
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters))

    private fun stringProperty(description: String) =
        JSONObject().put("type", "string").put("description", description)

    private fun integerProperty(description: String) =
        JSONObject().put("type", "integer").put("description", description)

    companion object {
        private val TOOL_NAMES = setOf(
            "code_list_files",
            "code_read_file",
            "code_search",
            "code_write_file"
        )
    }
}
