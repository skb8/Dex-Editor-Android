# Dex-Editor-Android + MCP Server (Fork)

> 🇷🇺 **[Читайте на русском](README.ru.md)**

This is a fork of the original advanced Android DEX file editor project, with added support for **MCP (Model Context Protocol)**. 

This fork allows you to connect your local AI agent (like Claude Desktop, Pi, or any other MCP client) directly to your Android device to read, search, modify, and build DEX files.

---

## 🚀 New Features in this Fork

- **Built-in MCP HTTP Server**: Runs inside a background Android Service (`McpService`) with a Foreground notification. The server does not disconnect when the app is minimized.
- **Tools for LLM Agent**:
  - `dex_load` — Load DEX files (including multi-dex via paths array).
  - `dex_list_classes` — Get a list of classes with filtering and pagination.
  - `dex_get_class_outline` — Get field and method signatures (without method bodies) to save model context window.
  - `dex_get_method` — Read Smali code of a single specific method (supports full `methodSignature` for overloads).
  - `dex_get_java` — Decompile a Smali class into fully readable Java code using the built-in JADX decompiler.
  - `dex_search` — Fast pool search by classes, methods, fields, strings, or code.
  - `dex_find_usages` — Deep Xref search (who calls a method, accesses a field, or extends a class).
  - `dex_replace_in_method` — Smart precise string replacement in a method (`str_replace` approach) with instant compiler verification and uniqueness checks.
  - `dex_replace_method` — Full replacement of a method body (supports full `methodSignature` for overloads).
  - `dex_create_class` — Create and compile an entirely new class from scratch using Smali code.
  - `dex_remove_class` — Completely remove a class from the DEX.
  - `dex_list_methods` — List methods of a class with full overload-safe signatures.
  - `dex_list_fields` — List fields of a class with full field signatures.
  - `dex_get_strings` — List string constants with filtering and pagination.
  - `dex_validate` — Validate pending or supplied Smali without saving files.
  - `dex_diff` — Preview line-level Smali diffs before applying/saving changes.
  - `dex_export_smali` / `dex_import_smali` — Export and import classes as `.smali` files or text.
  - `dex_rename_class`, `dex_rename_method`, `dex_rename_field` — Refactor descriptors/references with validation.
  - `dex_get_call_graph` — Inspect outgoing/incoming method-call edges.
  - `dex_get_constants` — Extract string/numeric constants from Smali code.
  - `dex_patch_batch` — Apply several MCP operations sequentially in one request.
  - `dex_save` — Compile and save modified files to disk, with exact output path support for single-dex files.
- **Two-way Synchronization**:
  - If the LLM agent loads a file via `dex_load`, its path is immediately displayed on the app's main screen.
  - If the user selects one or more DEX files on the main screen (the built-in file picker writes multiple paths as a `;`-separated list) while the MCP server is running, the files are automatically loaded into the server's memory as multi-dex.
- **UI Control and Logging**:
  - An **MCP Server** button is added to the main screen menu.
  - The control panel allows you to set the port, start/stop the service, copy logs to clipboard with one click, and view incoming requests from the model and compiler messages in real time.

---

<details>
<summary><b>Original Project Description (Original README)</b></summary>

## My first open source Project 😀🇮🇳
# Dex-Editor-Android
[![Android CI](https://github.com/developer-krushna/Dex-Editor-Android/actions/workflows/android.yml/badge.svg)](https://github.com/developer-krushna/Dex-Editor-Android/actions/workflows/android.yml)
A work-in-progress multifunctional advanced *Android **DEX** file editor* for Android, using mainly [smali](https://github.com/google/smali) & [dexlib2](https://github.com/google/smali/tree/main/dexlib2).

### Available decompilers
- [JADX](https://github.com/skylot/jadx)

### Available features
- Dex Smali classes TreeView
- Smali navigation (methods, fields and strings list)
- Decompile single smali classes
- Decompiling single smali method bodies to java
- Batch class deletion
- Smali method flow diagram
- Editing Smali with best code editor
- Batch class editor and navigator
- Multi dex loader and compiler
- Smali full featured search and replacement
- Custom editor selection menu
- Faster Dex compilation with real time progress update
- Supported DEX version 40 and 41

### Environment
- **Gradle Version**: 9.5.0
- **Android Gradle Plugin (AGP)**: 9.2.1
- **JDK**: 17 or 21 (Required for Gradle 9+)
- **Min SDK**: API 24
- **Compile SDK**: API 37

</details>
