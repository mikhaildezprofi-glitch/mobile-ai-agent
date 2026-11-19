# Code Agent

Status: initial sandboxed file tools are wired into the PocketPal structured tool loop. JVM tests are added; no APK build or device runtime validation is performed in this stage.

## Implemented

The local PocketPal provider supplies four structured tools to the text model. In hybrid mode, ordinary code-generation requests can still use Gemini; requests that explicitly target project files/workspaces are routed to the local PocketPal provider so that tool execution remains local.

- `code_list_files`: list files in the private Code Agent workspace.
- `code_read_file`: read a UTF-8 source/configuration file and return its SHA-256.
- `code_search`: search text in workspace files.
- `code_write_file`: create a file or replace an existing file only when the caller supplies the current SHA-256 from a previous read.

Workspace root: `filesDir/code-workspace` (private app storage). It is deliberately separate from arbitrary shared storage and from the app's own installed source files.

## Safety boundaries

- All paths are canonicalized and must remain under the workspace root. Absolute paths and parent traversal are rejected.
- Symlink targets that resolve outside the workspace are rejected.
- `.git`, `.gradle`, `.idea`, `build`, `node_modules`, and Jimmy temporary files are excluded.
- UTF-8 text files are limited to 512 KiB per read/write.
- Existing-file edits require an optimistic SHA-256 match, preventing stale model output from overwriting a newer workspace version.
- Writes are atomic within the same directory.
- The tool allow-list contains no shell, process execution, arbitrary code execution, delete, network, or device-control operation.
- The existing structured runner's hard limit of five tool calls per request also applies to Code Agent tools.
- Vision inference still receives no text tool schemas.

## Not yet implemented

This is the first safe workspace layer, not a complete autonomous IDE:

1. Project import/export through Android's Storage Access Framework.
2. Patch preview and explicit user approval before applying a multi-file change to an imported project.
3. A remote GitHub Actions test/build executor with scoped GitHub authorization. A PAT must never be embedded in the APK.
4. Git status/diff/branch/commit/PR operations through an authenticated GitHub integration.
5. Compiler/test output ingestion and bounded repair iterations.
6. UI for selecting the active workspace/project and reviewing changes.

The app does not execute arbitrary shell commands or compile untrusted source code locally. Build and test execution is intended to be delegated to isolated GitHub Actions jobs after the authenticated integration and approval flow are implemented. The current private workspace is initially empty: until the SAF project import/export flow is added, the agent can create and edit files there but cannot inspect an existing repository or the installed app's own source tree.

## Validation

The unit suite covers workspace traversal rejection, file listing/search exclusions, SHA-256 guarded edits, and tool schema/dispatch behavior. GitHub Actions unit-test results must be checked for the current branch head before treating this implementation as CI-verified.

No APK is built in this stage. Device runtime behavior remains unverified.
