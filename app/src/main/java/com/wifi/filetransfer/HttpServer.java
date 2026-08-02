package com.wifi.filetransfer;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpServer {

    private static final String TAG = "HttpServer";
    private final Context context;
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private boolean isRunning = false;

    public HttpServer(Context context, int port) {
        this.context = context;
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public synchronized void start() throws IOException {
        if (isRunning) return;
        serverSocket = new ServerSocket(port);
        threadPool = Executors.newFixedThreadPool(15);
        isRunning = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        Socket socket = serverSocket.accept();
                        if (isRunning) {
                            threadPool.execute(new ClientHandler(socket));
                        }
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting connection", e);
                        }
                    }
                }
            }
        }).start();
        Log.i(TAG, "Server started on port " + port);
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
        }
        if (threadPool != null) {
            threadPool.shutdown();
        }
        Log.i(TAG, "Server stopped");
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
                handleRequest(in, out);
            } catch (Exception e) {
                Log.e(TAG, "Error handling client request", e);
            } finally {
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing streams/socket", e);
                }
            }
        }

        private void handleRequest(InputStream in, OutputStream out) throws Exception {
            BufferedInputStream bin = new BufferedInputStream(in);
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int b;
            boolean headerEnded = false;
            byte[] lastFour = new byte[4];
            int pointer = 0;

            while ((b = bin.read()) != -1) {
                headerBuffer.write(b);
                lastFour[pointer % 4] = (byte) b;
                pointer++;

                if (pointer >= 4) {
                    int p0 = (pointer - 4) % 4;
                    int p1 = (pointer - 3) % 4;
                    int p2 = (pointer - 2) % 4;
                    int p3 = (pointer - 1) % 4;
                    if (lastFour[p0] == 13 && lastFour[p1] == 10 && lastFour[p2] == 13 && lastFour[p3] == 10) {
                        headerEnded = true;
                        break;
                    }
                }
            }

            if (!headerEnded) {
                sendErrorResponse(out, 400, "Bad Request", "Header did not end correctly");
                return;
            }

            String headersStr = headerBuffer.toString("UTF-8");
            String[] lines = headersStr.split("\r\n");
            if (lines.length == 0) {
                sendErrorResponse(out, 400, "Bad Request", "Empty request");
                return;
            }

            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) {
                sendErrorResponse(out, 400, "Bad Request", "Invalid request line");
                return;
            }

            String method = requestLine[0];
            String rawUri = requestLine[1];

            String uri = rawUri;
            Map<String, String> queryParams = new HashMap<>();
            int qIndex = rawUri.indexOf('?');
            if (qIndex != -1) {
                uri = rawUri.substring(0, qIndex);
                String qStr = rawUri.substring(qIndex + 1);
                queryParams = parseQueryParams(qStr);
            }

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon != -1) {
                    String key = line.substring(0, colon).trim().toLowerCase();
                    String val = line.substring(colon + 1).trim();
                    headers.put(key, val);
                }
            }

            // Authentication verification
            boolean isAuthRequired = PasswordUtils.isPasswordEnabled(context);
            boolean authenticated = false;

            if (isAuthRequired && uri.startsWith("/api/") && !uri.equals("/api/auth")) {
                String authParam = queryParams.get("auth");
                if (authParam != null) {
                    authenticated = verifySessionToken(authParam);
                }
                if (!authenticated && headers.containsKey("cookie")) {
                    String cookieHeader = headers.get("cookie");
                    String token = parseCookie(cookieHeader, "session_token");
                    if (token != null) {
                        authenticated = verifySessionToken(token);
                    }
                }

                if (!authenticated) {
                    sendJsonResponse(out, 401, "{\"status\":\"error\",\"message\":\"Unauthorized\"}");
                    return;
                }
            }

            if (method.equals("GET")) {
                handleGet(uri, queryParams, out);
            } else if (method.equals("POST")) {
                handlePost(uri, queryParams, headers, bin, out);
            } else {
                sendErrorResponse(out, 405, "Method Not Allowed", "Unsupported HTTP Method: " + method);
            }
        }

        private boolean verifySessionToken(String token) {
            android.content.SharedPreferences prefs = context.getSharedPreferences("wifi_transfer_prefs", Context.MODE_PRIVATE);
            String storedHash = prefs.getString("password_hash", "");
            if (storedHash.isEmpty()) {
                // Password enabled but none set, accept empty password as authenticated
                return true;
            }
            String expectedToken = PasswordUtils.hashPassword(storedHash, "session_salt");
            return expectedToken.equals(token);
        }

        private String parseCookie(String cookieHeader, String cookieName) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String[] pair = cookie.trim().split("=");
                if (pair.length == 2 && pair[0].trim().equals(cookieName)) {
                    return pair[1].trim();
                }
            }
            return null;
        }

        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> params = new HashMap<>();
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=");
                try {
                    String k = URLDecoder.decode(kv[0], "UTF-8");
                    String v = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    params.put(k, v);
                } catch (UnsupportedEncodingException e) {
                    // fallback
                }
            }
            return params;
        }

        private void handleGet(String uri, Map<String, String> queryParams, OutputStream out) throws Exception {
            if (uri.equals("/") || uri.equals("/index.html")) {
                serveAssetFile("web/index.html", "text/html", out);
            } else if (uri.equals("/api/drives")) {
                listDrives(out);
            } else if (uri.equals("/api/files")) {
                String path = queryParams.get("path");
                listDirectory(path, out);
            } else if (uri.equals("/api/download")) {
                String path = queryParams.get("path");
                downloadFile(path, out);
            } else {
                sendErrorResponse(out, 404, "Not Found", "File not found: " + uri);
            }
        }

        private void serveAssetFile(String assetPath, String contentType, OutputStream out) throws IOException {
            InputStream is = null;
            try {
                is = context.getAssets().open(assetPath);
                out.write("HTTP/1.1 200 OK\r\n".getBytes());
                out.write(("Content-Type: " + contentType + "; charset=UTF-8\r\n").getBytes());
                out.write("Connection: close\r\n".getBytes());
                out.write("\r\n".getBytes());

                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                if (is != null) is.close();
            }
        }

        private void listDrives(OutputStream out) throws IOException {
            // Keep unique paths, preserving order
            java.util.LinkedHashMap<String, String> drivesMap = new java.util.LinkedHashMap<>();

            // 1. Primary Internal Storage
            File primaryStorage = Environment.getExternalStorageDirectory();
            String primaryPath = primaryStorage.getAbsolutePath();
            drivesMap.put(primaryPath, "Internal Storage");

            // 2. Discover via getExternalFilesDirs
            try {
                File[] externalDirs = context.getExternalFilesDirs(null);
                if (externalDirs != null) {
                    for (File f : externalDirs) {
                        if (f != null) {
                            String absPath = f.getAbsolutePath();
                            int idx = absPath.indexOf("/Android/data/");
                            if (idx > 0) {
                                String rootPath = absPath.substring(0, idx);
                                if (!drivesMap.containsKey(rootPath)) {
                                    File rootFile = new File(rootPath);
                                    String name = rootFile.getName();
                                    drivesMap.put(rootPath, "Drive (" + name + ")");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error discovering external dirs via getExternalFilesDirs", e);
            }

            // 3. Discover via listing /storage
            try {
                File storageDir = new File("/storage");
                if (storageDir.exists() && storageDir.isDirectory()) {
                    File[] files = storageDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName();
                            if (!name.equals("self") && !name.equals("emulated") && !name.equals("container") && !name.equals("knox")) {
                                String absPath = f.getAbsolutePath();
                                if (!drivesMap.containsKey(absPath)) {
                                    drivesMap.put(absPath, "Drive (" + name + ")");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error discovering drives via /storage listing", e);
            }

            // Build JSON output
            StringBuilder json = new StringBuilder("[");
            int count = 0;
            for (Map.Entry<String, String> entry : drivesMap.entrySet()) {
                if (count > 0) {
                    json.append(",");
                }
                json.append("{");
                json.append("\"name\":\"").append(escapeJson(entry.getValue())).append("\",");
                json.append("\"path\":\"").append(escapeJson(entry.getKey())).append("\"");
                json.append("}");
                count++;
            }
            json.append("]");

            sendJsonResponse(out, 200, json.toString());
        }

        private void listDirectory(String path, OutputStream out) throws IOException {
            if (path == null || path.trim().isEmpty()) {
                sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Missing path\"}");
                return;
            }

            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                sendJsonResponse(out, 404, "{\"status\":\"error\",\"message\":\"Directory not found\"}");
                return;
            }

            File[] files = dir.listFiles();
            StringBuilder json = new StringBuilder("[");
            if (files != null) {
                int count = 0;
                for (File f : files) {
                    if (count > 0) {
                        json.append(",");
                    }
                    json.append("{");
                    json.append("\"name\":\"").append(escapeJson(f.getName())).append("\",");
                    json.append("\"path\":\"").append(escapeJson(f.getAbsolutePath())).append("\",");
                    json.append("\"is_dir\":").append(f.isDirectory()).append(",");
                    json.append("\"size\":").append(f.isDirectory() ? 0 : f.length()).append(",");
                    json.append("\"last_modified\":").append(f.lastModified());
                    json.append("}");
                    count++;
                }
            }
            json.append("]");

            sendJsonResponse(out, 200, json.toString());
        }

        private void downloadFile(String path, OutputStream out) throws IOException {
            if (path == null || path.trim().isEmpty()) {
                sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Missing path\"}");
                return;
            }

            File file = new File(path);
            if (!file.exists() || file.isDirectory()) {
                sendJsonResponse(out, 404, "{\"status\":\"error\",\"message\":\"File not found\"}");
                return;
            }

            out.write("HTTP/1.1 200 OK\r\n".getBytes());
            out.write("Content-Type: application/octet-stream\r\n".getBytes());
            out.write(("Content-Length: " + file.length() + "\r\n").getBytes());
            out.write(("Content-Disposition: attachment; filename=\"" + escapeHeaderValue(file.getName()) + "\"\r\n").getBytes());
            out.write("Connection: close\r\n".getBytes());
            out.write("\r\n".getBytes());

            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                byte[] buffer = new byte[16384];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                if (fis != null) fis.close();
            }
        }

        private void handlePost(String uri, Map<String, String> queryParams, Map<String, String> headers, BufferedInputStream bin, OutputStream out) throws Exception {
            if (uri.equals("/api/auth")) {
                String password = queryParams.get("password");
                if (password == null) {
                    password = "";
                }
                
                boolean success = PasswordUtils.verifyPassword(context, password);
                if (success) {
                    android.content.SharedPreferences prefs = context.getSharedPreferences("wifi_transfer_prefs", Context.MODE_PRIVATE);
                    String storedHash = prefs.getString("password_hash", "");
                    String token = PasswordUtils.hashPassword(storedHash, "session_salt");
                    
                    // Respond with Cookie and JSON token
                    out.write("HTTP/1.1 200 OK\r\n".getBytes());
                    out.write(("Set-Cookie: session_token=" + token + "; Path=/; Max-Age=31536000\r\n").getBytes());
                    out.write("Content-Type: application/json; charset=UTF-8\r\n".getBytes());
                    String json = "{\"status\":\"success\",\"token\":\"" + token + "\"}";
                    out.write(("Content-Length: " + json.getBytes("UTF-8").length + "\r\n").getBytes());
                    out.write("Connection: close\r\n".getBytes());
                    out.write("\r\n".getBytes());
                    out.write(json.getBytes("UTF-8"));
                    out.flush();
                } else {
                    sendJsonResponse(out, 401, "{\"status\":\"error\",\"message\":\"Incorrect Password\"}");
                }
            } else if (uri.equals("/api/create_folder")) {
                String parentPath = queryParams.get("parent_path");
                String name = queryParams.get("name");
                if (parentPath == null || name == null || name.trim().isEmpty()) {
                    sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Missing arguments\"}");
                    return;
                }
                File folder = new File(parentPath, name);
                if (folder.exists()) {
                    sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Folder already exists\"}");
                } else if (folder.mkdirs()) {
                    sendJsonResponse(out, 200, "{\"status\":\"success\"}");
                } else {
                    sendJsonResponse(out, 500, "{\"status\":\"error\",\"message\":\"Could not create directory. Check storage permissions.\"}");
                }
            } else if (uri.equals("/api/rename")) {
                String path = queryParams.get("path");
                String newName = queryParams.get("new_name");
                if (path == null || newName == null || newName.trim().isEmpty()) {
                    sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Missing arguments\"}");
                    return;
                }
                File src = new File(path);
                if (!src.exists()) {
                    sendJsonResponse(out, 404, "{\"status\":\"error\",\"message\":\"Target file/folder not found\"}");
                    return;
                }
                File dest = new File(src.getParent(), newName);
                if (dest.exists()) {
                    sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Target file/folder already exists\"}");
                } else if (src.renameTo(dest)) {
                    sendJsonResponse(out, 200, "{\"status\":\"success\"}");
                } else {
                    sendJsonResponse(out, 500, "{\"status\":\"error\",\"message\":\"Rename failed\"}");
                }
            } else if (uri.equals("/api/delete")) {
                String path = queryParams.get("path");
                if (path == null) {
                    sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Missing path\"}");
                    return;
                }
                File file = new File(path);
                if (!file.exists()) {
                    sendJsonResponse(out, 404, "{\"status\":\"error\",\"message\":\"File not found\"}");
                    return;
                }
                deleteRecursive(file);
                sendJsonResponse(out, 200, "{\"status\":\"success\"}");
            } else if (uri.equals("/api/copy_paste")) {
                String srcPath = queryParams.get("src_path");
                String destDir = queryParams.get("dest_dir");
                if (srcPath == null || destDir == null) {
                    sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Missing src_path or dest_dir\"}");
                    return;
                }
                File src = new File(srcPath);
                if (!src.exists()) {
                    sendJsonResponse(out, 404, "{\"status\":\"error\",\"message\":\"Source file not found\"}");
                    return;
                }
                File dest = new File(destDir, src.getName());
                try {
                    copyRecursive(src, dest);
                    sendJsonResponse(out, 200, "{\"status\":\"success\"}");
                } catch (IOException e) {
                    sendJsonResponse(out, 500, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
                }
            } else if (uri.equals("/api/upload")) {
                String destDir = queryParams.get("dest_dir");
                String filename = queryParams.get("filename");
                String contentLengthStr = headers.get("content-length");
                
                if (destDir == null || filename == null || contentLengthStr == null) {
                    sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Missing upload metadata\"}");
                    return;
                }
                
                long contentLength = Long.parseLong(contentLengthStr);
                File target = new File(destDir, filename);
                
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(target);
                    byte[] buffer = new byte[16384];
                    long totalRead = 0;
                    int read;
                    while (totalRead < contentLength) {
                        int toRead = (int) Math.min(buffer.length, contentLength - totalRead);
                        read = bin.read(buffer, 0, toRead);
                        if (read == -1) {
                            throw new IOException("Unexpected end of stream during upload");
                        }
                        fos.write(buffer, 0, read);
                        totalRead += read;
                    }
                    fos.flush();
                    sendJsonResponse(out, 200, "{\"status\":\"success\"}");
                } catch (Exception e) {
                    if (target.exists()) {
                        target.delete(); // cleanup incomplete file
                    }
                    sendJsonResponse(out, 500, "{\"status\":\"error\",\"message\":\"Upload failed: " + e.getMessage() + "\"}");
                } finally {
                    if (fos != null) fos.close();
                }
            } else {
                sendErrorResponse(out, 404, "Not Found", "POST Endpoint not found: " + uri);
            }
        }

        private void copyRecursive(File src, File dest) throws IOException {
            if (src.isDirectory()) {
                if (!dest.exists()) {
                    dest.mkdirs();
                }
                String[] children = src.list();
                if (children != null) {
                    for (String child : children) {
                        copyRecursive(new File(src, child), new File(dest, child));
                    }
                }
            } else {
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = new FileInputStream(src);
                    out = new FileOutputStream(dest);
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                } finally {
                    if (in != null) in.close();
                    if (out != null) out.close();
                }
            }
        }

        private void deleteRecursive(File file) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            }
            file.delete();
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (ch < ' ') {
                            String t = "000" + java.lang.Integer.toHexString(ch);
                            sb.append("\\u").append(t.substring(t.length() - 4));
                        } else {
                            sb.append(ch);
                        }
                }
            }
            return sb.toString();
        }

        private String escapeHeaderValue(String s) {
            if (s == null) return "";
            return s.replace("\"", "\\\"");
        }

        private void sendJsonResponse(OutputStream out, int status, String json) throws IOException {
            String statusPhrase = "OK";
            if (status == 401) {
                statusPhrase = "Unauthorized";
            } else if (status == 400) {
                statusPhrase = "Bad Request";
            } else if (status == 404) {
                statusPhrase = "Not Found";
            } else if (status == 405) {
                statusPhrase = "Method Not Allowed";
            } else if (status == 500) {
                statusPhrase = "Internal Server Error";
            }
            out.write(("HTTP/1.1 " + status + " " + statusPhrase + "\r\n").getBytes());
            out.write("Content-Type: application/json; charset=UTF-8\r\n".getBytes());
            out.write(("Content-Length: " + json.getBytes("UTF-8").length + "\r\n").getBytes());
            out.write("Connection: close\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.write(json.getBytes("UTF-8"));
            out.flush();
        }

        private void sendErrorResponse(OutputStream out, int statusCode, String statusString, String body) throws IOException {
            String html = "<html><head><title>" + statusString + "</title></head><body><h1>" + statusCode + " " + statusString + "</h1><p>" + body + "</p></body></html>";
            out.write(("HTTP/1.1 " + statusCode + " " + statusString + "\r\n").getBytes());
            out.write("Content-Type: text/html; charset=UTF-8\r\n".getBytes());
            out.write(("Content-Length: " + html.getBytes("UTF-8").length + "\r\n").getBytes());
            out.write("Connection: close\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.write(html.getBytes("UTF-8"));
            out.flush();
        }
    }
}
