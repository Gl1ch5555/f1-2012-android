package com.f12012.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Direct & Google Drive downloader + zero-copy local ZIP / 7Z / Xbox 360 ISO extractor for F1 2012.
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

    static void cleanupTempFiles(File targetDir) {
        if (targetDir == null) return;
        File parent = targetDir.getParentFile();
        if (parent == null) return;
        new File(parent, ".f1_2012_download.tmp").delete();
        new File(parent, ".f1_2012_local.tmp").delete();
        deleteRecursively(new File(parent, ".f1_2012_staging"));
    }

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
            cleanupTempFiles(targetDir);
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(new CookieManager());
            }
            listener.onProgress("Подключение к серверу...", 0);
            downloadFile(downloadUrl, tempFile, listener);
            extractFileAndInstall(tempFile, staging, targetDir, listener);
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
        File staging = new File(parent, ".f1_2012_staging");
        try {
            cleanupTempFiles(targetDir);
            String name = queryDisplayName(ctx, uri);
            listener.onProgress("Открытие " + name + "...", 0);

            try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                    throw new IOException("Не удалось открыть выбранный файл.");
                }
                byte[] sig = readSignature(pfd);
                if (isZipSig(sig)) {
                    if (!staging.mkdirs() && !staging.isDirectory()) {
                        throw new IOException("Не удалось создать временную папку.");
                    }
                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                        unzipStream(fis, staging, pfd.getStatSize(), listener);
                    }
                    finalizeFromStaging(staging, targetDir, listener);
                } else if (isSevenZipSig(sig)) {
                    if (!staging.mkdirs() && !staging.isDirectory()) {
                        throw new IOException("Не удалось создать временную папку.");
                    }
                    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                         FileChannel channel = fis.getChannel();
                         SevenZFile sevenZ = SevenZFile.builder().setSeekableByteChannel(channel).get()) {
                        un7zip(sevenZ, staging, listener);
                    }
                    finalizeFromStaging(staging, targetDir, listener);
                } else {
                    // Direct Xbox 360 ISO via /proc/self/fd/<fd> (zero copy!)
                    deleteRecursively(targetDir);
                    if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
                        throw new IOException("Не удалось создать папку игры.");
                    }
                    String fdPath = "/proc/self/fd/" + pfd.getFd();
                    runXisoWithProgress(fdPath, targetDir, listener);
                    if (findGameRoot(targetDir) == null) {
                        throw new IOException("Не удалось распаковать файл (неизвестный формат или внутри нет default.xex).");
                    }
                }
            }
            listener.onSuccess();
        } catch (Exception e) {
            listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            deleteRecursively(staging);
        }
    }

    private static void extractFileAndInstall(File sourceFile, File staging, File targetDir, Listener listener) throws IOException {
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Не удалось создать временную папку распаковки.");
        }

        if (isZip(sourceFile)) {
            try (FileInputStream fis = new FileInputStream(sourceFile)) {
                unzipStream(fis, staging, sourceFile.length(), listener);
            }
            sourceFile.delete();
            finalizeFromStaging(staging, targetDir, listener);
        } else if (isSevenZip(sourceFile)) {
            try (SevenZFile sevenZ = SevenZFile.builder().setFile(sourceFile).get()) {
                un7zip(sevenZ, staging, listener);
            }
            sourceFile.delete();
            finalizeFromStaging(staging, targetDir, listener);
        } else {
            deleteRecursively(targetDir);
            if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
                throw new IOException("Не удалось создать папку игры.");
            }
            runXisoWithProgress(sourceFile.getAbsolutePath(), targetDir, listener);
            sourceFile.delete();
            if (findGameRoot(targetDir) == null) {
                throw new IOException("Внутри ISO не найден default.xex.");
            }
        }
    }

    private static void finalizeFromStaging(File staging, File targetDir, Listener listener) throws IOException {
        File resolvedRoot = findGameRoot(staging);
        if (resolvedRoot != null) {
            deleteRecursively(targetDir);
            if (!resolvedRoot.renameTo(targetDir)) {
                copyRecursive(resolvedRoot, targetDir);
            }
            return;
        }

        File isoFile = findLargestByExtension(staging, ".iso");
        if (isoFile != null) {
            deleteRecursively(targetDir);
            if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
                throw new IOException("Не удалось создать папку игры.");
            }
            runXisoWithProgress(isoFile.getAbsolutePath(), targetDir, listener);
            isoFile.delete();
            File rootInTarget = findGameRoot(targetDir);
            if (rootInTarget == null) {
                throw new IOException("Внутри ISO не найден default.xex.");
            }
            if (!rootInTarget.equals(targetDir)) {
                File tmpMove = new File(targetDir.getParentFile(), ".f1_2012_move");
                deleteRecursively(tmpMove);
                if (!rootInTarget.renameTo(tmpMove)) {
                    copyRecursive(rootInTarget, tmpMove);
                }
                deleteRecursively(targetDir);
                if (!tmpMove.renameTo(targetDir)) {
                    copyRecursive(tmpMove, targetDir);
                    deleteRecursively(tmpMove);
                }
            }
            return;
        }

        throw new IOException("Внутри архива не найден default.xex или .iso образ.");
    }

    private static void runXisoWithProgress(String isoPath, File outDir, Listener listener) throws IOException {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread monitor = new Thread(() -> {
            while (running.get()) {
                long bytes = dirSize(outDir);
                int pct = (int) Math.min(98, (bytes * 100L) / (6300L * 1024L * 1024L));
                listener.onProgress(String.format(Locale.US, "Распаковка ISO: %.1f МБ (%d%%)",
                        bytes / 1048576.0, pct), pct);
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "xiso-progress");
        monitor.start();
        int rc;
        try {
            rc = nativeExtractXiso(isoPath, outDir.getAbsolutePath());
        } finally {
            running.set(false);
            monitor.interrupt();
        }
        if (rc != 0) {
            throw new IOException("Ошибка распаковки Xbox 360 ISO (код " + rc + ").");
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

    private static byte[] readSignature(ParcelFileDescriptor pfd) throws IOException {
        byte[] sig = new byte[4];
        try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            FileChannel ch = in.getChannel();
            long pos = ch.position();
            ch.position(0);
            int n = in.read(sig);
            ch.position(pos);
            if (n < 4) return new byte[4];
        }
        return sig;
    }

    private static boolean isZipSig(byte[] sig) {
        return sig.length >= 2 && sig[0] == 0x50 && sig[1] == 0x4B;
    }

    private static boolean isSevenZipSig(byte[] sig) {
        return sig.length >= 4 && sig[0] == 0x37 && sig[1] == 0x7A && sig[2] == (byte) 0xBC && sig[3] == (byte) 0xAF;
    }

    private static boolean isZip(File f) {
        byte[] sig = new byte[4];
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read(sig) == 4 && isZipSig(sig);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSevenZip(File f) {
        byte[] sig = new byte[4];
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read(sig) == 4 && isSevenZipSig(sig);
        } catch (IOException e) {
            return false;
        }
    }

    private static void unzipStream(InputStream rawIn, File destDir, long archiveBytes, Listener listener) throws IOException {
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[65536];
        long written = 0;
        long lastReport = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(rawIn, 65536))) {
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
                            written += n;
                            long now = System.currentTimeMillis();
                            if (now - lastReport >= 250) {
                                lastReport = now;
                                int pct = archiveBytes > 0 ? (int) Math.min(99, (written * 100L) / archiveBytes) : 50;
                                listener.onProgress(String.format(Locale.US, "Распаковка ZIP: %.1f МБ (%d%%)",
                                        written / 1048576.0, pct), pct);
                            }
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void un7zip(SevenZFile sevenZFile, File destDir, Listener listener) throws IOException {
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[65536];
        long totalUnpacked = 0;
        for (SevenZArchiveEntry e : sevenZFile.getEntries()) {
            if (!e.isDirectory() && e.getSize() > 0) totalUnpacked += e.getSize();
        }
        long done = 0;
        long lastReport = 0;
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
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport >= 250) {
                            lastReport = now;
                            if (totalUnpacked > 0) {
                                int pct = (int) Math.min(99, (done * 100L) / totalUnpacked);
                                listener.onProgress(String.format(Locale.US, "Распаковка .7z: %.1f / %.1f МБ (%d%%)",
                                        done / 1048576.0, totalUnpacked / 1048576.0, pct), pct);
                            } else {
                                listener.onProgress(String.format(Locale.US, "Распаковка .7z: %.1f МБ",
                                        done / 1048576.0), 50);
                            }
                        }
                    }
                }
            }
        }
    }

    private static long dirSize(File dir) {
        long sum = 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        for (File f : list) {
            sum += f.isDirectory() ? dirSize(f) : f.length();
        }
        return sum;
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
