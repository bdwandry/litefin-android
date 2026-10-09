package com.litefin.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ============================================================================
 * Loopback media proxy for the Android WebView shell
 * ============================================================================
 *
 * shouldInterceptRequest cannot reliably serve multiple ranged (206)
 * responses for the same media URL: Chromium merges an implicit
 * 'Content-Length: 0' into the intercepted response (capture shows
 * 'Content-Length: 0, <n>') and every second-or-later Range request to the
 * same URL fails with net::ERR_FAILED — killing playback of MP4 files whose
 * moov atom sits at the end (the media stack must repeatedly re-request the
 * tail while streaming).
 *
 * Fix: run a real HTTP server on 127.0.0.1 and let the WebView load media
 * directly from it. Loopback http is a secure context, so Chromium's
 * mixed-content gate does not apply, and the normal network stack handles
 * Range/206 natively — no interception quirks.
 *
 * URL scheme: http://127.0.0.1:<port>/<target>
 * where <target> is the URL-encoded http://host:port/path?query, same
 * encoding LanProxy uses. Media credentials ride in the query string
 * (Jellyfin accepts api_key=), which <video> elements can do but cannot
 * set headers for.
 * ============================================================================
 */
public final class LanServer {

    private static ServerSocket serverSocket;
    private static int boundPort = -1;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private LanServer() {
    }

    /** Start accepting connections. Idempotent; returns the bound port. */
    public static synchronized int start() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            return boundPort;
        }
        try {
            /*
             * Bind IPv4 loopback explicitly: getLoopbackAddress() can resolve
             * to ::1 (IPv6), while Chromium's network service connects to the
             * literal address we hand it (127.0.0.1, IPv4) — a v6-only listen
             * then refuses those connections (ERR_CONNECTION_REFUSED).
             */
            serverSocket = new ServerSocket(0, 64, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            boundPort = serverSocket.getLocalPort();
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        POOL.execute(() -> serve(client));
                    } catch (Exception e) {
                        if (serverSocket.isClosed()) return;
                    }
                }
            }, "LanServer-accept");
            acceptor.setDaemon(true);
            acceptor.start();
            android.util.Log.i("LanServer", "loopback proxy listening on 127.0.0.1:" + boundPort);
        } catch (Exception e) {
            android.util.Log.e("LanServer", "failed to start: " + e);
            boundPort = -1;
        }
        return boundPort;
    }

    public static int port() {
        return boundPort;
    }

    /**
     * Rewrite a plain-http absolute URL onto the loopback server.
     * Returns null for https targets (they load natively) or when the
     * server could not start. Mirrors LanProxy.buildProxiedUrl semantics.
     */
    public static String buildLoopbackUrl(String targetUrl) {
        if (targetUrl == null) return null;
        String trimmed = targetUrl.trim();
        if (!trimmed.startsWith("http://")) return null;
        if (boundPort <= 0 && start() <= 0) return null;
        try {
            String hostPortAndRest = trimmed.substring("http://".length());
            if (hostPortAndRest.isEmpty()) return null;
            String encoded = java.net.URLEncoder.encode(hostPortAndRest, "UTF-8").replace("+", "%20");
            return "http://127.0.0.1:" + boundPort + "/" + encoded;
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // Per-connection handling
    // ========================================================================

    private static void serve(Socket client) {
        try {
            android.util.Log.d("LanServer", "accept " + client.getRemoteSocketAddress());
            client.setTcpNoDelay(true);
            Request req = readRequest(client);
            if (req == null) {
                android.util.Log.d("LanServer", "no request parsed, closing");
                try { client.close(); } catch (Exception ignored) { }
                return;
            }
            android.util.Log.d("LanServer", "GET " + req.target + " range=" + req.range);
            proxy(client, req);
        } catch (Exception e) {
            android.util.Log.d("LanServer", "connection error: " + e);
            try { client.close(); } catch (Exception ignored) { }
        }
    }

    /** Minimal HTTP request (first line + Range header) over the raw socket. */
    private static final class Request {
        String target; // decoded upstream URL
        String range;  // Range request header (nullable)
    }

    private static Request readRequest(Socket client) throws Exception {
        InputStream in = client.getInputStream();
        client.setSoTimeout(10000);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        int total = 0;
        /*
         * Stream-safe header read: track the last 4 bytes in rolling vars
         * instead of re-scanning a growing copy every byte (the quadratic
         * toByteArray() version wedged on Chromium's multi-packet headers).
         */
        int w0 = -1, w1 = -1, w2 = -1, w3 = -1; // rolling last-4 window
        while ((b = in.read()) != -1) {
            head.write(b);
            w0 = w1; w1 = w2; w2 = w3; w3 = b;
            total++;
            if (total > MAX_HEADER_BYTES) return null;
            if (w0 == '\r' && w1 == '\n' && w2 == '\r' && w3 == '\n') {
                break;
            }
        }
        if (total == 0) {
            android.util.Log.d("LanServer", "request head empty (client closed before sending)");
            return null;
        }
        String text = head.toString("ISO-8859-1");
        android.util.Log.d("LanServer", "head bytes=" + total + " first=" + text.substring(0, Math.min(60, text.length())).replace("\r", "<CR>").replace("\n", "<LF>"));
        int firstBreak = text.indexOf("\r\n");
        if (firstBreak < 0) {
            android.util.Log.d("LanServer", "no CRLF in head, len=" + text.length());
            return null;
        }
        String firstLine = text.substring(0, firstBreak);
        // firstLine: "GET /<encoded> HTTP/1.1" (or OPTIONS preflight)
        int sp1 = firstLine.indexOf(' ');
        int sp2 = firstLine.lastIndexOf(' ');
        if (sp1 < 0 || sp2 <= sp1) {
            android.util.Log.d("LanServer", "malformed request line: " + firstLine);
            return null;
        }
        String method = firstLine.substring(0, sp1);
        if ("OPTIONS".equalsIgnoreCase(method)) {
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 204 No Content\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
                    + "Access-Control-Allow-Headers: Range, Content-Type\r\n"
                    + "Access-Control-Max-Age: 86400\r\n"
                    + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
            out.flush();
            client.close();
            return null;
        }
        String encodedPath = firstLine.substring(sp1 + 1, sp2);
        if (encodedPath.startsWith("/")) encodedPath = encodedPath.substring(1);
        String decoded;
        try {
            decoded = URLDecoder.decode(encodedPath, "UTF-8");
        } catch (Exception e) {
            android.util.Log.d("LanServer", "url decode failed");
            return null;
        }
        /* buildLoopbackUrl strips the scheme when encoding, exactly like
         * LanProxy — reconstruct the full upstream target here. */
        if (decoded.startsWith("http://")) {
            // Defensive: scheme already present (older paths)
        } else {
            decoded = "http://" + decoded;
        }
        Request req = new Request();
        req.target = decoded;
        String lower = text.toLowerCase();
        int ri = lower.indexOf("range:");
        if (ri >= 0) {
            int lineEnd = text.indexOf("\r\n", ri);
            int colon = text.indexOf(':', ri);
            if (lineEnd > colon) {
                req.range = text.substring(colon + 1, lineEnd).trim();
            }
        }
        return req;
    }

    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private static void proxy(Socket client, Request req) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(req.target).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000); // idle between buffers; Chromium aborts freely
            if (req.range != null) conn.setRequestProperty("Range", req.range);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String ct = conn.getContentType();
            if (ct == null) ct = "application/octet-stream";

            StringBuilder headOut = new StringBuilder();
            headOut.append("HTTP/1.1 ").append(code).append(' ').append(reasonPhrase(code)).append("\r\n");
            headOut.append("Content-Type: ").append(ct).append("\r\n");
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null) headOut.append("Content-Range: ").append(cr).append("\r\n");
            headOut.append("Accept-Ranges: bytes\r\n");
            headOut.append("Cache-Control: no-store\r\n");
            headOut.append("Access-Control-Allow-Origin: *\r\n");
            headOut.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
            headOut.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
            headOut.append("Connection: close\r\n");
            String cl = conn.getHeaderField("Content-Length");
            if (cl != null) headOut.append("Content-Length: ").append(cl).append("\r\n");
            headOut.append("\r\n");

            OutputStream out = client.getOutputStream();
            out.write(headOut.toString().getBytes("ISO-8859-1"));
            out.flush();

            InputStream body = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (body != null) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = body.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        } catch (SocketTimeoutException ste) {
            android.util.Log.d("LanServer", "upstream idle timeout (client probably done)");
        } catch (Exception e) {
            android.util.Log.d("LanServer", "proxy aborted: " + e);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
            try { client.close(); } catch (Exception ignored) { }
        }
    }

    private static String reasonPhrase(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default: return "Status " + code;
        }
    }
}
