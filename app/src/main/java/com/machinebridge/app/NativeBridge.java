package com.machinebridge.app;

import android.content.Context;
import android.util.Log;

public class NativeBridge {
    private static final String TAG = "MachineBridgeNative";
    private static boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("machinebridge");
            sLoaded = true;
            Log.i(TAG, "libmachinebridge.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libmachinebridge.so", e);
        }
    }

    public static boolean isLibraryLoaded() {
        return sLoaded;
    }

    public static void init(Context context) {
        if (!sLoaded) return;
        String filesDir = context.getFilesDir().getAbsolutePath();
        String cacheDir = context.getCacheDir().getAbsolutePath();
        nativeInit(filesDir, cacheDir);
    }

    public static native void nativeInit(String filesDir, String cacheDir);

    public static native boolean nativeStartServer(
        int port,
        String host,
        String apiKey,
        String workspaceRoot,
        String defaultShell,
        boolean verboseLog,
        boolean exposeTunnel,
        String tunnelToken,
        String tunnelProtocol,
        String logLevel,
        int maxSessions,
        int sessionTtl
    );

    public static native void nativeStopServer();

    public static native boolean nativeIsServerRunning();

    public static native String nativeGetRecentLogs();

    public static native String nativeGetStatus();

    public static native String nativeGetTunnelUrl();

    public static native String nativeGetTunnelError();

    public static native String nativeRunPtySelfTest();

    public static native void nativeLogInfo(String tag, String message);

    private static void reportDownloadLog(String msg) {
        Log.i(TAG, msg);
        try {
            nativeLogInfo("TUNNEL", msg);
        } catch (Throwable ignored) {}
    }

    public static String downloadFile(String urlStr, String destPath) {
        java.net.HttpURLConnection conn = null;
        try {
            reportDownloadLog("[cloudflared] In-app download starting: " + urlStr);
            java.net.URL url = new java.net.URL(urlStr);

            // Configure permissive SSL context for legacy Android devices (e.g. Android 7.0 Nougat)
            // where modern Let's Encrypt root CAs are not present in the outdated system trust store
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            }, new java.security.SecureRandom());

            conn = (java.net.HttpURLConnection) url.openConnection();
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(sslContext.getSocketFactory());
                ((javax.net.ssl.HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
            }
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(90000);
            conn.setRequestProperty("User-Agent", "MachineBridge/1.0 (Android)");

            int status = conn.getResponseCode();
            int redirects = 0;
            while ((status == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                    || status == java.net.HttpURLConnection.HTTP_MOVED_PERM
                    || status == java.net.HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307 || status == 308) && redirects < 6) {
                String newUrl = conn.getHeaderField("Location");
                if (newUrl == null) break;
                conn.disconnect();
                url = new java.net.URL(url, newUrl);
                conn = (java.net.HttpURLConnection) url.openConnection();
                if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                    ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(sslContext.getSocketFactory());
                    ((javax.net.ssl.HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
                }
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(90000);
                conn.setRequestProperty("User-Agent", "MachineBridge/1.0 (Android)");
                status = conn.getResponseCode();
                redirects++;
            }

            if (status < 200 || status >= 300) {
                String errMsg = "HTTP " + status + " (" + conn.getResponseMessage() + ")";
                reportDownloadLog("[cloudflared] Download HTTP error: " + errMsg);
                return errMsg;
            }

            long totalBytes = conn.getContentLength();
            java.io.File destFile = new java.io.File(destPath);
            if (destFile.getParentFile() != null) {
                destFile.getParentFile().mkdirs();
            }
            java.io.File tempFile = new java.io.File(destPath + ".tmp");

            try (java.io.InputStream in = conn.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                long totalRead = 0;
                long lastLogTime = System.currentTimeMillis();
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 800) {
                        lastLogTime = now;
                        if (totalBytes > 0) {
                            int pct = (int) ((totalRead * 100) / totalBytes);
                            String progressMsg = String.format(
                                java.util.Locale.US,
                                "[cloudflared] Downloading: %d%% (%.1f MB / %.1f MB)",
                                pct,
                                totalRead / (1024.0 * 1024.0),
                                totalBytes / (1024.0 * 1024.0)
                            );
                            reportDownloadLog(progressMsg);
                        } else {
                            String progressMsg = String.format(
                                java.util.Locale.US,
                                "[cloudflared] Downloading: %.1f MB",
                                totalRead / (1024.0 * 1024.0)
                            );
                            reportDownloadLog(progressMsg);
                        }
                    }
                }
                out.flush();
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (destFile.exists()) destFile.delete();
                boolean renamed = tempFile.renameTo(destFile);
                if (renamed) {
                    destFile.setExecutable(true, false);
                    destFile.setReadable(true, false);
                    reportDownloadLog(String.format(
                        java.util.Locale.US,
                        "[cloudflared] In-app download finished successfully: %.1f MB",
                        destFile.length() / (1024.0 * 1024.0)
                    ));
                    return null; // SUCCESS
                } else {
                    return "Failed to rename temp file to destination: " + destPath;
                }
            }
            return "Downloaded temp file was empty or missing";
        } catch (Throwable t) {
            reportDownloadLog("[cloudflared] Download exception: " + t.getMessage());
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }
}
