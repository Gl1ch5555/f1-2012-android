package com.f12012.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * Direct & Google Drive downloader + local ZIP / 7Z / Xbox 360 ISO extractor for F1 2012.
 */
final class GameDownloader {
    static {
        System.loadLibrary("xiso_extract");
    }

    private static native int nativeExtractXiso(String isoPath, String outDir);

    // Put a direct .zip / .7z / .iso or Google Drive URL here when ready
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
        File tempFile = new File(parent, ".f1_2012_download.tmp");
        File staging = new File(parent, ".f1_2012_staging");
        try {
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(new CookieManager());
            }
            listener.onProgress("Подключение к серверу...", 0);
            downloadFile(downloadUrl, tempFile, listener);
            extractAnyAndInstall(tempFile, staging, targetDir, listener);
            listener.onSuccess();
        } catch (Exception e) {
            listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (tempFile.exists()) tempFile.delete();
            deleteRecursively(staging);
        }
    }

    static void installFromUri(Context ctx, Uri uri, File targetDir, Listener listener) {
        File parent = targetDir.getParentFile();
        if (parent == null) {
            listener.onError("Некорректный путь к папке игры.");
            return;
        }
        File tempFile = new File(parent, ".f1_2012_local.tmp");
        File staging = new File(parent, ".f1_2012_staging");
        try {
            String name = queryDisplayName(ctx, uri);
            listener.onProgress("Чтение " + name + "...", 0);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("Не удалось открыть выбранный файл.");
                }
                copyStreamWithProgress(in, tempFile, -1, "Копирование файла: ", listener);
            }
            extractAnyAndInstall(tempFile, staging, targetDir, listener);
            listener.onSuccess();
        } catch (Exception e) {
            listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (tempFile.exists()) tempFile.delete();
            deleteRecursively(staging);
        }
    }

    private static void extractAnyAndInstall(File sourceFile, File staging, File targetDir, Listener listener) throws IOException {
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            throw new IOException("Не удалось создать временную папку распаковки.");
        }

        if (isZip(sourceFile)) {
            listener.onProgress("Распаковка ZIP-архива...", 100);
            unzip(sourceFile, staging, listener);
        } else if (isSevenZip(sourceFile)) {
            listener.onProgress("Распаковка 7z-архива...", 100);
            un7zip(sourceFile, staging, listener);
        } else {
            listener.onProgress("Распаковка Xbox 360 ISO...", 50);
            int rc = nativeExtractXiso(sourceFile.getAbsolutePath(), staging.getAbsolutePath());
            if (rc != 0) {
                throw new IOException("Не удалось распаковать файл (неизвестный формат или повреждённый ISO/архив, код " + rc + ").");
            }
        }

        // Remove temp archive before nested ISO extraction to save storage space
        if (sourceFile.exists()) {
            sourceFile.delete();
        }

        File resolvedRoot = findGameRoot(staging);
        if (resolvedRoot == null) {
            File isoFile = findLargestByExtension(staging, ".iso");
            if (isoFile != null) {
                listener.onProgress("Найден " + isoFile.getName() + ", извлекаю Xbox 360 ISO...", 50);
                File isoOut = new File(staging, "_xiso_out");
                if (!isoOut.mkdirs() && !isoOut.isDirectory()) {
                    throw new IOException("Не удалось создать папку для распаковки ISO.");
                }
                int rc = nativeExtractXiso(isoFile.getAbsolutePath(), isoOut.getAbsolutePath());
                isoFile.delete();
                if (rc != 0) {
                    throw new IOException("Ошибка распаковки ISO (код " + rc + ").");
                }
                resolvedRoot = findGameRoot(isoOut);
            }
        }

        if (resolvedRoot == null) {
            throw new IOException("Внутри архива/ISO не найден default.xex.");
        }
        deleteRecursively(targetDir);
        if (!resolvedRoot.renameTo(targetDir)) {
            copyRecursive(resolvedRoot, targetDir);
        }
    }

    private static void downloadFile(String urlStr, File dest, Listener listener) throws IOException {
        HttpURLConnection conn = openConnection(urlStr, 0);
        try {
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                String html = readSmallText(conn.getInputStream());
                conn.disconnect();
                if (html.contains("Too many users have viewed or downloaded") || html.contains("quota")) {
                    throw new IOException("Превышен лимит скачиваний Google Диска (квота 24ч). Используйте другую ссылку или выберите архив/ISO с телефона.");
                }
                String confirmUrl = extractGoogleDriveConfirmUrl(html);
                if (confirmUrl == null) {
                    throw new IOException("Google Диск вернул страницу вместо файла (проверьте доступ 'Все, у кого есть ссылка').");
                }
                conn = openConnection(confirmUrl, 0);
                String ct2 = conn.getContentType();
                if (ct2 != null && ct2.toLowerCase(Locale.US).contains("text/html")) {
                    String html2 = readSmallText(conn.getInputStream());
                    conn.disconnect();
                    if (html2.contains("Too many users have viewed or downloaded") || html2.contains("quota")) {
                        throw new IOException("Превышен лимит скачиваний Google Диска (квота 24ч).");
                    }
                    throw new IOException("Google Диск не отдал файл (возможно, включён лимит или закрыт доступ).");
                }
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                copyStreamWithProgress(in, dest, total, "Скачивание: ", listener);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copyStreamWithProgress(InputStream in, File dest, long total, String prefix, Listener listener) throws IOException {
        byte[] buf = new byte[65536];
        long downloaded = 0;
        long lastReport = 0;
        try (FileOutputStream out = new FileOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (now - lastReport >= 250) {
                    lastReport = now;
                    if (total > 0) {
                        int pct = (int) Math.min(99, (downloaded * 100L) / total);
                        listener.onProgress(String.format(Locale.US, "%s%.1f / %.1f МБ (%d%%)",
                                prefix, downloaded / 1048576.0, total / 1048576.0, pct), pct);
                    } else {
                        listener.onProgress(String.format(Locale.US, "%s%.1f МБ",
                                prefix, downloaded / 1048576.0), 0);
                    }
                }
            }
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
        Matcher uc = Pattern.compile("href=\"(/uc\\?export=download[^\"]+)\"").matcher(html);
        if (uc.find()) {
            return "https://drive.google.com" + uc.group(1).replace("&amp;", "&");
        }
        return null;
    }

    private static boolean isZip(File f) {
        byte[] sig = new byte[4];
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read(sig) == 4 && sig[0] == 0x50 && sig[1] == 0x4B;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSevenZip(File f) {
        byte[] sig = new byte[4];
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read(sig) == 4 && sig[0] == 0x37 && sig[1] == 0x7A && sig[2] == (byte) 0xBC && sig[3] == (byte) 0xAF;
        } catch (IOException e) {
            return false;
        }
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

    private static void un7zip(File archiveFile, File destDir, Listener listener) throws IOException {
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[65536];
        int count = 0;
        try (SevenZFile sevenZFile = SevenZFile.builder().setFile(archiveFile).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
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
                        while ((n = sevenZFile.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                    }
                    count++;
                    if (count % 10 == 0) {
                        listener.onProgress("Распаковано из .7z: " + count + "...", 100);
                    }
                }
            }
        }
    }

    private static File findGameRoot(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().equalsIgnoreCase("default.xex")) {
                return dir;
            }
        }
        for (File c : list) {
            if (c.isDirectory()) {
                File nested = findGameRoot(c);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static File findLargestByExtension(File dir, String ext) {
        File best = null;
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(ext)) {
                if (best == null || f.length() > best.length()) {
                    best = f;
                }
            } else if (f.isDirectory()) {
                File nested = findLargestByExtension(f, ext);
                if (nested != null && (best == null || nested.length() > best.length())) {
                    best = nested;
                }
            }
        }
        return best;
    }

    private static String queryDisplayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {}
        return "архива";
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
