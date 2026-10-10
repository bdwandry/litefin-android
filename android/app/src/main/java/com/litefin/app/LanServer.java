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
 *
 * HLS NOTE: The whole upstream URL is packed into ONE path segment, so the
 * final character of every request path is .m3u8 or .ts/.aac/... even for
 * dynamic segment URLs. When an upstream playlist contains RELATIVE URIs
 * (Jellyfin's master.m3u8 references "main.m3u8?MediaSourceId=..."), Hls.js
 * resolves them strictly per RFC (which strips our single encoded segment
 * entirely) and all follow-up requests arrive with NO target. To keep Hls.js
 * (and any other JS player resolving relative URIs) working, playlist
 * response bodies are rewritten here: every non-comment line and every
 * quoted URI attribute gets rewritten to ABSOLUTE loopback URLs that still
 * encode the request's original upstream target as their base.
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
            /*
             * Some HTTP stacks keep literal % sequences in the request target
             * un-decoded (the first URLDecoder pass turns our encoded target's
             * inner %3A into %253A when the client sent the host byte as a bare
             * percent-escape in a second layer). When that leaves an encoded
             * host remaining, decode one more time: targets are produced by a
             * single URLEncoder pass on this side, so at most one extra layer
             * can occur from proxy intermediaries (e.g. Hls.js normalizing the
             * URL string). Detect via "<digits>%3A" pattern right after the
             * http:// prefix (an IPv4 host followed by an encoded colon).
             */
            if (decoded.startsWith("http://") && decoded.length() > 7) {
                if (java.util.regex.Pattern.compile("^http://[0-9./]+%3A").matcher(decoded).find()) {
                    decoded = URLDecoder.decode(decoded, "UTF-8");
                } else if (decoded.startsWith("http://") && !decoded.regionMatches(7, "127.0.0.1", 0, 9)) {
                    // never re-route loopback requests (self-proxy guard)
                    if (java.util.regex.Pattern.compile("%253A").matcher(decoded).find()) {
                        decoded = URLDecoder.decode(decoded, "UTF-8");
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.d("LanServer", "url decode failed");
            return null;
        }

        /*
         * SELF-PROXY GUARD: a target pointing back at this very server would
         * recurse (each hop demanifests another upstream hop). Reject up front.
         */
        if (decoded.startsWith("http://127.0.0.1:" + boundPort + "/")) {
            android.util.Log.w("LanServer", "rejected self-referencing proxy target");
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

            InputStream body = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (body == null) {
                out.write(headOut.toString().getBytes("ISO-8859-1"));
                out.flush();
                return;
            }

            /*
             * HLS manifests are text/any-mime small bodies: we must buffer them
             * fully to rewrite relative URIs to absolute loopback URLs, which
             * changes Content-Length. Everything else streams straight through
             * with the upstream length/headers untouched.
             */
            boolean isManifest = ct != null && (ct.contains("mpegurl") || ct.contains("m3u"));
            if (!isManifest) {
                out.write(headOut.toString().getBytes("ISO-8859-1"));
                out.flush();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = body.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                return;
            }

            // Manifest path: read fully, rewrite, and serve with a correct length
            ByteArrayOutputStream manifest = new ByteArrayOutputStream();
            byte[] mbuf = new byte[16 * 1024];
            int mn;
            while ((mn = body.read(mbuf)) != -1) {
                manifest.write(mbuf, 0, mn);
            }
            String rewritten = rewritePlaylist(new String(manifest.toByteArray(), "UTF-8"), req.target, "http://127.0.0.1:" + client.getLocalPort());
            if (rewritten == null) {
                rewritten = new String(manifest.toByteArray(), "UTF-8");
            }
            byte[] bodyBytes = rewritten.getBytes("UTF-8");

            StringBuilder mHead = new StringBuilder();
            mHead.append("HTTP/1.1 ").append(code).append(' ').append(reasonPhrase(code)).append("\r\n");
            mHead.append("Content-Type: ").append(ct).append("\r\n");
            mHead.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            mHead.append("Cache-Control: no-store\r\n");
            mHead.append("Access-Control-Allow-Origin: *\r\n");
            mHead.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
            mHead.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
            mHead.append("Connection: close\r\n");
            mHead.append("\r\n");
            out.write(mHead.toString().getBytes("ISO-8859-1"));
            out.write(bodyBytes);
            out.flush();
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

    /**
     * Rewrite every URI inside an HLS playlist to the loopback proxy scheme.
     * Why rewriting is necessary: hls.js resolves relative URIs via
     * url-toolkit against <the request that fetched this manifest>, and that
     * request URL encodes the ENTIRE upstream target in ONE path segment
     * (e.g. /192.168.1.201%3A8096%2Fvideos%2Fabc%2Fmaster.m3u8). Strict RFC
     * relative resolution then produces /main.m3u8 — the encoded target is
     * lost and every subsequent request arrives target-less, unable to be
     * proxied. The universal fix (works for any JS or native HLS engine) is
     * to make the playlist contain ABSOLUTE loopback URLs that already carry
     * the correct encoded upstream base.
     *
     * Rules matched against every non-comment line (URI lines and quoted
     * URI attribute values such as EXT-X-KEY:URI="..." and
     * EXT-X-MEDIA:URI="..."):
     *   - already-absolute http(s) URIs: re-encoded onto the proxy as-is
     *   - relative URIs: resolved against the manifest's own upstream target
     *     (RFC path/std-resolve — directories/filename only, the query is
     *     dropped for "path-like" upstream targets), keeping query strings.
     * Query strings in the original are preserved and appended to the proxy
     * URL so Jellyfin's per-segment params (MediaSourceId, PlaySessionId,
     * ApiKey, ...) survive.
     */
    static String rewritePlaylist(String body, String upstreamTarget, String loopbackOrigin) {
        if (body == null || body.isEmpty()) return body;
        try {
            final String BASE_HOST; // "192.168.1.201%3A8096" — encoded host:port
            java.net.URI u = new java.net.URI(upstreamTarget);
            String rawAuthority = u.getRawAuthority();
            BASE_HOST = java.net.URLEncoder.encode(rawAuthority, "UTF-8").replace("+", "%20");
            final String rawPath = u.getRawPath(); // e.g. /videos/abc/master.m3u8 (unencoded-ish)
            final String baseQuery = u.getRawQuery(); // may be null
            final String loopPrefix = loopbackOrigin + "/";

            StringBuilder outLines = new StringBuilder(body.length() + 512);
            String[] lines = body.split("\r?\n", -1);
            for (String line : lines) {
                boolean isComment = line.startsWith("#");
                String outLine = line;
                if (isComment) {
                    // Rewrite each QUOTED uri-looking attribute (URI="...")
                    // EXT-X-KEY: ... URI="..." — retry, session key, etc.
                    // EXT-X-MEDIA: ... URI="..." / EXT-X-IMAGE-STREAM-INF:URI="..."
                    java.util.regex.Matcher qm = QUOTED_URI.matcher(line);
                    while (qm.find()) {
                        String uriVal = qm.group(1);
                        if (uriVal.isEmpty()) continue;
                        String resolved = resolveToLoopback(uriVal, BASE_HOST, rawPath, baseQuery, loopPrefix);
                        if (resolved != null) {
                            outLine = outLine.replace("URI=\"" + uriVal + "\"", "URI=\"" + resolved + "\"");
                        }
                    }
                } else {
                    // URI-only line (segment or variant playlist)
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        String resolved = resolveToLoopback(trimmed, BASE_HOST, rawPath, baseQuery, loopPrefix);
                        if (resolved != null) {
                            outLine = resolved;
                        }
                    }
                }
                outLines.append(outLine).append('\n');
            }
            return outLines.toString();
        } catch (Exception e) {
            android.util.Log.w("LanServer", "rewritePlaylist failed (passing through): " + e);
            return body;
        }
    }

    private static final java.util.regex.Pattern QUOTED_URI = java.util.regex.Pattern.compile("URI=\"([^\"]*)\"");

    /**
     * Resolve a manifest URI against the manifest's own upstream target and
     * re-encode it into the loopback proxy scheme. Returns null when the
     * input is not applicable (empty or non-http result).
     */
    private static String resolveToLoopback(String uri, String baseHost, String basePath, String baseQuery, String loopPrefix) {
        try {
            String resolved;
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                resolved = uri; // full URL already; loopPrefix call below re-encodes it
            } else if (uri.startsWith("/")) {
                /*
                 * Root-relative within the upstream server. baseHost arrives
                 * ENCODED ("192.168.1.201%3A8096") — that encoding is for the
                 * final path segment. Decoded here so the final URLEncoder pass
                 * below encodes the colon exactly once (no %253A doubling).
                 */
                String decodedHost = URLDecoder.decode(baseHost, "UTF-8");
                resolved = "http://" + decodedHost + uri;
            } else {
                // Relative to the manifest's directory.
                // RFC 3986 merge: base directory = basePath up to the last '/'.
                String dir = "";
                int slash = basePath.lastIndexOf('/');
                if (slash >= 0) dir = basePath.substring(0, slash + 1);
                if (uri.startsWith("./")) uri = uri.substring(2);
                // Handle ../ (rare in HLS)
                while (uri.startsWith("../")) {
                    uri = uri.substring(3);
                    int d = dir.lastIndexOf('/', dir.length() - 2);
                    dir = d >= 0 ? dir.substring(0, d + 1) : "/";
                }
                resolved = "http://" + URLDecoder.decode(baseHost, "UTF-8") + dir + uri;
            }
            /*
             * If the resolved target still contains double-encoded sequences
             * (e.g. a caller passed an already-encoded relative URI), normalize
             * them down to single encoding so the final URLEncoder pass emits
             * exactly one layer of escaping (no %253A that the server cannot
             * decode into a usable host).
             */
            while (resolved.contains("%253A") || resolved.contains("%252F") || resolved.contains("%253F")) {
                String before = resolved;
                resolved = resolved.replace("%253A", "%3A").replace("%252F", "%2F").replace("%253F", "%3F");
                if (resolved.equals(before)) break;
            }
            if (!resolved.startsWith("http")) return null;
            // The resolved target may be https (remote CDN segments). The
            // loopback server can only proxy http (channel read timeout and
            // HttpURLConnection trust default); pass those through untouched —
            // mixed-content does not apply to hls.js fetches in a secure
            // context... except Chromium still enforces it per-document.
            // HTTPS-rendered documents on appassets ARE a secure context while
            // http loopback is NOT, so http targets are fine here.
            if (resolved.startsWith("https://")) return null;
            String rest = resolved.substring("http://".length());
            String encoded = java.net.URLEncoder.encode(rest, "UTF-8").replace("+", "%20");
            return loopPrefix + encoded;
        } catch (Exception e) {
            return null;
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
