# Code Agent

Status: initial sandboxed file tools are wired into the PocketPal structured tool loop. JVM tests are added; no APK build or device runtime validation is performed in this stage.

## Implemented

The local PocketPal provider supplies five structured tools to the text model. In hybrid mode, ordinary code-generation requests can still use Gemini; requests that explicitly target project files/workspaces are routed to the local PocketPal provider so that tool execution remains local.

- `code_list_files`: list files in the private Code Agent workspace.
- `code_read_file`: read a UTF-8 source/configuration file and return its SHA-256.
- `code_search`: search text in workspace files.
- `code_replace_text`: replace one exact text fragment in a file only when it occurs exactly once and the caller supplies the current SHA-256.
- `code_write_file`: create a file or replace an existing file only when the caller supplies the current SHA-256 from a previous read.
- A bounded ZIP archive utility imports/exports named project directories through Android's Storage Access Framework document picker.
- The user can select the active project. The local model's Code Agent tools are scoped to that project's directory, not the full workspace.

Workspace root: `filesDir/code-workspace` (private app storage). It is deliberately separate from arbitrary shared storage and from the app's own installed source files.

## Safety boundaries

- All paths are canonicalized and must remain under the workspace root. Absolute paths and parent traversal are rejected.
- Symbolic links are not followed or edited, including links that point back inside the workspace.
- `.git`, `.gradle`, `.idea`, `build`, `node_modules`, and Jimmy temporary files are excluded.
- UTF-8 text files are limited to 512 KiB per read/write.
- Existing-file edits require an optimistic SHA-256 match, preventing stale model output from overwriting a newer workspace version.
- Writes are atomic within the same directory.
- The tool allow-list contains no shell, process execution, arbitrary code execution, delete, network, or device-control operation. Precise edits fail closed when the source fragment is missing, duplicated, or stale.
- The existing structured runner's hard limit of five tool calls per request also applies to Code Agent tools.
- Vision inference still receives no text tool schemas.

## Not yet implemented

This is the first safe workspace layer, not a complete autonomous IDE:

1. Patch preview and explicit user approval before applying a multi-file change to an imported project.
2. A remote GitHub Actions test/build executor with scoped GitHub authorization. A PAT must never be embedded in the APK.
3. Git status/diff/branch/commit/PR operations through an authenticated GitHub integration.
4. Compiler/test output ingestion and bounded repair iterations.
5. UI for reviewing proposed changes and test results.

The app does not execute arbitrary shell commands or compile untrusted source code locally. Build and test execution is intended to be delegated to isolated GitHub Actions jobs after the authenticated integration and approval flow are implemented. The user can now import a ZIP snapshot of an existing project and select it as the active workspace. The agent cannot inspect the installed app's own source tree or access a remote Git repository directly.

## Validation

The unit suite covers workspace traversal rejection, file listing/search exclusions, SHA-256 guarded edits, and tool schema/dispatch behavior. GitHub Actions unit-test results must be checked for the current branch head before treating this implementation as CI-verified.

No APK is built in this stage. Device runtime behavior remains unverified.
