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
            log("Response body: " + responseBody);

            sendHttpResponse(os, 200, "OK", "application/json", responseBody);
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

        try {
            if ("tools/list".equals(method)) {
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

    private static String createToolsListResponse(JsonElement id) {
        JsonObject res = new JsonObject();
        res.addProperty("jsonrpc", "2.0");
        res.add("id", id);

        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();

        // 1. dex_load
        JsonObject dexLoad = new JsonObject();
        dexLoad.addProperty("name", "dex_load");
        dexLoad.addProperty("description", "Loads one or more APK/DEX files into the editor memory.");
        JsonObject dlParams = new JsonObject();
        dlParams.addProperty("type", "object");
        JsonObject dlProps = new JsonObject();
        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "Absolute path to the DEX or APK file");
        dlProps.add("path", pathProp);
        dlParams.add("properties", dlProps);
        JsonArray dlReq = new JsonArray();
        dlReq.add("path");
        dlParams.add("required", dlReq);
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
        gmParams.add("properties", gmProps);
        JsonArray gmReq = new JsonArray();
        gmReq.add("className");
        gmReq.add("methodName");
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
        rimReq.add("methodName");
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
        JsonObject smaliProp = new JsonObject();
        smaliProp.addProperty("type", "string");
        smaliProp.addProperty("description", "New full Smali code of the method, including .method and .end method");
        rmProps.add("smali", smaliProp);
        rmParams.add("properties", rmProps);
        JsonArray rmReq = new JsonArray();
        rmReq.add("className");
        rmReq.add("methodName");
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
                String path = args.has("path") ? args.get("path").getAsString() : "";
                if (path.isEmpty()) {
                    throw new Exception("Path parameter is required");
                }

                // If path is relative or not absolute, resolve it
                File file = new File(path);
                if (!file.exists()) {
                    throw new Exception("File not found: " + path);
                }

                List<String> paths = Collections.singletonList(file.getAbsolutePath());
                // Cache dir
                String cacheDir = new File(System.getProperty("java.io.tmpdir"), "dex_mcp_cache").getAbsolutePath();
                classTree = new ClassTree(paths, cacheDir);

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
                    ClassDef classDef = findClassDef(className);

                    String smali = getPureSmali(classDef);
                    String methodSmali = extractMethod(smali, methodName);
                    if (methodSmali == null) {
                        throw new Exception("Method '" + methodName + "' not found in class " + className);
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
                } else if ("dex_replace_in_method".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String methodName = args.has("methodName") ? args.get("methodName").getAsString() : "";
                    String oldStr = args.has("old_str") ? args.get("old_str").getAsString() : "";
                    String newStr = args.has("new_str") ? args.get("new_str").getAsString() : "";

                    ClassDef classDef = findClassDef(className);
                    String classSmali = getPureSmali(classDef);
                    String methodSmali = extractMethod(classSmali, methodName);
                    if (methodSmali == null) {
                        throw new Exception("Method '" + methodName + "' not found in class " + className);
                    }

                    if (!methodSmali.contains(oldStr)) {
                        throw new Exception("Method body does not contain old_str");
                    }

                    String updatedMethodSmali = methodSmali.replace(oldStr, newStr);
                    String updatedClassSmali = classSmali.replace(methodSmali, updatedMethodSmali);

                    ClassDef assembled = Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                    classTree.saveClassDef(assembled);

                    textObj.addProperty("text", "Successfully replaced code and reassembled class: " + className);
                } else if ("dex_replace_method".equals(toolName)) {
                    String className = args.has("className") ? args.get("className").getAsString() : "";
                    String methodName = args.has("methodName") ? args.get("methodName").getAsString() : "";
                    String newMethodSmali = args.has("smali") ? args.get("smali").getAsString() : "";

                    ClassDef classDef = findClassDef(className);
                    String classSmali = getPureSmali(classDef);
                    String methodSmali = extractMethod(classSmali, methodName);
                    if (methodSmali == null) {
                        throw new Exception("Method '" + methodName + "' not found in class " + className);
                    }

                    String updatedClassSmali = classSmali.replace(methodSmali, newMethodSmali);

                    ClassDef assembled = Smali.assemble(updatedClassSmali, new SmaliOptions(), classTree.getOpenedDexVersion());
                    classTree.saveClassDef(assembled);

                    textObj.addProperty("text", "Successfully replaced entire method and reassembled class: " + className);
                } else if ("dex_save".equals(toolName)) {
                    String outPath = args.has("outputPath") ? args.get("outputPath").getAsString() : "";
                    boolean stripDebug = args.has("stripDebug") && args.get("stripDebug").getAsBoolean();

                    if (!outPath.isEmpty()) {
                        classTree.paths.set(0, outPath);
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
                } else {
                    throw new Exception("Unknown tool: " + toolName);
                }
            }
        } catch (Exception e) {
            textObj.addProperty("text", "Error executing " + toolName + ": " + e.getMessage());
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

    private static String extractMethod(String smaliCode, String methodName) {
        String[] lines = smaliCode.split("\n");
        StringBuilder sb = null;
        boolean inMethod = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(".method ") && trimmed.contains(" " + methodName + "(")) {
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
}
