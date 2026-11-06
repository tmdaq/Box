package com.github.tvbox.osc.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 测试辅助：用 java.net.Socket 发原始 HTTP/1.1 GET 请求，按字节解析响应。
 * 请求总带 Connection: close，读到 EOF 为止；会解 chunked，但不做任何透明解压，
 * 所以能看到服务端真实写出的 Content-Encoding 和压缩后的字节。
 */
final class RawHttpClient {

    private static final int TIMEOUT_MS = 5000;

    private RawHttpClient() {
    }

    static Response get(int port, String path) throws IOException {
        return get(port, path, Collections.<String, String>emptyMap());
    }

    static Response get(int port, String path, Map<String, String> headers) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(TIMEOUT_MS);
            writeRequest(socket.getOutputStream(), path, headers);
            return parse(socket.getInputStream());
        }
    }

    /** 写出 GET 请求，供需要自己控制连接（比如读到一半就断开）的测试使用。 */
    static void writeRequest(OutputStream out, String path, Map<String, String> headers) throws IOException {
        StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1\r\n")
                .append("Connection: close\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static Response parse(InputStream in) throws IOException {
        String statusLine = readLine(in).trim(); // NanoHTTPD 在状态行末尾多写一个空格
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        boolean chunked = "chunked".equalsIgnoreCase(headers.get("Transfer-Encoding"));
        byte[] body = chunked ? readChunked(in) : readToEnd(in);
        return new Response(statusLine, headers, body);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int size = chunkSize(readLine(in)); size > 0; size = chunkSize(readLine(in))) {
            body.write(readExactly(in, size));
            readLine(in); // chunk 末尾的 CRLF
        }
        return body.toByteArray();
    }

    private static int chunkSize(String line) {
        int semicolon = line.indexOf(';');
        return Integer.parseInt((semicolon >= 0 ? line.substring(0, semicolon) : line).trim(), 16);
    }

    private static byte[] readExactly(InputStream in, int size) throws IOException {
        byte[] buffer = new byte[size];
        int read = 0;
        while (read < size) {
            int n = in.read(buffer, read, size - read);
            if (n < 0) throw new IOException("EOF inside chunk: " + read + "/" + size);
            read += n;
        }
        return buffer;
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            body.write(buffer, 0, n);
        }
        return body.toByteArray();
    }

    /** 读一行（去掉 CRLF），按 ISO-8859-1 解码。 */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0) throw new IOException("EOF before end of line: " + line);
            if (b != '\r') line.write(b);
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    static final class Response {
        final String statusLine;
        /** 头名不区分大小写。 */
        final Map<String, String> headers;
        /** 已解 chunked，未解压。 */
        final byte[] body;

        Response(String statusLine, Map<String, String> headers, byte[] body) {
            this.statusLine = statusLine;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name);
        }
    }
}
