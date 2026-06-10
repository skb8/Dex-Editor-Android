package modder.hub.dexeditor.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.smali.smali2.Smali;

import modder.hub.dexeditor.utils.ClassTree;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class McpServer {
    private static ServerSocket serverSocket;
    private static ExecutorService executorService;
    private static boolean running = false;
    public static ClassTree classTree;
    private static LogListener logListener;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static volatile String loadedDexPath = "";
    private static PathChangeListener pathChangeListener;

    public interface PathChangeListener {
        void onPathChanged(String newPath);
    }

    public static synchronized void setPathChangeListener(PathChangeListener listener) {
        pathChangeListener = listener;
        if (listener != null && loadedDexPath != null && !loadedDexPath.isEmpty()) {
            listener.onPathChanged(loadedDexPath);
        }
    }

    public static void loadDexDirectly(String path) throws Exception {
        String[] parts = path.split(";");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                list.add(p.trim());
            }
        }
        loadDexDirectly(list);
    }

    public static synchronized void loadDexDirectly(List<String> paths) throws Exception {
        if (paths == null || paths.isEmpty()) {
            throw new Exception("Paths list is empty");
        }
        List<String> resolved = new ArrayList<>();
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists()) {
                throw new Exception("File not found: " + p);
            }
            if (!f.isFile() || !f.getName().toLowerCase(java.util.Locale.US).endsWith(".dex")) {
                throw new Exception("Only .dex files are supported by MCP: " + p);
            }
            resolved.add(f.getAbsolutePath());
        }
        loadedDexPath = String.join(";", resolved);
        String cacheDir = new File(System.getProperty("java.io.tmpdir"), "dex_mcp_cache").getAbsolutePath();
        classTree = new ClassTree(resolved, cacheDir);
        log("DEX loaded successfully: " + loadedDexPath);
        if (pathChangeListener != null) {
            pathChangeListener.onPathChanged(loadedDexPath);
        }
    }

    public interface LogListener {
        void onLog(String message);
    }

    public static void setLogListener(LogListener listener) {
        logListener = listener;
    }

    private static void log(String msg) {
        System.out.println("[MCP] " + msg);
        if (logListener != null) {
            logListener.onLog(msg);
        }
    }

    public static synchronized void start(final int port) throws IOException {
        if (running) {
            return;
        }

        serverSocket = new ServerSocket(port);
        running = true;
        executorService = Executors.newCachedThreadPool();

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                log("Server started on port " + port);
                while (running) {
                    try {
                        final Socket socket = serverSocket.accept();
                        executorService.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleConnection(socket);
                            }
                        });
                    } catch (IOException e) {
                        if (!running) {
                            break;
                        }
                        log("Accept error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public static synchronized void stop() {
        if (running) {
            running = false;
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (executorService != null) {
                executorService.shutdownNow();
            }
            serverSocket = null;
            executorService = null;
            log("Server stopped");
        }
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    private static void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(10000); // 10s timeout
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            // Read request line and headers
            ByteArrayOutputStream headerBos = new ByteArrayOutputStream();
            int b;
            int consecutiveNewlines = 0;
            while ((b = is.read()) != -1) {
                headerBos.write(b);
                char c = (char) b;
                if (c == '\n') {
                    consecutiveNewlines++;
                } else if (c != '\r') {
                    consecutiveNewlines = 0;
                }

                if (consecutiveNewlines >= 2 || (consecutiveNewlines == 1 && headerBos.toString().endsWith("\n\n"))) {
                    break;
                }
            }

            String headersStr = headerBos.toString("UTF-8");
            String[] lines = headersStr.split("\r?\n");
            if (lines.length == 0 || lines[0].isEmpty()) {
                sendHttpError(os, 400, "Bad Request");
                socket.close();
                return;
            }

            String requestLine = lines[0];
            String[] reqParts = requestLine.split(" ");
            if (reqParts.length < 2) {
                sendHttpError(os, 400, "Bad Request");
                socket.close();
                return;
            }

            String method = reqParts[0];
            String path = reqParts[1];

            // Parse headers to find Content-Length
            int contentLength = 0;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // Handle preflight OPTIONS request
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendHttpOptionsResponse(os);
                socket.close();
                return;
            }

            if (!"POST".equalsIgnoreCase(method)) {
                sendHttpError(os, 405, "Method Not Allowed");
                socket.close();
                return;
            }

            // Read Body
            byte[] bodyBytes = new byte[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = is.read(bodyBytes, totalRead, contentLength - totalRead);
                if (read == -1) {
                    break;
                }
                totalRead += read;
            }

            String requestBody = new String(bodyBytes, StandardCharsets.UTF_8);
            log("Received request: POST " + path);
            log("Request body: " + requestBody);

            String responseBody = processRequest(requestBody);
            if (responseBody == null) {
                sendHttpNoContent(os);
            } else {
                log("Response body: " + responseBody);
                sendHttpResponse(os, 200, "OK", "application/json", responseBody);
            }
        } catch (Exception e) {
            log("Connection error: " + e.getMessage());
            e.printStackTrace();
            try {
                sendHttpError(socket.getOutputStream(), 500, e.getMessage());
            } catch (Exception ignored) {}
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private static void sendHttpOptionsResponse(OutputStream os) throws IOException {
        String response = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void sendHttpNoContent(OutputStream os) throws IOException {
        String response = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void sendHttpError(OutputStream os, int code, String msg) throws IOException {
        JsonObject err = new JsonObject();
        JsonObject errorDetail = new JsonObject();
        errorDetail.addProperty("code", -32603);
        errorDetail.addProperty("message", msg);
        err.addProperty("jsonrpc", "2.0");
        err.add("error", errorDetail);
        err.add("id", null);

        String json = gson.toJson(err);
        sendHttpResponse(os, code, msg, "application/json", json);
    }

    private static void sendHttpResponse(OutputStream os, int status, String statusMsg, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String responseHeaders = "HTTP/1.1 " + status + " " + statusMsg + "\r\n" +
                "Content-Type: " + contentType + "; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        os.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        os.write(bodyBytes);
        os.flush();
    }

    private static String processRequest(String requestBody) {
        JsonElement element;
        try {
            element = JsonParser.parseString(requestBody);
        } catch (Exception e) {
            return createJsonRpcError(null, -32700, "Parse error: " + e.getMessage());
        }

        if (!element.isJsonObject()) {
            return createJsonRpcError(null, -32600, "Invalid Request");
        }

        JsonObject req = element.getAsJsonObject();
        JsonElement id = req.get("id");

        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();

        if ("notifications/initialized".equals(method) || "notifications/initialized".equals(req.get("method"))) {
            return null;
        }

        try {
            if ("initialize".equals(method)) {
                return createInitializeResponse(id);
            } else if ("tools/list".equals(method)) {
                return createToolsListResponse(id);
            } else if ("tools/call".equals(method)) {
                String toolName = params.has("name") ? params.get("name").getAsString() : "";
                JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
                return executeTool(toolName, arguments, id);
            } else {
                // If it's a flat method call
                return executeTool(method, params, id);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return createJsonRpcError(id, -32603, e.getMessage());
        }
    }

    private static String createJsonRpcError(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        JsonObject errorDetail = new JsonObject();
        errorDetail.addProperty("code", code);
        errorDetail.addProperty("message", message);
        err.addProperty("jsonrpc", "2.0");
        err.add("error", errorDetail);
        err.add("id", id);
        return gson.toJson(err);
    }

    private static String createInitializeResponse(JsonElement id) {
        JsonObject res = new JsonObject();
        res.addProperty("jsonrpc", "2.0");
        res.add("id", id);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "dex-mcp-server");
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);

        res.add("result", result);
        return gson.toJson(res);
    }

    private static String createToolsListResponse(JsonElement id) {
        JsonObject res = new JsonObject();
        res.addProperty("jsonrpc", "2.0");
        res.add("id", id);

        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();

        // 1. dex_load
        JsonObject dexLoad = new JsonObject();
        dexLoad.addProperty("name", "dex_load");
        dexLoad.addProperty("description", "Loads one or more DEX files into the editor memory.");
        JsonObject dlParams = new JsonObject();
        dlParams.addProperty("type", "object");
        JsonObject dlProps = new JsonObject();
        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "Absolute path(s) to the DEX file(s). You can specify multiple files separated by a semicolon (;)");
        dlProps.add("path", pathProp);
        
        JsonObject pathsProp = new JsonObject();
        pathsProp.addProperty("type", "array");
        pathsProp.addProperty("description", "Array of absolute paths to DEX files");
        JsonObject itemsProp = new JsonObject();
        itemsProp.addProperty("type", "string");
        pathsProp.add("items", itemsProp);
        dlProps.add("paths", pathsProp);
        
        dlParams.add("properties", dlProps);
        dexLoad.add("inputSchema", dlParams);
        tools.add(dexLoad);

        // 2. dex_list_classes
        JsonObject dexList = new JsonObject();
        dexList.addProperty("name", "dex_list_classes");
        dexList.addProperty("description", "Lists classes in the loaded DEX. Supports filtering and pagination.");
        JsonObject lParams = new JsonObject();
        lParams.addProperty("type", "object");
        JsonObject lProps = new JsonObject();
        JsonObject filterProp = new JsonObject();
        filterProp.addProperty("type", "string");
        filterProp.addProperty("description", "Filter by class name or package (e.g. 'com.example')");
        JsonObject limitProp = new JsonObject();
        limitProp.addProperty("type", "integer");
        limitProp.addProperty("description", "Limit results count");
        JsonObject offsetProp = new JsonObject();
        offsetProp.addProperty("type", "integer");
        offsetProp.addProperty("description", "Offset for pagination");
        lProps.add("filter", filterProp);
        lProps.add("limit", limitProp);
        lProps.add("offset", offsetProp);
        lParams.add("properties", lProps);
        dexList.add("inputSchema", lParams);
        tools.add(dexList);

        // 3. dex_get_class_outline
        JsonObject dexOutline = new JsonObject();
        dexOutline.addProperty("name", "dex_get_class_outline");
        dexOutline.addProperty("description", "Returns the outlines (field & method signatures) of a class without method bodies.");
        JsonObject oParams = new JsonObject();
        oParams.addProperty("type", "object");
        JsonObject oProps = new JsonObject();
        JsonObject clsNameProp = new JsonObject();
        clsNameProp.addProperty("type", "string");
        clsNameProp.addProperty("description", "Full class signature name (e.g. 'Lcom/example/MainActivity;')");
        oProps.add("className", clsNameProp);
        oParams.add("properties", oProps);
        JsonArray oReq = new JsonArray();
        oReq.add("className");
        oParams.add("required", oReq);
        dexOutline.add("inputSchema", oParams);
        tools.add(dexOutline);

        // 4. dex_get_method
        JsonObject dexGetMethod = new JsonObject();
        dexGetMethod.addProperty("name", "dex_get_method");
        dexGetMethod.addProperty("description", "Extracts and returns the Smali code of a specific method.");
        JsonObject gmParams = new JsonObject();
        gmParams.addProperty("type", "object");
        JsonObject gmProps = new JsonObject();
        gmProps.add("className", clsNameProp);
        JsonObject mNameProp = new JsonObject();
        mNameProp.addProperty("type", "string");
        mNameProp.addProperty("description", "Method name");
        gmProps.add("methodName", mNameProp);
        JsonObject mSigProp = new JsonObject();
        mSigProp.addProperty("type", "string");
        mSigProp.addProperty("description", "Full method signature, e.g. Lcom/pkg/Cls;->methodName(I)Z. Required when methodName is overloaded.");
        gmProps.add("methodSignature", mSigProp);
        gmParams.add("properties", gmProps);
        JsonArray gmReq = new JsonArray();
        gmReq.add("className");
        gmParams.add("required", gmReq);
        dexGetMethod.add("inputSchema", gmParams);
        tools.add(dexGetMethod);

        // 5. dex_search
        JsonObject dexSearch = new JsonObject();
        dexSearch.addProperty("name", "dex_search");
        dexSearch.addProperty("description", "Search in DEX pool by class, method, field, string, or code.");
        JsonObject sParams = new JsonObject();
        sParams.addProperty("type", "object");
        JsonObject sProps = new JsonObject();
        JsonObject qProp = new JsonObject();
        qProp.addProperty("type", "string");
        qProp.addProperty("description", "Query string");
        JsonObject tProp = new JsonObject();
        tProp.addProperty("type", "string");
        tProp.addProperty("description", "Type of search: class, method, field, string, or code");
        sProps.add("query", qProp);
        sProps.add("type", tProp);
        sParams.add("properties", sProps);
        JsonArray sReq = new JsonArray();
        sReq.add("query");
        sReq.add("type");
        sParams.add("required", sReq);
        dexSearch.add("inputSchema", sParams);
        tools.add(dexSearch);

        // 6. dex_replace_in_method
        JsonObject dexReplInMethod = new JsonObject();
        dexReplInMethod.addProperty("name", "dex_replace_in_method");
        dexReplInMethod.addProperty("description", "Replaces a specific string inside a method's Smali body, compiles, and saves updates.");
        JsonObject rimParams = new JsonObject();
        rimParams.addProperty("type", "object");
        JsonObject rimProps = new JsonObject();
        rimProps.add("className", clsNameProp);
        rimProps.add("methodName", mNameProp);
        rimProps.add("methodSignature", mSigProp);
        JsonObject oldStrProp = new JsonObject();
        oldStrProp.addProperty("type", "string");
        oldStrProp.addProperty("description", "Original unique Smali code substring in method");
        JsonObject newStrProp = new JsonObject();
        newStrProp.addProperty("type", "string");
        newStrProp.addProperty("description", "Replacement Smali code substring");
        rimProps.add("old_str", oldStrProp);
        rimProps.add("new_str", newStrProp);
        rimParams.add("properties", rimProps);
        JsonArray rimReq = new JsonArray();
        rimReq.add("className");
        rimReq.add("old_str");
        rimReq.add("new_str");
        rimParams.add("required", rimReq);
        dexReplInMethod.add("inputSchema", rimParams);
        tools.add(dexReplInMethod);

        // 7. dex_replace_method
        JsonObject dexReplMethod = new JsonObject();
        dexReplMethod.addProperty("name", "dex_replace_method");
        dexReplMethod.addProperty("description", "Replaces the entire body of a method.");
        JsonObject rmParams = new JsonObject();
        rmParams.addProperty("type", "object");
        JsonObject rmProps = new JsonObject();
        rmProps.add("className", clsNameProp);
        rmProps.add("methodName", mNameProp);
        rmProps.add("methodSignature", mSigProp);
        JsonObject smaliProp = new JsonObject();
        smaliProp.addProperty("type", "string");
        smaliProp.addProperty("description", "New full Smali code of the method, including .method and .end method");
        rmProps.add("smali", smaliProp);
        rmParams.add("properties", rmProps);
        JsonArray rmReq = new JsonArray();
        rmReq.add("className");
        rmReq.add("smali");
        rmParams.add("required", rmReq);
        dexReplMethod.add("inputSchema", rmParams);
        tools.add(dexReplMethod);

        // 8. dex_save
        JsonObject dexSave = new JsonObject();
        dexSave.addProperty("name", "dex_save");
        dexSave.addProperty("description", "Compiles all modified classes and saves to the output path.");
        JsonObject svParams = new JsonObject();
        svParams.addProperty("type", "object");
        JsonObject svProps = new JsonObject();
        JsonObject outPathProp = new JsonObject();
        outPathProp.addProperty("type", "string");
        outPathProp.addProperty("description", "Output absolute file path. If omitted, overwrites loaded file.");
        JsonObject debugProp = new JsonObject();
        debugProp.addProperty("type", "boolean");
        debugProp.addProperty("description", "Strip debug info");
        svProps.add("outputPath", outPathProp);
        svProps.add("stripDebug", debugProp);
        svParams.add("properties", svProps);
        dexSave.add("inputSchema", svParams);
        tools.add(dexSave);

        // 9. dex_get_java
        JsonObject dexGetJava = new JsonObject();
        dexGetJava.addProperty("name", "dex_get_java");
        dexGetJava.addProperty("description", "Decompiles a class into Java code using JADX.");
        JsonObject gjParams = new JsonObject();
        gjParams.addProperty("type", "object");
        JsonObject gjProps = new JsonObject();
        gjProps.add("className", clsNameProp);
        gjParams.add("properties", gjProps);
        JsonArray gjReq = new JsonArray();
        gjReq.add("className");
        gjParams.add("required", gjReq);
        dexGetJava.add("inputSchema", gjParams);
        tools.add(dexGetJava);

        // 10. dex_find_usages
        JsonObject dexFindUsages = new JsonObject();
        dexFindUsages.addProperty("name", "dex_find_usages");
        dexFindUsages.addProperty("description", "Finds usages (cross-references) of a class, method, or field.");
        JsonObject fuParams = new JsonObject();
        fuParams.addProperty("type", "object");
        JsonObject fuProps = new JsonObject();
        JsonObject fuTypeProp = new JsonObject();
        fuTypeProp.addProperty("type", "string");
        fuTypeProp.addProperty("description", "Type of reference: class, method, or field");
        JsonObject fuQueryProp = new JsonObject();
        fuQueryProp.addProperty("type", "string");
        fuQueryProp.addProperty("description", "Full signature. Class: 'Lcom/pkg/Cls;', Method: 'Lcom/pkg/Cls;->methodName()V', Field: 'Lcom/pkg/Cls;->fieldName:Z'");
        fuProps.add("type", fuTypeProp);
        fuProps.add("signature", fuQueryProp);
        fuParams.add("properties", fuProps);
        JsonArray fuReq = new JsonArray();
        fuReq.add("type");
        fuReq.add("signature");
        fuParams.add("required", fuReq);
        dexFindUsages.add("inputSchema", fuParams);
        tools.add(dexFindUsages);

        // 11. dex_create_class
        JsonObject dexCreateClass = new JsonObject();
        dexCreateClass.addProperty("name", "dex_create_class");
        dexCreateClass.addProperty("description", "Creates and compiles a completely new class from Smali code.");
        JsonObject ccParams = new JsonObject();
        ccParams.addProperty("type", "object");
        JsonObject ccProps = new JsonObject();
        JsonObject smaliCodeProp = new JsonObject();
        smaliCodeProp.addProperty("type", "string");
        smaliCodeProp.addProperty("description", "Full Smali code of the new class");
        ccProps.add("smali", smaliCodeProp);
        ccParams.add("properties", ccProps);
        JsonArray ccReq = new JsonArray();
        ccReq.add("smali");
        ccParams.add("required", ccReq);
        dexCreateClass.add("inputSchema", ccParams);
        tools.add(dexCreateClass);

        // 12. dex_remove_class
        JsonObject dexRemoveClass = new JsonObject();
        dexRemoveClass.addProperty("name", "dex_remove_class");
        dexRemoveClass.addProperty("description", "Removes a class completely from the DEX.");
        JsonObject rcParams = new JsonObject();
        rcParams.addProperty("type", "object");
        JsonObject rcProps = new JsonObject();
        rcProps.add("className", clsNameProp);
        rcParams.add("properties", rcProps);
        JsonArray rcReq = new JsonArray();
        rcReq.add("className");
        rcParams.add("required", rcReq);
        dexRemoveClass.add("inputSchema", rcParams);
        tools.add(dexRemoveClass);

        // 13. dex_list_methods
        JsonObject dexListMethods = new JsonObject();
        dexListMethods.addProperty("name", "dex_list_methods");
        dexListMethods.addProperty("description", "Lists methods of a class with full signatures for overload-safe calls.");
        JsonObject lmParams = new JsonObject();
        lmParams.addProperty("type", "object");
        JsonObject lmProps = new JsonObject();
        lmProps.add("className", clsNameProp);
        lmParams.add("properties", lmProps);
        JsonArray lmReq = new JsonArray();
        lmReq.add("className");
        lmParams.add("required", lmReq);
        dexListMethods.add("inputSchema", lmParams);
        tools.add(dexListMethods);

        // 14. dex_list_fields
        JsonObject dexListFields = new JsonObject();
        dexListFields.addProperty("name", "dex_list_fields");
        dexListFields.addProperty("description", "Lists fields of a class with full signatures.");
        JsonObject lfParams = new JsonObject();
        lfParams.addProperty("type", "object");
        JsonObject lfProps = new JsonObject();
        lfProps.add("className", clsNameProp);
        lfParams.add("properties", lfProps);
        JsonArray lfReq = new JsonArray();
        lfReq.add("className");
        lfParams.add("required", lfReq);
        dexListFields.add("inputSchema", lfParams);
        tools.add(dexListFields);

        // 15. dex_get_strings
        JsonObject dexGetStrings = new JsonObject();
        dexGetStrings.addProperty("name", "dex_get_strings");
        dexGetStrings.addProperty("description", "Returns string constants from loaded DEX files with optional filtering and pagination.");
        JsonObject gsParams = new JsonObject();
        gsParams.addProperty("type", "object");
        JsonObject gsProps = new JsonObject();
        gsProps.add("filter", filterProp);
        gsProps.add("limit", limitProp);
        gsProps.add("offset", offsetProp);
        gsParams.add("properties", gsProps);
        dexGetStrings.add("inputSchema", gsParams);
        tools.add(dexGetStrings);

        result.add("tools", tools);
        res.add("result", result);
        return gson.toJson(res);
    }

    private static String executeTool(String toolName, JsonObject args, JsonElement id) throws Exception {
        JsonObject res = new JsonObject();
        res.addProperty("jsonrpc", "2.0");
        res.add("id", id);

        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textObj = new JsonObject();
        textObj.addProperty("type", "text");

        try {
            if ("dex_load".equals(toolName)) {
                if (args.has("paths") && args.get("paths").isJsonArray()) {
                    JsonArray arr = args.getAsJsonArray("paths");
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i < arr.size(); i++) {
                        list.add(arr.get(i).getAsString());
                    }
                    loadDexDirectly(list);
                } else {
                    String path = args.has("path") ? args.get("path").getAsString() : "";
                    if (path.isEmpty()) {
                        throw new Exception("Path or paths parameter is required");
                    }
                    loadDexDirectly(path);
                }

                textObj.addProperty("text", "DEX loaded successfully.\nVersion: " + classTree.getOpenedDexVersion() + "\nTotal Classes: " + classTree.classMap.size());
            } else {
                // All other tools require classTree to be loaded
                if (classTree == null) {
                    throw new Exception("No DEX file is currently loaded. Call 'dex_load' first.");
                }

                if ("dex_list_classes".equals(toolName)) {
                    String filter = args.has("filter") ? args.get("filter").getAsString() : "";
                    int limit = args.has("limit") ? args.get("limit").getAsInt() : 1000;
                    int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;

                    List<String> matched = new ArrayList<>();
                    for (String key : classTree.classMap.keySet()) {
                        String classSig = "L" + key + ";";
                        if (filter.isEmpty() || classSig.contains(filter) || key.contains(filter)) {
                            matched.add(classSig);
                        }
                    }
                    Collections.sort(matched);

                    int total = matched.size();
                    int end = Math.min(offset + limit, total);
                    List<String> sub = (offset < total) ? matched.subList(offset, end) : Collections.emptyList();

                    JsonObject listResult = new JsonObject();
                    listResult.addProperty("total", total);
                    listResult.addProperty("offset", offset);
                    listResult.addProperty("limit", limit);
                    JsonArray arr = new JsonArray();
                    for (String s : sub) {
                        arr.add(s);
                    }
                    listResult.add("classes", arr);

                    textObj.addProperty("text", gson.toJson(listResult));
                } else if ("dex_get_class_outline".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    ClassDef classDef = findClassDef(className);

                    StringBuilder outline = new StringBuilder();
                    outline.append(".class ").append(classDef.getType()).append("\n");
                    outline.append(".super ").append(classDef.getSuperclass()).append("\n\n");

                    outline.append("# Fields\n");
                    for (Field field : classDef.getFields()) {
                        outline.append(".field ");
                        outline.append(field.getName()).append(":").append(field.getType()).append("\n");
                    }

                    outline.append("\n# Methods\n");
                    for (Method method : classDef.getMethods()) {
                        outline.append(".method ").append(method.getName()).append("(");
                        for (CharSequence param : method.getParameterTypes()) {
                            outline.append(param);
                        }
                        outline.append(")").append(method.getReturnType()).append("\n");
                    }

                    textObj.addProperty("text", outline.toString());
                } else if ("dex_get_method".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String methodName = args.has("methodName") ? args.get("methodName").getAsString() : "";
                    String methodSignature = args.has("methodSignature") ? args.get("methodSignature").getAsString() : "";
                    ClassDef classDef = findClassDef(className);
                    Method targetMethod = findMethod(classDef, methodName, methodSignature);

                    String smali = getPureSmali(classDef);
                    String methodSmali = extractMethod(smali, targetMethod);
                    if (methodSmali == null) {
                        throw new Exception("Method not found in smali: " + methodSignature(classDef.getType(), targetMethod));
                    }
                    textObj.addProperty("text", methodSmali);
                } else if ("dex_search".equals(toolName)) {
                    String query = args.has("query") ? args.get("query").getAsString() : "";
                    String type = args.has("type") ? args.get("type").getAsString() : "";

                    List<String> resultsList = new ArrayList<>();

                    for (ClassDef classDef : classTree.classMap.values()) {
                        String classSig = classDef.getType();
                        if ("class".equalsIgnoreCase(type)) {
                            if (classSig.contains(query)) {
                                resultsList.add("Class: " + classSig);
                            }
                        } else if ("method".equalsIgnoreCase(type)) {
                            for (Method method : classDef.getMethods()) {
                                if (method.getName().contains(query)) {
                                    resultsList.add("Method in " + classSig + " -> " + method.getName());
                                }
                            }
                        } else if ("field".equalsIgnoreCase(type)) {
                            for (Field field : classDef.getFields()) {
                                if (field.getName().contains(query)) {
                                    resultsList.add("Field in " + classSig + " -> " + field.getName());
                                }
                            }
                        } else if ("string".equalsIgnoreCase(type) || "code".equalsIgnoreCase(type)) {
                            String smali = getPureSmali(classDef);
                            if (smali.contains(query)) {
                                resultsList.add("Match in " + classSig);
                            }
                        }
                        if (resultsList.size() >= 200) {
                            resultsList.add("...and more matches (truncated to 200)");
                            break;
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    if (resultsList.isEmpty()) {
                        sb.append("No matches found.");
                    } else {
                        for (String r : resultsList) {
                            sb.append(r).append("\n");
                        }
                    }
                    textObj.addProperty("text", sb.toString());
                } else if ("dex_find_usages".equals(toolName)) {
                    String queryType = args.has("type") ? args.get("type").getAsString() : "";
                    String signature = args.has("signature") ? args.get("signature").getAsString() : "";

                    List<String> resultsList = new ArrayList<>();

                    for (ClassDef classDef : classTree.classMap.values()) {
                        String currentClass = classDef.getType();
                        
                        // Class Usage Check (Superclass/Interfaces)
                        if ("class".equalsIgnoreCase(queryType)) {
                            if (signature.equals(classDef.getSuperclass())) {
                                resultsList.add("Extended by: " + currentClass);
                            }
                            if (classDef.getInterfaces() != null) {
                                for (String iface : classDef.getInterfaces()) {
                                    if (signature.equals(iface)) {
                                        resultsList.add("Implemented by: " + currentClass);
                                    }
                                }
                            }
                        }

                        // Method bodies (Instructions) Check
                        for (Method method : classDef.getMethods()) {
                            MethodImplementation impl = method.getImplementation();
                            if (impl != null) {
                                for (Instruction instruction : impl.getInstructions()) {
                                    if (instruction instanceof ReferenceInstruction) {
                                        Reference ref = ((ReferenceInstruction) instruction).getReference();
                                        if ("class".equalsIgnoreCase(queryType) && ref instanceof TypeReference) {
                                            if (signature.equals(((TypeReference) ref).getType())) {
                                                resultsList.add("Class referenced in " + currentClass + " -> " + method.getName());
                                            }
                                        } else if ("method".equalsIgnoreCase(queryType) && ref instanceof MethodReference) {
                                            MethodReference mRef = (MethodReference) ref;
                                            String mSig = mRef.getDefiningClass() + "->" + mRef.getName() + "(";
                                            for (CharSequence param : mRef.getParameterTypes()) {
                                                mSig += param;
                                            }
                                            mSig += ")" + mRef.getReturnType();
                                            if (signature.equals(mSig)) {
                                                resultsList.add("Method called in " + currentClass + " -> " + method.getName());
                                            }
                                        } else if ("field".equalsIgnoreCase(queryType) && ref instanceof FieldReference) {
                                            FieldReference fRef = (FieldReference) ref;
                                            String fSig = fRef.getDefiningClass() + "->" + fRef.getName() + ":" + fRef.getType();
                                            if (signature.equals(fSig)) {
                                                resultsList.add("Field accessed in " + currentClass + " -> " + method.getName());
                                            }
                                        } else if ("string".equalsIgnoreCase(queryType) && ref instanceof StringReference) {
                                            if (signature.equals(((StringReference) ref).getString())) {
                                                resultsList.add("String referenced in " + currentClass + " -> " + method.getName());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (resultsList.size() >= 300) {
                            resultsList.add("...and more matches (truncated to 300)");
                            break;
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    if (resultsList.isEmpty()) {
                        sb.append("No usages found for ").append(signature);
                    } else {
                        for (String r : resultsList) {
                            sb.append(r).append("\n");
                        }
                    }
                    textObj.addProperty("text", sb.toString());
                } else if ("dex_list_methods".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    ClassDef classDef = findClassDef(className);
                    JsonArray arr = new JsonArray();
                    for (Method method : classDef.getMethods()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", method.getName());
                        item.addProperty("signature", methodSignature(classDef.getType(), method));
                        arr.add(item);
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("className", classDef.getType());
                    out.addProperty("total", arr.size());
                    out.add("methods", arr);
                    textObj.addProperty("text", gson.toJson(out));
                } else if ("dex_list_fields".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    ClassDef classDef = findClassDef(className);
                    JsonArray arr = new JsonArray();
                    for (Field field : classDef.getFields()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", field.getName());
                        item.addProperty("type", field.getType());
                        item.addProperty("signature", classDef.getType() + "->" + field.getName() + ":" + field.getType());
                        arr.add(item);
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("className", classDef.getType());
                    out.addProperty("total", arr.size());
                    out.add("fields", arr);
                    textObj.addProperty("text", gson.toJson(out));
                } else if ("dex_get_strings".equals(toolName)) {
                    String filter = args.has("filter") ? args.get("filter").getAsString() : "";
                    int limit = args.has("limit") ? args.get("limit").getAsInt() : 200;
                    int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                    List<String> allStrings = classTree.getAllStrings();
                    List<String> matched = new ArrayList<>();
                    for (String value : allStrings) {
                        if (filter.isEmpty() || value.contains(filter)) {
                            matched.add(value);
                        }
                    }
                    int total = matched.size();
                    int end = Math.min(offset + limit, total);
                    List<String> sub = (offset < total) ? matched.subList(offset, end) : Collections.emptyList();
                    JsonObject out = new JsonObject();
                    out.addProperty("total", total);
                    out.addProperty("offset", offset);
                    out.addProperty("limit", limit);
                    JsonArray arr = new JsonArray();
                    for (String value : sub) {
                        arr.add(value);
                    }
                    out.add("strings", arr);
                    textObj.addProperty("text", gson.toJson(out));
                } else if ("dex_replace_in_method".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String methodName = args.has("methodName") ? args.get("methodName").getAsString() : "";
                    String methodSignature = args.has("methodSignature") ? args.get("methodSignature").getAsString() : "";
                    String oldStr = args.has("old_str") ? args.get("old_str").getAsString() : "";
                    String newStr = args.has("new_str") ? args.get("new_str").getAsString() : "";

                    ClassDef classDef = findClassDef(className);
                    Method targetMethod = findMethod(classDef, methodName, methodSignature);
                    String classSmali = getPureSmali(classDef);
                    String methodSmali = extractMethod(classSmali, targetMethod);
                    if (methodSmali == null) {
                        throw new Exception("Method not found in smali: " + methodSignature(classDef.getType(), targetMethod));
                    }

                    int occurrences = countOccurrences(methodSmali, oldStr);
                    if (occurrences == 0) {
                        throw new Exception("Method body does not contain old_str");
                    }
                    if (occurrences > 1) {
                        throw new Exception("old_str is not unique in the method (" + occurrences + " matches). Provide a larger unique snippet.");
                    }

                    String updatedMethodSmali = methodSmali.replace(oldStr, newStr);
                    String updatedClassSmali = classSmali.replace(methodSmali, updatedMethodSmali);

                    ClassDef assembled = Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                    classTree.saveClassDef(assembled);

                    textObj.addProperty("text", "Successfully replaced code and reassembled class: " + className);
                } else if ("dex_replace_method".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String methodName = args.has("methodName") ? args.get("methodName").getAsString() : "";
                    String methodSignature = args.has("methodSignature") ? args.get("methodSignature").getAsString() : "";
                    String newMethodSmali = args.has("smali") ? args.get("smali").getAsString() : "";

                    ClassDef classDef = findClassDef(className);
                    Method targetMethod = findMethod(classDef, methodName, methodSignature);
                    String classSmali = getPureSmali(classDef);
                    String methodSmali = extractMethod(classSmali, targetMethod);
                    if (methodSmali == null) {
                        throw new Exception("Method not found in smali: " + methodSignature(classDef.getType(), targetMethod));
                    }

                    String updatedClassSmali = classSmali.replace(methodSmali, newMethodSmali);

                    ClassDef assembled = Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                    classTree.saveClassDef(assembled);

                    textObj.addProperty("text", "Successfully replaced entire method and reassembled class: " + className);
                } else if ("dex_save".equals(toolName)) {
                    String outPath = args.has("outputPath") ? args.get("outputPath").getAsString() : "";
                    boolean stripDebug = args.has("stripDebug") && args.get("stripDebug").getAsBoolean();

                    classTree.clearOutputPathOverrides();
                    if (!outPath.isEmpty()) {
                        List<String> dexFileNames = classTree.getDexFileNames();
                        if (dexFileNames.size() == 1) {
                            classTree.setOutputPathOverride(dexFileNames.get(0), outPath);
                        } else {
                            File outFile = new File(outPath);
                            boolean directoryStyle = outPath.endsWith("/") || outPath.endsWith(File.separator) || (outFile.exists() && outFile.isDirectory());
                            if (!directoryStyle) {
                                throw new Exception("outputPath must be a directory when multiple DEX files are loaded");
                            }
                            for (String dexFileName : dexFileNames) {
                                classTree.setOutputPathOverride(dexFileName, new File(outFile, dexFileName).getAbsolutePath());
                            }
                        }
                    }

                    ClassTree.CompilationOptions opts = new ClassTree.CompilationOptions();
                    if (stripDebug) {
                        opts.removeAllDebug = true;
                    }
                    classTree.setCompilationOptions(opts);

                    final StringBuilder progressLog = new StringBuilder();
                    classTree.saveAllDexFiles(new ClassTree.DexSaveProgress() {
                        @Override
                        public void onProgress(int progress, int total) {
                            log("Save progress: " + progress + "/" + total);
                        }

                        @Override
                        public void onTitle(String title) {
                            log("Saving: " + title);
                        }

                        @Override
                        public void onMessage(String msg) {
                            log("Save msg: " + msg);
                            progressLog.append(msg).append("\n");
                        }
                    });

                    textObj.addProperty("text", "DEX files saved successfully.\n" + progressLog.toString());
                } else if ("dex_get_java".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    ClassDef classDef = findClassDef(className);
                    String smali = getPureSmali(classDef);
                    String javaCode = modder.hub.dexeditor.smali.Smali2Java.translate(smali, classTree.getOpenedDexVersion());
                    textObj.addProperty("text", javaCode);
                } else if ("dex_create_class".equals(toolName)) {
                    String smaliCode = args.has("smali") ? args.get("smali").getAsString() : "";
                    if (smaliCode.isEmpty()) throw new Exception("Smali code is empty");
                    ClassDef assembled = Smali.assemble(smaliCode, new SmaliOptions(), classTree.getOpenedDexVersion());
                    classTree.saveClassDef(assembled);
                    textObj.addProperty("text", "Successfully created/updated class: " + assembled.getType());
                } else if ("dex_remove_class".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String type = className;
                    if (type.startsWith("L") && type.endsWith(";")) {
                        type = type.substring(1, type.length() - 1);
                    }
                    classTree.removeClass(type);
                    textObj.addProperty("text", "Successfully marked class for removal: " + className + ". Call dex_save to apply.");
                } else {
                    throw new Exception("Unknown tool: " + toolName);
                }
            }
        } catch (Exception e) {
            textObj.addProperty("text", "Error executing " + toolName + ": " + e.getMessage());
            result.addProperty("isError", true);
        }

        content.add(textObj);
        result.add("content", content);
        res.add("result", result);
        return gson.toJson(res);
    }

    public static List<String> getIpAddresses() {
        List<String> ipList = new ArrayList<>();
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        ipList.add(inetAddress.getHostAddress());
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return ipList;
    }

    private static ClassDef findClassDef(String className) throws Exception {
        String key = className;
        if (key.startsWith("L") && key.endsWith(";")) {
            key = key.substring(1, key.length() - 1);
        }
        ClassDef classDef = classTree.classMap.get(key);
        if (classDef == null) {
            throw new Exception("Class not found: " + className);
        }
        return classDef;
    }

    private static String getPureSmali(ClassDef classDef) throws Exception {
        String key = classDef.getType();
        String typeKey = key.substring(1, key.length() - 1);
        if (classTree.getPendingSmaliMap().containsKey(typeKey)) {
            return classTree.getPendingSmaliMap().get(typeKey);
        }

        StringWriter sw = new StringWriter();
        BaksmaliWriter bw = new BaksmaliWriter(sw);
        new com.android.tools.smali.baksmali.Adaptors.ClassDefinition(new BaksmaliOptions(), classDef).writeTo(bw);
        bw.close();
        return sw.toString();
    }

    private static Method findMethod(ClassDef classDef, String methodName, String fullSignature) throws Exception {
        if (fullSignature != null && !fullSignature.isEmpty()) {
            for (Method method : classDef.getMethods()) {
                if (fullSignature.equals(methodSignature(classDef.getType(), method))) {
                    return method;
                }
            }
            throw new Exception("Method signature not found: " + fullSignature);
        }

        if (methodName == null || methodName.isEmpty()) {
            throw new Exception("methodName or methodSignature is required");
        }

        List<Method> matches = new ArrayList<>();
        for (Method method : classDef.getMethods()) {
            if (methodName.equals(method.getName())) {
                matches.add(method);
            }
        }
        if (matches.isEmpty()) {
            throw new Exception("Method '" + methodName + "' not found in class " + classDef.getType());
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Method method : matches) {
                sb.append(methodSignature(classDef.getType(), method)).append("\n");
            }
            throw new Exception("Method '" + methodName + "' is overloaded. Use methodSignature. Available signatures:\n" + sb.toString());
        }
        return matches.get(0);
    }

    private static String methodSignature(String classType, Method method) {
        return classType + "->" + methodDescriptor(method);
    }

    private static String methodDescriptor(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName()).append("(");
        for (CharSequence param : method.getParameterTypes()) {
            sb.append(param);
        }
        sb.append(")").append(method.getReturnType());
        return sb.toString();
    }

    private static String extractMethod(String smaliCode, Method method) {
        String descriptor = methodDescriptor(method);
        String nameWithParen = method.getName() + "(";
        String[] lines = smaliCode.split("\n");
        StringBuilder sb = null;
        boolean inMethod = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(".method ") && trimmed.contains(nameWithParen) && trimmed.endsWith(descriptor)) {
                sb = new StringBuilder();
                inMethod = true;
            }

            if (inMethod) {
                sb.append(line).append("\n");
                if (trimmed.startsWith(".end method")) {
                    break;
                }
            }
        }

        return (sb != null) ? sb.toString() : null;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}

