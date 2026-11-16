package com.f12012.android;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Direct & Google Drive downloader + ZIP extractor for F1 2012 game files.
 */
final class GameDownloader {
    // Set this to your public Google Drive / direct ZIP URL when ready, e.g.:
    // "https://drive.google.com/file/d/YOUR_FILE_ID/view?usp=sharing"
    static final String DEFAULT_GAME_URL = "";

    interface Listener {
        void onProgress(String statusText, int percent);
        void onSuccess();
        void onError(String message);
    }

    private GameDownloader() {}

    static String normalizeUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String url = rawUrl.trim();
        Matcher m = Pattern.compile("drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?.*?id=)([a-zA-Z0-9_-]+)")
                .matcher(url);
        if (m.find()) {
            String fileId = m.group(1);
            return "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download&confirm=t";
        }
        return url;
    }

    static void downloadAndInstall(String rawUrl, File targetDir, Listener listener) {
        String downloadUrl = normalizeUrl(rawUrl);
        if (downloadUrl.isEmpty()) {
            listener.onError("Ссылка для скачивания не указана.");
            return;
        }
        File parent = targetDir.getParentFile();
        if (parent == null) {
            listener.onError("Некорректный путь к папке игры.");
            return;
        }
        File tempZip = new File(parent, ".f1_2012_download.zip");
        File staging = new File(parent, ".f1_2012_staging");
        try {
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(new CookieManager());
            }
            listener.onProgress("Подключение к серверу...", 0);
            downloadFile(downloadUrl, tempZip, listener);
            listener.onProgress("Распаковка файлов игры...", 100);
            deleteRecursively(staging);
            if (!staging.mkdirs()) {
                throw new IOException("Не удалось создать временную папку распаковки.");
            }
            unzip(tempZip, staging, listener);
            File resolvedRoot = findGameRoot(staging);
            if (resolvedRoot == null) {
                throw new IOException("В архиве не найден default.xex.");
            }
            deleteRecursively(targetDir);
            if (!resolvedRoot.renameTo(targetDir)) {
                copyRecursive(resolvedRoot, targetDir);
            }
            listener.onSuccess();
        } catch (Exception e) {
            listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (tempZip.exists()) tempZip.delete();
            deleteRecursively(staging);
        }
    }

    private static void downloadFile(String urlStr, File dest, Listener listener) throws IOException {
        HttpURLConnection conn = openConnection(urlStr, 0);
        try {
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                String html = readSmallText(conn.getInputStream());
                conn.disconnect();
                String confirmUrl = extractGoogleDriveConfirmUrl(html);
                if (confirmUrl == null) {
                    throw new IOException("Сервер вернул HTML вместо архива (проверьте доступ к файлу на Google Диске).");
                }
                conn = openConnection(confirmUrl, 0);
            }
            long total = conn.getContentLengthLong();
            byte[] buf = new byte[65536];
            long downloaded = 0;
            long lastReport = 0;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 250) {
                        lastReport = now;
                        if (total > 0) {
                            int pct = (int) Math.min(99, (downloaded * 100L) / total);
                            listener.onProgress(String.format(Locale.US, "Скачивание: %.1f / %.1f МБ (%d%%)",
                                    downloaded / 1048576.0, total / 1048576.0, pct), pct);
                        } else {
                            listener.onProgress(String.format(Locale.US, "Скачивание: %.1f МБ",
                                    downloaded / 1048576.0), 0);
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String urlStr, int redirects) throws IOException {
        if (redirects > 8) throw new IOException("Слишком много перенаправлений.");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) F12012Port/1.0");
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
            String next = conn.getHeaderField("Location");
            conn.disconnect();
            if (next == null || next.isEmpty()) throw new IOException("Пустой редирект (" + code + ").");
            return openConnection(new URL(new URL(urlStr), next).toString(), redirects + 1);
        }
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("Ошибка HTTP " + code + " при скачивании.");
        }
        return conn;
    }

    private static String readSmallText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1 && out.size() < 262144) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String extractGoogleDriveConfirmUrl(String html) {
        Matcher form = Pattern.compile("action=\"(https://drive\\.usercontent\\.google\\.com/download[^\"]*)\"").matcher(html);
        if (form.find()) {
            String base = form.group(1).replace("&amp;", "&");
            StringBuilder query = new StringBuilder(base);
            Matcher inputs = Pattern.compile("<input[^>]+name=\"([^\"]+)\"[^>]+value=\"([^\"]*)\"").matcher(html);
            while (inputs.find()) {
                query.append(query.indexOf("?") >= 0 ? "&" : "?")
                     .append(inputs.group(1)).append("=").append(inputs.group(2));
            }
            return query.toString();
        }
        return null;
    }

    private static void unzip(File zipFile, File destDir, Listener listener) throws IOException {
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[65536];
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                String canonicalOut = outFile.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalDest)) {
                    throw new IOException("Небезопасный путь в архиве: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File p = outFile.getParentFile();
                    if (p != null) p.mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                    }
                    count++;
                    if (count % 25 == 0) {
                        listener.onProgress("Распаковано файлов: " + count + "...", 100);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static File findGameRoot(File dir) {
        if (new File(dir, "default.xex").isFile()) return dir;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            if (c.isDirectory() && new File(c, "default.xex").isFile()) {
                return c;
            }
        }
        return null;
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] list = src.listFiles();
            if (list != null) {
                for (File f : list) copyRecursive(f, new File(dst, f.getName()));
            }
        } else {
            File p = dst.getParentFile();
            if (p != null) p.mkdirs();
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] list = f.listFiles();
        if (list != null) {
            for (File c : list) deleteRecursively(c);
        }
        f.delete();
    }
}
