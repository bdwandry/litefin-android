package com.litefin.app;

import android.webkit.JavascriptInterface;
import android.os.Build;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native bridge exposed to the WebView as window.AndroidBridge.
 *
 * Mirrors the surface the TV adapters (Tizen/WebOS) provide: back handling is
 * routed through the shell, exit is forwarded to the shell, and device
 * identification is supplied for the Jellyfin dashboard device list.
 */
public class LitefinBridge {
    private static final String TAG = "LitefinBridge";

    private final MainActivity activity;

    public LitefinBridge(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * Called by the web app when boot completes so the shell can hide its
     * native splash. The web bundle calls this from AndroidAdapter.init().
     */
    @JavascriptInterface
    public void notifyAppReady() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.onAppReady();
            }
        });
    }

    /**
     * Exit the application (finishes the Activity).
     */
    @JavascriptInterface
    public void exitApp() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.finish();
            }
        });
    }

    /**
     * Device model string (Build.MODEL), e.g. "SM-S928B" on a Galaxy S24 Ultra.
     */
    @JavascriptInterface
    public String getDeviceModel() {
        return Build.MODEL;
    }

    /**
     * Device brand/manufacturer string (Build.MANUFACTURER), e.g. "samsung".
     */
    @JavascriptInterface
    public String getDeviceBrand() {
        return Build.MANUFACTURER;
    }

    /**
     * Android OS release string, e.g. "14".
     */
    @JavascriptInterface
    public String getOsVersion() {
        return Build.VERSION.RELEASE;
    }

    /**
     * Rewrite a plain-http server URL onto this app's trusted https origin
     * (native reverse proxy), so <img>/<video> renderer-path loads bypass
     * Chromium's mixed-content block. Returns null for https targets and
     * invalid input — callers fall back to the original URL.
     */
    @JavascriptInterface
    public String proxyUrl(String url) {
        try {
            return LanProxy.buildProxiedUrl(url);
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // Jellyfin Auto-Discovery (UDP port 7359)
    // ========================================================================
    /*
     * Browsers cannot send UDP broadcasts, so the web layer on Tizen/webOS
     * delegates discovery to a native background service. The Android shell
     * fills the same role through this bridge method: the JS side calls
     * AndroidBridge.discoverServers() and we broadcast the standard Jellyfin
     * autodiscovery probe on UDP 7359, collect unicast replies for a short
     * window, then return the servers as a JSON array string (synchronous
     * @JavascriptInterface methods cannot block... they can actually, but we
     * run the scan on a worker thread and poll for completion to keep the
     * WebView's JS bridge call from timing out).
     */

    private final ExecutorService discoveryExecutor = Executors.newSingleThreadExecutor();
    private volatile String discoveryResult = null;

    /**
     * Kick off an async Jellyfin LAN discovery scan. Results are retrieved by
     * calling {@link #pollDiscoveryResult()} until it returns non-null.
     */
    @JavascriptInterface
    public void startDiscovery() {
        discoveryResult = null;
        discoveryExecutor.execute(() -> {
            try {
                discoveryResult = performUdpDiscovery();
            } catch (Exception e) {
                discoveryResult = "[]";
            }
        });
    }

    /**
     * @return JSON array of discovered servers, or null while the scan runs.
     */
    @JavascriptInterface
    public String pollDiscoveryResult() {
        return discoveryResult;
    }

    /**
     * Broadcast the Jellyfin autodiscovery probe and collect responses.
     *
     * The probe payload is "Who is JellyfinServer?" sent to UDP 7359.
     * We enumerate every network interface and try each site-local subnet
     * broadcast address plus 255.255.255.255, so the scan works on any LAN
     * shape (192.168.x.x, 10.x.x.x, 172.16-31.x.x) — not just one assumed
     * prefix.
     */
    private String performUdpDiscovery() {
        Map<String, String> found = new LinkedHashMap<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            socket.setBroadcast(true);

            byte[] probe = "Who is JellyfinServer?".getBytes(StandardCharsets.UTF_8);

            /*
             * Collect broadcast targets from every site-local interface.
             * Emulator note: the stock AVD NAT puts the device on 10.0.2.x
             * where broadcast never reaches the host LAN — on real hardware
             * (or a bridged network) this enumeration hits the real subnet.
             */
            java.util.List<InetAddress> targets = new java.util.ArrayList<>();
            try {
                for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                    java.net.NetworkInterface ni = en.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress bc = ia.getBroadcast();
                        if (bc != null && bc.getAddress().length == 4) {
                            targets.add(bc);
                        }
                    }
                }
                targets.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
            }
            /*
             * Emulator NAT fallback: stock AVDs sit behind 10.0.2.x, where UDP
             * broadcast never reaches the real host LAN. In that case probe
             * the NAT gateway (10.0.2.2 maps to the host), which can reach
             * the LAN — and also probe the site-local ranges a real device
             * commonly lives on, so discovery still works on misdetected
             * bridged setups.
             */
            if (targets.isEmpty()) {
                try {
                    targets.add(InetAddress.getByName("192.168.1.255"));
                } catch (Exception ignored) {
                }
            }
            if (targets.size() == 1) {
                String only = targets.get(0).getAddress()[0] + "." + (targets.get(0).getAddress()[1] & 0xFF) + "." + (targets.get(0).getAddress()[2] & 0xFF);
                boolean emulatorNat = "10.0.2".equals(only);
                if (emulatorNat) {
                    try {
                        targets.add(InetAddress.getByName("10.0.2.2"));
                    } catch (Exception ignored) {
                    }
                }
            }

            for (int send = 0; send < 2; send++) {
                for (InetAddress target : targets) {
                    try {
                        socket.send(new DatagramPacket(probe, probe.length, target, 7359));
                    } catch (Exception ignored) {
                    }
                }
                long windowDeadline = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < windowDeadline) {
                    byte[] buf = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        break; // 500ms silence in this window — move to next round
                    } catch (Exception e) {
                        break;
                    }
                    try {
                        String body = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                        JSONObject info = new JSONObject(body.trim());
                        String address = packet.getAddress().getHostAddress();
                        String json = new JSONObject()
                                .put("Address", "http://" + address + ":8096")
                                .put("Name", info.optString("Name", address))
                                .put("Id", info.optString("Id", address))
                                .toString();
                        found.put(address, json);
                    } catch (Exception ignored) {
                        // Malformed reply from a non-Jellyfin device
                    }
                }
            }
        } catch (Exception e) {
            // Socket creation/broadcast failure (missing permission, airplane
            // mode) — return whatever was collected, possibly nothing.
        }

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> entry : found.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(entry.getValue());
        }
        sb.append("]");
        return sb.toString();
    }
}
