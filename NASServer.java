package projectjavanas;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executors;

public class NASServer {

    static final int PORT = 8080;
    static final String STORAGE = "storage";
    static final String WEB = "web";
    static final String DB_URL = "jdbc:sqlite:database.db";

    static Map<String, String> sessions = new HashMap<>();

    public static void main(String[] args) throws Exception {

        // ✅ Load SQLite driver
        Class.forName("org.sqlite.JDBC");

        initializeStorage();
        initializeDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", new StaticFileHandler("index.html"));
        server.createContext("/style.css", new StaticFileHandler("style.css"));
        server.createContext("/script.js", new StaticFileHandler("script.js"));

        server.createContext("/login", new LoginHandler());
        server.createContext("/upload", new UploadHandler());
        server.createContext("/files", new FileListHandler());
        server.createContext("/download", new DownloadHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("Server running at http://localhost:" + PORT);
    }

    // ================= STORAGE =================
    static void initializeStorage() {
        File dir = new File(STORAGE);
        if (!dir.exists()) dir.mkdir();
    }

    // ================= DATABASE =================
    static void initializeDatabase() throws Exception {
        Connection conn = DriverManager.getConnection(DB_URL);

        Statement stmt = conn.createStatement();

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS users(
                username TEXT PRIMARY KEY,
                password TEXT NOT NULL
            )
        """);

        PreparedStatement check = conn.prepareStatement(
                "SELECT * FROM users WHERE username=?"
        );
        check.setString(1, "admin");

        ResultSet rs = check.executeQuery();

        if (!rs.next()) {
            PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO users VALUES (?,?)"
            );
            insert.setString(1, "admin");
            insert.setString(2, hash("1234"));
            insert.executeUpdate();
        }

        conn.close();
    }

    // ================= HASH =================
    static String hash(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = md.digest(input.getBytes());

        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ================= STATIC FILE =================
    static class StaticFileHandler implements HttpHandler {
        String fileName;

        StaticFileHandler(String fileName) {
            this.fileName = fileName;
        }

        public void handle(HttpExchange exchange) throws IOException {
            Path path = Paths.get(WEB, fileName);

            if (!Files.exists(path)) {
                String response = "File not found: " + path.toAbsolutePath();
                exchange.sendResponseHeaders(404, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
                return;
            }

            byte[] bytes = Files.readAllBytes(path);

            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    // ================= LOGIN =================
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {

            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                Map<String, String> params = parse(body);

                Connection conn = DriverManager.getConnection(DB_URL);

                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT * FROM users WHERE username=? AND password=?"
                );

                stmt.setString(1, params.get("username"));
                stmt.setString(2, hash(params.get("password")));

                ResultSet rs = stmt.executeQuery();

                String response;

                if (rs.next()) {
                    String token = UUID.randomUUID().toString();
                    sessions.put(token, params.get("username"));
                    response = "LOGIN_SUCCESS:" + token;
                } else {
                    response = "LOGIN_FAILED";
                }

                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();

            } catch (Exception e) {
                e.printStackTrace();
                String response = "SERVER_ERROR";
                exchange.sendResponseHeaders(500, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
            }
        }
    }

    // ================= UPLOAD =================
    static class UploadHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {

            try {
                String fileName = "file_" + System.currentTimeMillis();
                Path target = Paths.get(STORAGE, fileName);

                Files.copy(
                        exchange.getRequestBody(),
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );

                String response = "UPLOAD_SUCCESS";

                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();

            } catch (Exception e) {
                e.printStackTrace();
                String response = "UPLOAD_FAILED";
                exchange.sendResponseHeaders(500, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
            }
        }
    }

    // ================= FILE LIST =================
   static class FileListHandler implements HttpHandler {
    public void handle(HttpExchange exchange) throws IOException {

        File folder = new File(STORAGE);
        File[] files = folder.listFiles();

        StringBuilder sb = new StringBuilder();
        sb.append("<ul>");

        if (files != null) {
            for (File f : files) {
                sb.append("<li>")
                  .append(f.getName())
                  .append(" - <a href='/download?name=")
                  .append(f.getName())
                  .append("'>Download</a></li>");
            }
        }

        sb.append("</ul>");

        byte[] response = sb.toString().getBytes();

        exchange.getResponseHeaders().set("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}

    // ================= DOWNLOAD =================
    static class DownloadHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parse(query);

            String name = params.get("name");

            if (name == null) {
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }

            File file = new File(STORAGE, name);

            if (!file.exists()) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }

            exchange.getResponseHeaders().set(
    "Content-Disposition",
    "attachment; filename=\"" + name + "\""
);

exchange.sendResponseHeaders(200, file.length());
Files.copy(file.toPath(), exchange.getResponseBody());
exchange.close();
        }
    }

    // ================= PARSER =================
    static Map<String, String> parse(String query) {
        Map<String, String> map = new HashMap<>();

        if (query == null) return map;

        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2) {
                map.put(pair[0], URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        return map;
    }
}