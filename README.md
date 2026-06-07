# Dex-Editor-Android + MCP Server (Fork)

> 🇷🇺 **[Читайте на русском](README.ru.md)**

This is a fork of the original advanced Android DEX file editor project, with added support for **MCP (Model Context Protocol)**. 

This fork allows you to connect your local AI agent (like Claude Desktop, Pi, or any other MCP client) directly to your Android device to read, search, modify, and build DEX/APK files.

---

## 🚀 New Features in this Fork

- **Built-in MCP HTTP Server**: Runs inside a background Android Service (`McpService`) with a Foreground notification. The server does not disconnect when the app is minimized.
- **Tools for LLM Agent**:
  - `dex_load` — Load DEX/APK files (including multi-dex via paths array).
  - `dex_list_classes` — Get a list of classes with filtering and pagination.
  - `dex_get_class_outline` — Get field and method signatures (without method bodies) to save model context window.
  - `dex_get_method` — Read Smali code of a single specific method.
  - `dex_get_java` — Decompile a Smali class into fully readable Java code using the built-in JADX decompiler.
  - `dex_search` — Fast pool search by classes, methods, fields, strings, or code.
  - `dex_find_usages` — Deep Xref search (who calls a method, accesses a field, or extends a class).
  - `dex_replace_in_method` — Smart precise string replacement in a method (`str_replace` approach) with instant compiler verification.
  - `dex_replace_method` — Full replacement of a method body.
  - `dex_create_class` — Create and compile an entirely new class from scratch using Smali code.
  - `dex_remove_class` — Completely remove a class from the DEX.
  - `dex_save` — Compile and save only modified files to disk.
- **Two-way Synchronization**:
  - If the LLM agent loads a file via `dex_load`, its path is immediately displayed on the app's main screen.
  - If the user selects a file on the main screen (via the built-in file picker or by pasting the path) while the MCP server is running, the file is automatically loaded into the server's memory.
- **UI Control and Logging**:
  - An **MCP Server** button is added to the main screen menu.
  - The control panel allows you to set the port, start/stop the service, copy logs to clipboard with one click, and view incoming requests from the model and compiler messages in real time.

---

## 🛠 Usage

1. **Starting the server**:
   - Install the built APK from the [Releases](https://github.com/skb8/Dex-Editor-Android/releases) page.
   - Open the app, tap the three dots in the top right corner $\rightarrow$ **MCP Server**.
   - Specify the port (default `8788`) and tap **Start Server**.

2. **Connecting from PC (via ADB / Localhost)**:
   - Connect your phone via USB and forward the port:
     ```bash
     adb forward tcp:8788 tcp:8788
     ```
   - The server is now available on your computer via HTTP at `http://127.0.0.1:8788/mcp`.

3. **Connecting via HTTPS (Over Internet)**:
   If you want to expose the server over a secure **HTTPS** connection (e.g., to connect a remote AI agent without ADB), use a tunnel tool like `ngrok` or `localtunnel` on your phone (via Termux) or PC:
   ```bash
   ngrok http 8788
   ```
   This will give you a secure `https://...ngrok.app/mcp` endpoint with a valid SSL certificate.

4. **Client Configuration (e.g., Claude Desktop)**:
   Add the server to your `claude_desktop_config.json` configuration file:
   ```json
   {
     "mcpServers": {
       "dex-editor-mcp": {
         "command": "curl",
         "args": ["-s", "-X", "POST", "-H", "Content-Type: application/json", "-d", "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}", "http://127.0.0.1:8788/mcp"]
       }
     }
   }
   ```

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
