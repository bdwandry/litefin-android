package com.litefin.app;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * ============================================================================
 * LAN Reverse Proxy for the Android WebView shell
 * ============================================================================
 *
 * The app runs on the virtual https origin (appassets.androidplatform.net).
 * Chromium's mixed-content gate hard-blocks renderer-path subresources
 * (<img> poster/logo/backdrop requests, <video> stream loads) over plain
 * http even when the shell requests MIXED_CONTENT_ALWAYS_ALLOW — that
 * setting reliably only affects fetch/XHR. Plain-IP Jellyfin servers are
 * almost always plain http, so LAN users got text data but no images/video.
 *
 * Fix: renderer-path URLs are rewritten onto this activity's trusted origin
 * as /proxy/http/<host>:<port>/<path>?<query>, and MainActivity's
 * shouldInterceptRequest hands those to {@link #handle}. The JVM performs
 * the outbound call directly, outside any web policy, and streams the
 * upstream response (Content-Type, Range support, status) back.
 *
 * https servers load natively and never touch this path, so behavior for
 * https://jellyfin.example.com users is unchanged.
 * ============================================================================
 */
public final class LanProxy {

    private static final String PROXY_PREFIX = "/proxy/";

    private LanProxy() {
    }

    /** True when this WebView request targets the proxy path. */
    public static boolean isProxyRequest(WebResourceRequest request) {
        String path = request.getUrl().getPath();
        return path != null && path.startsWith(PROXY_PREFIX) && path.length() > PROXY_PREFIX.length();
    }

    /**
     * Rewrite a plain-http absolute URL onto the trusted origin. Returns
     * null for https targets (they load natively) and malformed input.
     * The target is fully URL-encoded except that we keep the scheme token
     * readable so handle() can reconstruct it.
     */
    public static String buildProxiedUrl(String targetUrl) {
        if (targetUrl == null) return null;
        String trimmed = targetUrl.trim();
        if (!trimmed.startsWith("http://")) return null;
        String rest = trimmed.substring("http://".length());
        if (rest.isEmpty()) return null;
        try {
            String encoded = URLEncoder.encode(rest, "UTF-8")
                    .replace("+", "%20");
            return "https://appassets.androidplatform.net" + PROXY_PREFIX + "http/" + encoded;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Serve a proxied request (blocking; runs on a WebView background thread).
     * Reconstructs the upstream URL from the encoded path, forwards the
     * Authorization/Range headers, streams the body with the upstream
     * Content-Type, and passes through status + Range headers so seeking
     * works for video.
     */
    public static int ACTIVE = 0;

    public static WebResourceResponse handle(WebResourceRequest request) {
        String path = request.getUrl().getPath();
        String rest = path != null ? path.substring(PROXY_PREFIX.length()) : "";
        if (!rest.startsWith("http/")) return notFound();
        ACTIVE++;            android.util.Log.d("LanProxy", "handle enter, active=" + ACTIVE + " for " + request.getUrl().getLastPathSegment());

        // rest = "http/<url-encoded target>"
        String targetFragment = rest.substring("http/".length());
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(targetFragment, "UTF-8");
        } catch (Exception e) {
            return notFound();
        }
        String query = request.getUrl().getQuery();
        String target = "http://" + decoded + (query != null ? ("?" + query) : "");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod(request.getMethod());

            // Forward the web layer's headers (Auth, Range, If-None-Match...).
            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    String name = h.getKey();
                    if (name == null || name.equalsIgnoreCase("host")) continue;
                    conn.setRequestProperty(name, h.getValue());
                }
            }

            int code = conn.getResponseCode();
            android.util.Log.d("LanProxy", "  upstream code=" + code + " ct=" + conn.getContentType() + " contentRange=" + conn.getHeaderField("Content-Range") + " for " + target);
            String contentType = conn.getContentType();
            if (contentType == null) contentType = "application/octet-stream";
            InputStream body = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (body == null) {
                body = new ByteArrayInputStream(new byte[0]);
            }

            /*
             * CRITICAL: do NOT call conn.disconnect() here. The WebView reads
             * the InputStream we return asynchronously, after this method has
             * already returned; disconnecting in finally closes the socket
             * underneath the reader and the load fails with
             * "Got exception when calling read() on an InputStream returned
             * from shouldInterceptRequest: Socket closed". The connection is
             * released to the pool when the stream is fully consumed and
             * garbage-collected — HttpURLConnection handle cleanup happens
             * in its finalizer.
             */

            /*
             * Header pass-through notes:
             * - Content-Range / Accept-Ranges are echoed explicitly.
             * - 'Cache-Control: no-store' prevents Chromium from reusing the
             *   first 206 body for later seek requests against the same URL.
             * - Content-Length IS set explicitly (from upstream). Omitting it
             *   made Chromium bookkeep an implicit 'Content-Length: 0' that
             *   contradicted Content-Range on 206 responses; the media/fetch
             *   stack then rejected do second response for the same resource
             *   with net::ERR_FAILED ('Failed to fetch') and playback died
             *   with 'Format error' / 'data source error'.
             */
            Map<String, String> respHeaders = new HashMap<>();
            String range = conn.getHeaderField("Content-Range");
            if (range != null) respHeaders.put("Content-Range", range);
            String accept = conn.getHeaderField("Accept-Ranges");
            if (accept != null) respHeaders.put("Accept-Ranges", accept);
            respHeaders.put("Cache-Control", "no-store");
            String upstreamLen = conn.getHeaderField("Content-Length");
            if (upstreamLen != null) respHeaders.put("Content-Length", upstreamLen);

            WebResourceResponse resp = new WebResourceResponse(contentType, null, code, reason(code), respHeaders, body);
            android.util.Log.d("LanProxy", "handle exit, active=" + ACTIVE);
            return resp;
        } catch (Exception e) {
            android.util.Log.e("LanProxy", "proxy failed for " + target + ": " + e);
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
            ACTIVE--;
            android.util.Log.d("LanProxy", "handle exception exit, active=" + ACTIVE);
            return notFound();
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "Status " + code;
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }
}
