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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
                if (isJsonRpcErrorResponse(responseBody)) {
                    sendHttpResponse(os, 500, "JSON-RPC Error", "application/json", responseBody);
                } else {
                    sendHttpResponse(os, 200, "OK", "application/json", responseBody);
                }
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

    private static boolean isJsonRpcErrorResponse(String responseBody) {
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            return element.isJsonObject() && element.getAsJsonObject().has("error");
        } catch (Exception ignored) {
            return false;
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
        tProp.addProperty("description", "Type of search: class, method, field, string, code, or all");
        JsonObject regexProp = new JsonObject();
        regexProp.addProperty("type", "boolean");
        regexProp.addProperty("description", "When true, treat query as a Java regular expression and use find() matching.");
        JsonObject ignoreCaseProp = new JsonObject();
        ignoreCaseProp.addProperty("type", "boolean");
        ignoreCaseProp.addProperty("description", "Case-insensitive matching for literal and regex searches.");
        JsonObject limitProp = new JsonObject();
        limitProp.addProperty("type", "integer");
        limitProp.addProperty("description", "Maximum results to return (default 200, max 1000).");
        sProps.add("query", qProp);
        sProps.add("type", tProp);
        sProps.add("regex", regexProp);
        sProps.add("ignoreCase", ignoreCaseProp);
        sProps.add("limit", limitProp);
        sParams.add("properties", sProps);
        JsonArray sReq = new JsonArray();
        sReq.add("query");
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

        JsonObject genericParams = new JsonObject();
        genericParams.addProperty("type", "object");
        genericParams.add("properties", new JsonObject());

        JsonObject dexValidate = new JsonObject();
        dexValidate.addProperty("name", "dex_validate");
        dexValidate.addProperty("description", "Validates pending or supplied Smali by assembling it without saving to disk.");
        dexValidate.add("inputSchema", genericParams);
        tools.add(dexValidate);

        JsonObject dexDiff = new JsonObject();
        dexDiff.addProperty("name", "dex_diff");
        dexDiff.addProperty("description", "Returns a compact line diff for a class or supplied Smali content.");
        dexDiff.add("inputSchema", genericParams);
        tools.add(dexDiff);

        JsonObject dexExportSmali = new JsonObject();
        dexExportSmali.addProperty("name", "dex_export_smali");
        dexExportSmali.addProperty("description", "Exports one class or a package to Smali text, optionally writing .smali files to outputDir.");
        dexExportSmali.add("inputSchema", genericParams);
        tools.add(dexExportSmali);

        JsonObject dexImportSmali = new JsonObject();
        dexImportSmali.addProperty("name", "dex_import_smali");
        dexImportSmali.addProperty("description", "Imports Smali from text or file paths, assembles it, and updates the in-memory DEX.");
        dexImportSmali.add("inputSchema", genericParams);
        tools.add(dexImportSmali);

        JsonObject dexRenameClass = new JsonObject();
        dexRenameClass.addProperty("name", "dex_rename_class");
        dexRenameClass.addProperty("description", "Renames a class descriptor and updates textual Smali references across loaded classes.");
        dexRenameClass.add("inputSchema", genericParams);
        tools.add(dexRenameClass);

        JsonObject dexRenameMethod = new JsonObject();
        dexRenameMethod.addProperty("name", "dex_rename_method");
        dexRenameMethod.addProperty("description", "Renames a method by full signature and updates textual Smali references across loaded classes.");
        dexRenameMethod.add("inputSchema", genericParams);
        tools.add(dexRenameMethod);

        JsonObject dexRenameField = new JsonObject();
        dexRenameField.addProperty("name", "dex_rename_field");
        dexRenameField.addProperty("description", "Renames a field by full signature and updates textual Smali references across loaded classes.");
        dexRenameField.add("inputSchema", genericParams);
        tools.add(dexRenameField);

        JsonObject dexGetCallGraph = new JsonObject();
        dexGetCallGraph.addProperty("name", "dex_get_call_graph");
        dexGetCallGraph.addProperty("description", "Returns outgoing or incoming method-call edges for a class or method.");
        dexGetCallGraph.add("inputSchema", genericParams);
        tools.add(dexGetCallGraph);

        JsonObject dexGetConstants = new JsonObject();
        dexGetConstants.addProperty("name", "dex_get_constants");
        dexGetConstants.addProperty("description", "Extracts string and numeric constants from loaded classes with optional filtering.");
        dexGetConstants.add("inputSchema", genericParams);
        tools.add(dexGetConstants);

        JsonObject dexPatchBatch = new JsonObject();
        dexPatchBatch.addProperty("name", "dex_patch_batch");
        dexPatchBatch.addProperty("description", "Applies a batch of MCP tool operations sequentially, stopping on first error.");
        dexPatchBatch.add("inputSchema", genericParams);
        tools.add(dexPatchBatch);

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
                    String type = args.has("type") ? args.get("type").getAsString() : "all";
                    boolean regex = args.has("regex") && args.get("regex").getAsBoolean();
                    boolean ignoreCase = args.has("ignoreCase") && args.get("ignoreCase").getAsBoolean();
                    int limit = args.has("limit") ? args.get("limit").getAsInt() : 200;
                    if (limit <= 0) limit = 200;
                    if (limit > 1000) limit = 1000;
                    if (query.isEmpty()) {
                        throw new Exception("query is required");
                    }

                    Pattern pattern = null;
                    if (regex) {
                        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                        try {
                            pattern = Pattern.compile(query, flags);
                        } catch (PatternSyntaxException ex) {
                            throw new Exception("Invalid regex: " + ex.getDescription());
                        }
                    }

                    boolean searchAll = type.isEmpty() || "all".equalsIgnoreCase(type);
                    boolean searchClass = searchAll || "class".equalsIgnoreCase(type);
                    boolean searchMethod = searchAll || "method".equalsIgnoreCase(type);
                    boolean searchField = searchAll || "field".equalsIgnoreCase(type);
                    boolean searchString = searchAll || "string".equalsIgnoreCase(type);
                    boolean searchCode = searchAll || "code".equalsIgnoreCase(type);
                    if (!searchAll && !searchClass && !searchMethod && !searchField && !searchString && !searchCode) {
                        throw new Exception("Unknown search type: " + type + ". Use class, method, field, string, code, or all.");
                    }

                    List<String> resultsList = new ArrayList<>();
                    boolean truncated = false;

                    for (ClassDef classDef : classTree.classMap.values()) {
                        String classSig = classDef.getType();
                        if (searchClass) {
                            String dotted = classSig.replace('/', '.');
                            if (searchMatches(classSig, query, pattern, ignoreCase) || searchMatches(dotted, query, pattern, ignoreCase)) {
                                resultsList.add("Class: " + classSig);
                            }
                        }

                        if (searchMethod && resultsList.size() < limit) {
                            for (Method method : classDef.getMethods()) {
                                String signature = methodSignature(classSig, method);
                                if (searchMatches(method.getName(), query, pattern, ignoreCase) || searchMatches(signature, query, pattern, ignoreCase)) {
                                    resultsList.add("Method: " + signature);
                                    if (resultsList.size() >= limit) break;
                                }
                            }
                        }

                        if (searchField && resultsList.size() < limit) {
                            for (Field field : classDef.getFields()) {
                                String signature = classSig + "->" + field.getName() + ":" + field.getType();
                                if (searchMatches(field.getName(), query, pattern, ignoreCase) || searchMatches(signature, query, pattern, ignoreCase)) {
                                    resultsList.add("Field: " + signature);
                                    if (resultsList.size() >= limit) break;
                                }
                            }
                        }

                        if ((searchString || searchCode) && resultsList.size() < limit) {
                            String[] lines = getPureSmali(classDef).split("\n", -1);
                            for (int i = 0; i < lines.length; i++) {
                                String line = lines[i];
                                String trimmed = line.trim();
                                if (searchString && trimmed.startsWith("const-string") && searchMatches(line, query, pattern, ignoreCase)) {
                                    resultsList.add("String in " + classSig + ":" + (i + 1) + ": " + trimmed);
                                } else if (searchCode && searchMatches(line, query, pattern, ignoreCase)) {
                                    resultsList.add("Code in " + classSig + ":" + (i + 1) + ": " + trimmed);
                                }
                                if (resultsList.size() >= limit) break;
                            }
                        }

                        if (resultsList.size() >= limit) {
                            truncated = true;
                            break;
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("Search mode: ").append(regex ? "regex" : "literal")
                            .append(", type: ").append(type)
                            .append(", results: ").append(resultsList.size()).append("\n");
                    if (resultsList.isEmpty()) {
                        sb.append("No matches found.");
                    } else {
                        for (String r : resultsList) {
                            sb.append(r).append("\n");
                        }
                        if (truncated) {
                            sb.append("...and more matches (truncated to ").append(limit).append(")\n");
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
                    boolean dryRun = args.has("dryRun") && args.get("dryRun").getAsBoolean();

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
                    Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                    if (dryRun) {
                        textObj.addProperty("text", buildLineDiff(classSmali, updatedClassSmali));
                    } else {
                        ClassDef assembled = Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                        classTree.saveClassDef(assembled);
                        textObj.addProperty("text", "Successfully replaced code and reassembled class: " + className);
                    }
                } else if ("dex_replace_method".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String methodName = args.has("methodName") ? args.get("methodName").getAsString() : "";
                    String methodSignature = args.has("methodSignature") ? args.get("methodSignature").getAsString() : "";
                    String newMethodSmali = args.has("smali") ? args.get("smali").getAsString() : "";
                    boolean dryRun = args.has("dryRun") && args.get("dryRun").getAsBoolean();

                    ClassDef classDef = findClassDef(className);
                    Method targetMethod = findMethod(classDef, methodName, methodSignature);
                    String classSmali = getPureSmali(classDef);
                    String methodSmali = extractMethod(classSmali, targetMethod);
                    if (methodSmali == null) {
                        throw new Exception("Method not found in smali: " + methodSignature(classDef.getType(), targetMethod));
                    }

                    String updatedClassSmali = classSmali.replace(methodSmali, newMethodSmali);
                    Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                    if (dryRun) {
                        textObj.addProperty("text", buildLineDiff(classSmali, updatedClassSmali));
                    } else {
                        ClassDef assembled = Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                        classTree.saveClassDef(assembled);
                        textObj.addProperty("text", "Successfully replaced entire method and reassembled class: " + className);
                    }
                } else if ("dex_validate".equals(toolName)) {
                    JsonArray issues = new JsonArray();
                    int checked = 0;
                    if (args.has("smali")) {
                        checked++;
                        try {
                            Smali.assemble(args.get("smali").getAsString(), new SmaliOptions(), classTree.getOpenedDexVersion());
                        } catch (Exception ex) {
                            issues.add(ex.getMessage());
                        }
                    } else if (args.has("className")) {
                        checked++;
                        ClassDef classDef = findClassDef(args.get("className").getAsString());
                        try {
                            Smali.assemble(getPureSmali(classDef), new SmaliOptions(), classTree.getOpenedDexVersion());
                        } catch (Exception ex) {
                            issues.add(classDef.getType() + ": " + ex.getMessage());
                        }
                    } else {
                        Map<String, String> pending = classTree.getPendingSmaliMap();
                        if (pending.isEmpty()) {
                            for (ClassDef classDef : classTree.classMap.values()) {
                                checked++;
                                try {
                                    Smali.assemble(getPureSmali(classDef), new SmaliOptions(), classTree.getOpenedDexVersion());
                                } catch (Exception ex) {
                                    issues.add(classDef.getType() + ": " + ex.getMessage());
                                }
                                if (checked >= 200 && !args.has("all")) break;
                            }
                        } else {
                            for (Map.Entry<String, String> entry : pending.entrySet()) {
                                checked++;
                                try {
                                    Smali.assemble(entry.getValue(), new SmaliOptions(), classTree.getOpenedDexVersion());
                                } catch (Exception ex) {
                                    issues.add(entry.getKey() + ": " + ex.getMessage());
                                }
                            }
                        }
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("ok", issues.size() == 0);
                    out.addProperty("checked", checked);
                    out.add("issues", issues);
                    textObj.addProperty("text", gson.toJson(out));
                } else if ("dex_diff".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String oldText;
                    String newText;
                    if (args.has("oldSmali") && args.has("newSmali")) {
                        oldText = args.get("oldSmali").getAsString();
                        newText = args.get("newSmali").getAsString();
                    } else {
                        ClassDef classDef = findClassDef(className);
                        oldText = getBaseSmali(classDef);
                        if (args.has("newSmali")) {
                            newText = args.get("newSmali").getAsString();
                        } else {
                            newText = getPureSmali(classDef);
                        }
                    }
                    textObj.addProperty("text", buildLineDiff(oldText, newText));
                } else if ("dex_export_smali".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String packagePrefix = args.has("packagePrefix") ? args.get("packagePrefix").getAsString() : "";
                    String outputDir = args.has("outputDir") ? args.get("outputDir").getAsString() : "";
                    JsonObject out = new JsonObject();
                    JsonArray exported = new JsonArray();
                    List<ClassDef> targets = new ArrayList<>();
                    if (!className.isEmpty()) {
                        targets.add(findClassDef(className));
                    } else {
                        for (ClassDef classDef : classTree.classMap.values()) {
                            if (packagePrefix.isEmpty() || classDef.getType().startsWith(packagePrefix)) {
                                targets.add(classDef);
                            }
                        }
                    }
                    Collections.sort(targets, new java.util.Comparator<ClassDef>() {
                        public int compare(ClassDef a, ClassDef b) { return a.getType().compareTo(b.getType()); }
                    });
                    if (!outputDir.isEmpty()) {
                        for (ClassDef classDef : targets) {
                            String rel = classDef.getType().substring(1, classDef.getType().length() - 1) + ".smali";
                            File outFile = new File(outputDir, rel);
                            writeTextFile(outFile, getPureSmali(classDef));
                            exported.add(outFile.getAbsolutePath());
                        }
                        out.addProperty("outputDir", outputDir);
                        out.addProperty("count", targets.size());
                        out.add("files", exported);
                        textObj.addProperty("text", gson.toJson(out));
                    } else if (targets.size() == 1) {
                        textObj.addProperty("text", getPureSmali(targets.get(0)));
                    } else {
                        for (ClassDef classDef : targets) {
                            JsonObject item = new JsonObject();
                            item.addProperty("className", classDef.getType());
                            item.addProperty("smali", getPureSmali(classDef));
                            exported.add(item);
                            if (exported.size() >= 20 && !args.has("all")) break;
                        }
                        out.addProperty("count", targets.size());
                        out.add("classes", exported);
                        textObj.addProperty("text", gson.toJson(out));
                    }
                } else if ("dex_import_smali".equals(toolName)) {
                    List<String> smaliSources = new ArrayList<>();
                    if (args.has("smali")) smaliSources.add(args.get("smali").getAsString());
                    if (args.has("path")) smaliSources.add(readTextFile(new File(args.get("path").getAsString())));
                    if (args.has("paths") && args.get("paths").isJsonArray()) {
                        JsonArray paths = args.getAsJsonArray("paths");
                        for (int i = 0; i < paths.size(); i++) smaliSources.add(readTextFile(new File(paths.get(i).getAsString())));
                    }
                    if (smaliSources.isEmpty()) throw new Exception("smali, path, or paths is required");
                    JsonArray imported = new JsonArray();
                    boolean dryRun = args.has("dryRun") && args.get("dryRun").getAsBoolean();
                    for (String smaliCode : smaliSources) {
                        ClassDef assembled = Smali.assemble(smaliCode, new SmaliOptions(), classTree.getOpenedDexVersion());
                        imported.add(assembled.getType());
                        if (!dryRun) classTree.saveClassDef(assembled);
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("dryRun", dryRun);
                    out.addProperty("count", imported.size());
                    out.add("classes", imported);
                    textObj.addProperty("text", gson.toJson(out));
                } else if ("dex_rename_class".equals(toolName)) {
                    String oldClass = normalizeClassDescriptor(args.get("oldClass").getAsString());
                    String newClass = normalizeClassDescriptor(args.get("newClass").getAsString());
                    boolean dryRun = args.has("dryRun") && args.get("dryRun").getAsBoolean();
                    int changed = replaceAcrossClasses(oldClass, newClass, dryRun);
                    if (!dryRun) classTree.removeClass(oldClass.substring(1, oldClass.length() - 1));
                    textObj.addProperty("text", (dryRun ? "Dry run: " : "") + "Renamed class references in " + changed + " classes");
                } else if ("dex_rename_method".equals(toolName)) {
                    String oldSignature = args.get("oldSignature").getAsString();
                    String newName = args.get("newName").getAsString();
                    int arrow = oldSignature.indexOf("->");
                    int paren = oldSignature.indexOf("(", arrow);
                    if (arrow < 0 || paren < 0) throw new Exception("oldSignature must look like Lpkg/Cls;->method(I)V");
                    String owner = oldSignature.substring(0, arrow + 2);
                    String ownerClass = oldSignature.substring(0, arrow);
                    String oldRef = oldSignature.substring(arrow + 2);
                    String newRef = newName + oldSignature.substring(paren);
                    boolean dryRun = args.has("dryRun") && args.get("dryRun").getAsBoolean();
                    int changed = replaceMemberReferenceAcrossClasses(ownerClass, oldSignature, owner + newRef, oldRef, newRef, dryRun);
                    textObj.addProperty("text", (dryRun ? "Dry run: " : "") + "Renamed method references in " + changed + " classes");
                } else if ("dex_rename_field".equals(toolName)) {
                    String oldSignature = args.get("oldSignature").getAsString();
                    String newName = args.get("newName").getAsString();
                    int arrow = oldSignature.indexOf("->");
                    int colon = oldSignature.indexOf(":", arrow);
                    if (arrow < 0 || colon < 0) throw new Exception("oldSignature must look like Lpkg/Cls;->field:Z");
                    String owner = oldSignature.substring(0, arrow + 2);
                    String ownerClass = oldSignature.substring(0, arrow);
                    String oldRef = oldSignature.substring(arrow + 2);
                    String newRef = newName + oldSignature.substring(colon);
                    boolean dryRun = args.has("dryRun") && args.get("dryRun").getAsBoolean();
                    int changed = replaceMemberReferenceAcrossClasses(ownerClass, oldSignature, owner + newRef, oldRef, newRef, dryRun);
                    textObj.addProperty("text", (dryRun ? "Dry run: " : "") + "Renamed field references in " + changed + " classes");
                } else if ("dex_get_call_graph".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String methodName = args.has("methodName") ? args.get("methodName").getAsString() : "";
                    String methodSig = args.has("methodSignature") ? args.get("methodSignature").getAsString() : "";
                    String direction = args.has("direction") ? args.get("direction").getAsString() : "outgoing";
                    JsonArray edges = new JsonArray();
                    if ("incoming".equalsIgnoreCase(direction) && !methodSig.isEmpty()) {
                        for (ClassDef c : classTree.classMap.values()) collectCallEdges(c, null, methodSig, edges);
                    } else {
                        ClassDef c = findClassDef(className);
                        Method target = (!methodName.isEmpty() || !methodSig.isEmpty()) ? findMethod(c, methodName, methodSig) : null;
                        collectCallEdges(c, target, null, edges);
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("total", edges.size());
                    out.add("edges", edges);
                    textObj.addProperty("text", gson.toJson(out));
                } else if ("dex_get_constants".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String filter = args.has("filter") ? args.get("filter").getAsString() : "";
                    int limit = args.has("limit") ? args.get("limit").getAsInt() : 300;
                    JsonArray constants = new JsonArray();
                    List<ClassDef> targets = new ArrayList<>();
                    if (!className.isEmpty()) targets.add(findClassDef(className)); else targets.addAll(classTree.classMap.values());
                    for (ClassDef c : targets) {
                        String[] lines = getPureSmali(c).split("\n");
                        for (String line : lines) {
                            String t = line.trim();
                            if (t.startsWith("const") || t.contains("const-string")) {
                                if (filter.isEmpty() || t.contains(filter)) {
                                    JsonObject item = new JsonObject();
                                    item.addProperty("className", c.getType());
                                    item.addProperty("line", t);
                                    constants.add(item);
                                    if (constants.size() >= limit) break;
                                }
                            }
                        }
                        if (constants.size() >= limit) break;
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("total", constants.size());
                    out.add("constants", constants);
                    textObj.addProperty("text", gson.toJson(out));
                } else if ("dex_patch_batch".equals(toolName)) {
                    JsonArray ops = args.getAsJsonArray("operations");
                    JsonArray results = new JsonArray();
                    for (int i = 0; i < ops.size(); i++) {
                        JsonObject op = ops.get(i).getAsJsonObject();
                        String opTool = op.get("tool").getAsString();
                        if ("dex_patch_batch".equals(opTool) || "dex_load".equals(opTool)) throw new Exception("Unsupported batch tool: " + opTool);
                        JsonObject opArgs = op.has("args") ? op.getAsJsonObject("args") : new JsonObject();
                        String raw = executeTool(opTool, opArgs, id);
                        JsonObject parsed = JsonParser.parseString(raw).getAsJsonObject();
                        JsonObject opResult = parsed.getAsJsonObject("result");
                        results.add(opResult);
                        if (opResult.has("isError") && opResult.get("isError").getAsBoolean()) {
                            throw new Exception("Batch stopped at operation " + i + ": " + opResult.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
                        }
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("applied", results.size());
                    out.add("results", results);
                    textObj.addProperty("text", gson.toJson(out));
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
            throw new Exception("Error executing " + toolName + ": " + e.getMessage(), e);
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

    private static boolean searchMatches(String value, String query, Pattern pattern, boolean ignoreCase) {
        if (value == null) {
            return false;
        }
        if (pattern != null) {
            return pattern.matcher(value).find();
        }
        if (ignoreCase) {
            return value.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT));
        }
        return value.contains(query);
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

    private static String getBaseSmali(ClassDef classDef) throws Exception {
        StringWriter sw = new StringWriter();
        BaksmaliWriter bw = new BaksmaliWriter(sw);
        new com.android.tools.smali.baksmali.Adaptors.ClassDefinition(new BaksmaliOptions(), classDef).writeTo(bw);
        bw.close();
        return sw.toString();
    }

    private static String buildLineDiff(String oldText, String newText) {
        if (oldText.equals(newText)) return "No changes.";
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);
        int max = Math.max(oldLines.length, newLines.length);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < max; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;
            if (oldLine == null || newLine == null || !oldLine.equals(newLine)) {
                sb.append("@@ line ").append(i + 1).append(" @@\n");
                if (oldLine != null) sb.append("- ").append(oldLine).append("\n");
                if (newLine != null) sb.append("+ ").append(newLine).append("\n");
                shown++;
                if (shown >= 200) {
                    sb.append("...diff truncated...\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static String normalizeClassDescriptor(String value) {
        if (value.startsWith("L") && value.endsWith(";")) return value;
        return "L" + value.replace('.', '/') + ";";
    }

    private static int replaceAcrossClasses(String oldText, String newText, boolean dryRun) throws Exception {
        int changed = 0;
        List<ClassDef> snapshot = new ArrayList<>(classTree.classMap.values());
        for (ClassDef classDef : snapshot) {
            String smali = getPureSmali(classDef);
            if (smali.contains(oldText)) {
                String updated = smali.replace(oldText, newText);
                Smali.assemble(updated, new SmaliOptions(), classTree.getOpenedDexVersion());
                changed++;
                if (!dryRun) {
                    ClassDef assembled = Smali.assemble(updated, new SmaliOptions(), classTree.getOpenedDexVersion());
                    classTree.saveClassDef(assembled);
                }
            }
        }
        return changed;
    }

    private static int replaceMemberReferenceAcrossClasses(String ownerClass, String oldFullRef, String newFullRef, String oldMemberRef, String newMemberRef, boolean dryRun) throws Exception {
        int changed = 0;
        List<ClassDef> snapshot = new ArrayList<>(classTree.classMap.values());
        for (ClassDef classDef : snapshot) {
            String smali = getPureSmali(classDef);
            String updated = smali.replace(oldFullRef, newFullRef);
            if (classDef.getType().equals(ownerClass)) {
                updated = updated.replace(oldMemberRef, newMemberRef);
            }
            if (!updated.equals(smali)) {
                Smali.assemble(updated, new SmaliOptions(), classTree.getOpenedDexVersion());
                changed++;
                if (!dryRun) {
                    ClassDef assembled = Smali.assemble(updated, new SmaliOptions(), classTree.getOpenedDexVersion());
                    classTree.saveClassDef(assembled);
                }
            }
        }
        return changed;
    }

    private static String readTextFile(File file) throws Exception {
        byte[] bytes = classTree.read(file.getAbsolutePath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeTextFile(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
    }

    private static String methodReferenceSignature(MethodReference mRef) {
        StringBuilder sb = new StringBuilder();
        sb.append(mRef.getDefiningClass()).append("->").append(mRef.getName()).append("(");
        for (CharSequence param : mRef.getParameterTypes()) sb.append(param);
        sb.append(")").append(mRef.getReturnType());
        return sb.toString();
    }

    private static void collectCallEdges(ClassDef classDef, Method targetMethod, String incomingTarget, JsonArray edges) {
        for (Method method : classDef.getMethods()) {
            if (targetMethod != null && !methodSignature(classDef.getType(), method).equals(methodSignature(classDef.getType(), targetMethod))) continue;
            MethodImplementation impl = method.getImplementation();
            if (impl == null) continue;
            String from = methodSignature(classDef.getType(), method);
            for (Instruction instruction : impl.getInstructions()) {
                if (instruction instanceof ReferenceInstruction) {
                    Reference ref = ((ReferenceInstruction) instruction).getReference();
                    if (ref instanceof MethodReference) {
                        String to = methodReferenceSignature((MethodReference) ref);
                        if (incomingTarget == null || incomingTarget.equals(to)) {
                            JsonObject edge = new JsonObject();
                            edge.addProperty("from", from);
                            edge.addProperty("to", to);
                            edges.add(edge);
                            if (edges.size() >= 1000) return;
                        }
                    }
                }
            }
        }
    }
}

