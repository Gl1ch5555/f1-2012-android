package com.f12012.android;

import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * High-speed, resumable, multi-stream & Google Drive downloader +
 * zero-copy local ZIP / 7Z / Xbox 360 ISO extractor for F1 2012.
 */
final class GameDownloader {
    static {
        System.loadLibrary("xiso_extract");
    }

    private static native int nativeExtractXiso(String isoPath, String outDir);

    static final String DEFAULT_GAME_URL = "https://drive.google.com/file/d/1xIxqplBjfAcKRmJvws2ZtoN7ecSzacfq/view?usp=drivesdk";

    private static final int DOWNLOAD_THREADS = 4;
    private static final long CHUNK_SIZE = 16L * 1024L * 1024L; // 16 MB
    private static final int IO_BUFFER_SIZE = 262144; // 256 KB
    private static final long EXTRACTED_RESERVE_BYTES = 6_500_000_000L;

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
        File dlTemp = new File(parent, ".f1_2012_download.tmp");
        File dlState = new File(parent, ".f1_2012_download.state");
        if (!dlState.exists()) {
            dlTemp.delete();
        }
        new File(parent, ".f1_2012_local.tmp").delete();
        deleteRecursively(new File(parent, ".f1_2012_staging"));
    }

    static void clearDownloadFiles(File targetDir) {
        if (targetDir == null) return;
        File parent = targetDir.getParentFile();
        if (parent == null) return;
        new File(parent, ".f1_2012_download.tmp").delete();
        new File(parent, ".f1_2012_download.state").delete();
        deleteRecursively(new File(parent, ".f1_2012_staging"));
    }

    static boolean hasResumableDownload(File targetDir) {
        if (targetDir == null) return false;
        File parent = targetDir.getParentFile();
        if (parent == null) return false;
        return new File(parent, ".f1_2012_download.tmp").isFile()
                && new File(parent, ".f1_2012_download.state").isFile();
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

    static boolean isNetworkAvailable(Context ctx) {
        if (ctx == null) return true;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        if (Build.VERSION.SDK_INT >= 23) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    private static void waitForNetwork(Context ctx, AtomicBoolean cancelFlag, String statusPrefix,
                                       int pct, Listener listener) throws IOException {
        if (cancelFlag.get()) throw new IOException("Загрузка отменена.");
        listener.onProgress(statusPrefix, pct);
        ConnectivityManager cm = ctx != null
                ? (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                : null;
        final CountDownLatch latch = new CountDownLatch(1);
        ConnectivityManager.NetworkCallback cb = null;
        if (cm != null && Build.VERSION.SDK_INT >= 24) {
            cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    latch.countDown();
                }
            };
            try {
                cm.registerDefaultNetworkCallback(cb);
            } catch (Exception ignored) {
                cb = null;
            }
        }
        try {
            while (!cancelFlag.get() && !isNetworkAvailable(ctx)) {
                try {
                    latch.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Загрузка прервана.");
                }
            }
            if (cancelFlag.get()) throw new IOException("Загрузка отменена.");
        } finally {
            if (cm != null && cb != null) {
                try {
                    cm.unregisterNetworkCallback(cb);
                } catch (Exception ignored) {}
            }
        }
    }

    static void downloadAndInstall(Context ctx, String rawUrl, File targetDir,
                                   AtomicBoolean cancelFlag, Listener listener) {
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
        File stateFile = new File(parent, ".f1_2012_download.state");
        File staging = new File(parent, ".f1_2012_staging");
        try {
            cleanupTempFiles(targetDir);
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(new CookieManager());
            }

            if (!isNetworkAvailable(ctx)) {
                if (hasResumableDownload(targetDir)) {
                    waitForNetwork(ctx, cancelFlag, "Нет интернета — ожидание сети для продолжения...", 0, listener);
                } else {
                    throw new IOException("Нет подключения к интернету. Проверьте Wi-Fi или мобильную сеть.");
                }
            }

            listener.onProgress("Подключение к серверу...", 0);
            downloadFileResilient(ctx, downloadUrl, tempFile, stateFile, parent, cancelFlag, listener);

            if (cancelFlag.get()) {
                clearDownloadFiles(targetDir);
                throw new IOException("Загрузка отменена.");
            }

            stateFile.delete();
            extractFileAndInstall(tempFile, staging, targetDir, listener);
            listener.onSuccess();
        } catch (Exception e) {
            if (cancelFlag.get()) {
                clearDownloadFiles(targetDir);
            }
            listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
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

    private static final class ProbeResult {
        final String resolvedUrl;
        final long totalLength;
        final boolean supportsRange;

        ProbeResult(String resolvedUrl, long totalLength, boolean supportsRange) {
            this.resolvedUrl = resolvedUrl;
            this.totalLength = totalLength;
            this.supportsRange = supportsRange;
        }
    }

    private static ProbeResult probeTarget(String urlStr) throws IOException {
        HttpURLConnection conn = openConnection(urlStr, 0, "bytes=0-0");
        try {
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                String html = readSmallText(conn.getInputStream());
                conn.disconnect();
                String confirmUrl = extractGoogleDriveConfirmUrl(html);
                if (confirmUrl != null) {
                    conn = openConnection(confirmUrl, 0, "bytes=0-0");
                    String ct2 = conn.getContentType();
                    if (ct2 != null && ct2.toLowerCase(Locale.US).contains("text/html")) {
                        String html2 = readSmallText(conn.getInputStream());
                        conn.disconnect();
                        if (html2.contains("Too many users have viewed or downloaded") || html2.contains("Quota exceeded")) {
                            throw new IOException("Превышен лимит скачиваний Google Диска (квота 24ч).");
                        }
                        throw new IOException("Google Диск не отдал файл (возможно, включён лимит или закрыт доступ).");
                    }
                    urlStr = conn.getURL().toString();
                } else {
                    if (html.contains("Too many users have viewed or downloaded") || html.contains("Quota exceeded")) {
                        throw new IOException("Превышен лимит скачиваний Google Диска (квота 24ч). Используйте другую ссылку или выберите архив/ISO с телефона.");
                    }
                    throw new IOException("Google Диск вернул страницу вместо файла (проверьте доступ 'Все, у кого есть ссылка').");
                }
            } else {
                urlStr = conn.getURL().toString();
            }

            int code = conn.getResponseCode();
            if (code == 206) {
                long total = parseTotalFromContentRange(conn.getHeaderField("Content-Range"));
                if (total <= 0) total = conn.getContentLengthLong();
                return new ProbeResult(urlStr, total, total > 0);
            }
            long total = conn.getContentLengthLong();
            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            boolean ranges = "bytes".equalsIgnoreCase(acceptRanges) && total > 0;
            return new ProbeResult(urlStr, total, ranges);
        } finally {
            conn.disconnect();
        }
    }

    private static long parseTotalFromContentRange(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= header.length()) return -1;
        String tail = header.substring(slash + 1).trim();
        if ("*".equals(tail)) return -1;
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void downloadFileResilient(Context ctx, String urlStr, File dest, File stateFile,
                                              File parentDir, AtomicBoolean cancelFlag,
                                              Listener listener) throws IOException {
        ProbeResult probe = probeTarget(urlStr);
        if (cancelFlag.get()) throw new IOException("Загрузка отменена.");

        if (probe.supportsRange && probe.totalLength > CHUNK_SIZE) {
            downloadSegmented(ctx, urlStr, probe, dest, stateFile, parentDir, cancelFlag, listener);
        } else {
            checkDiskSpace(parentDir, probe.totalLength, 0);
            downloadSingleStream(ctx, probe.resolvedUrl, dest, probe.totalLength, cancelFlag, listener);
        }
    }

    private static void checkDiskSpace(File dir, long totalBytes, long alreadyHaveBytes) throws IOException {
        if (totalBytes <= 0) return;
        long needed = Math.max(0, totalBytes - alreadyHaveBytes) + EXTRACTED_RESERVE_BYTES;
        long usable = dir.getUsableSpace();
        if (usable > 0 && usable < needed) {
            throw new IOException(String.format(Locale.US,
                    "Недостаточно места на телефоне: нужно %.1f ГБ (для образа и распаковки), свободно %.1f ГБ.",
                    needed / 1073741824.0, usable / 1073741824.0));
        }
    }

    private static void downloadSegmented(Context ctx, String originalUrl, ProbeResult probe,
                                          File dest, File stateFile, File parentDir,
                                          AtomicBoolean cancelFlag, Listener listener) throws IOException {
        final long total = probe.totalLength;
        final int numChunks = (int) ((total + CHUNK_SIZE - 1) / CHUNK_SIZE);
        final byte[] done = new byte[numChunks];
        boolean resumed = false;

        if (stateFile.isFile() && dest.isFile() && dest.length() == total) {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(stateFile))) {
                String savedUrl = dis.readUTF();
                long savedTotal = dis.readLong();
                long savedChunk = dis.readLong();
                int savedCount = dis.readInt();
                if (originalUrl.equals(savedUrl) && savedTotal == total
                        && savedChunk == CHUNK_SIZE && savedCount == numChunks) {
                    dis.readFully(done);
                    resumed = true;
                }
            } catch (IOException ignored) {
                resumed = false;
            }
        }

        long completedBytesInit = 0;
        if (resumed) {
            for (int i = 0; i < numChunks; i++) {
                if (done[i] == 1) {
                    completedBytesInit += chunkLength(i, total);
                }
            }
        } else {
            checkDiskSpace(parentDir, total, 0);
            try (RandomAccessFile raf = new RandomAccessFile(dest, "rw")) {
                raf.setLength(total);
            }
            saveState(stateFile, originalUrl, total, done);
        }

        if (resumed) {
            checkDiskSpace(parentDir, total, completedBytesInit);
        }

        final AtomicLong downloadedBytes = new AtomicLong(completedBytesInit);
        final AtomicInteger nextChunk = new AtomicInteger(0);
        final AtomicReference<IOException> fatalError = new AtomicReference<>();
        final Object stateLock = new Object();
        final String activeUrl = probe.resolvedUrl;

        final AtomicBoolean reporting = new AtomicBoolean(true);
        Thread reporter = new Thread(() -> {
            long lastTime = System.currentTimeMillis();
            long lastBytes = downloadedBytes.get();
            double smoothSpeed = 0;
            while (reporting.get() && !cancelFlag.get()) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    break;
                }
                long now = System.currentTimeMillis();
                long cur = downloadedBytes.get();
                long dt = Math.max(1, now - lastTime);
                double instantMbps = ((cur - lastBytes) * 1000.0 / dt) / 1048576.0;
                smoothSpeed = smoothSpeed == 0 ? instantMbps : (smoothSpeed * 0.6 + instantMbps * 0.4);
                lastTime = now;
                lastBytes = cur;

                int pct = (int) Math.min(99, (cur * 100L) / total);
                if (!isNetworkAvailable(ctx)) {
                    listener.onProgress(String.format(Locale.US,
                            "Нет интернета — ожидание сети... %.2f / %.2f ГБ (%d%%)",
                            cur / 1073741824.0, total / 1073741824.0, pct), pct);
                } else {
                    listener.onProgress(String.format(Locale.US,
                            "Скачивание: %.2f / %.2f ГБ (%d%%) • %.1f МБ/с",
                            cur / 1073741824.0, total / 1073741824.0, pct, Math.max(0, smoothSpeed)), pct);
                }
            }
        }, "dl-reporter");
        reporter.start();

        int workerCount = Math.min(DOWNLOAD_THREADS, numChunks);
        List<Thread> workers = new ArrayList<>(workerCount);
        try (RandomAccessFile raf = new RandomAccessFile(dest, "rw");
             FileChannel channel = raf.getChannel()) {
            for (int w = 0; w < workerCount; w++) {
                Thread t = new Thread(() -> {
                    byte[] buf = new byte[IO_BUFFER_SIZE];
                    while (!cancelFlag.get() && fatalError.get() == null) {
                        int idx = nextChunk.getAndIncrement();
                        if (idx >= numChunks) break;
                        synchronized (stateLock) {
                            if (done[idx] == 1) continue;
                        }
                        long start = idx * CHUNK_SIZE;
                        long len = chunkLength(idx, total);
                        long end = start + len - 1;

                        int attempt = 0;
                        boolean chunkOk = false;
                        while (!cancelFlag.get() && fatalError.get() == null && !chunkOk) {
                            long chunkWritten = 0;
                            try {
                                if (!isNetworkAvailable(ctx)) {
                                    int pct = (int) Math.min(99, (downloadedBytes.get() * 100L) / total);
                                    waitForNetwork(ctx, cancelFlag,
                                            String.format(Locale.US, "Нет интернета — ожидание сети... (%d%%)", pct),
                                            pct, listener);
                                }
                                String rangeHdr = "bytes=" + start + "-" + end;
                                HttpURLConnection conn = openConnection(activeUrl, 0, rangeHdr);
                                try {
                                    String ct = conn.getContentType();
                                    if (ct != null && ct.toLowerCase(Locale.US).contains("text/html")) {
                                        String html = readSmallText(conn.getInputStream());
                                        if (html.contains("Too many users") || html.contains("Quota exceeded")) {
                                            throw new FatalDownloadException("Превышен лимит скачиваний Google Диска (квота 24ч).");
                                        }
                                        throw new IOException("Сервер вернул HTML вместо фрагмента файла.");
                                    }
                                    if (conn.getResponseCode() != 206) {
                                        throw new IOException("Сервер не вернул 206 Partial Content (" + conn.getResponseCode() + ").");
                                    }
                                    try (InputStream in = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE)) {
                                        int n;
                                        while ((n = in.read(buf)) != -1) {
                                            if (cancelFlag.get()) {
                                                throw new FatalDownloadException("Загрузка отменена.");
                                            }
                                            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                                            long writePos = start + chunkWritten;
                                            while (bb.hasRemaining()) {
                                                writePos += channel.write(bb, writePos);
                                            }
                                            chunkWritten += n;
                                            downloadedBytes.addAndGet(n);
                                        }
                                    }
                                    if (chunkWritten != len) {
                                        throw new IOException("Неполный фрагмент: " + chunkWritten + " из " + len);
                                    }
                                    synchronized (stateLock) {
                                        done[idx] = 1;
                                        saveState(stateFile, originalUrl, total, done);
                                    }
                                    chunkOk = true;
                                } finally {
                                    conn.disconnect();
                                }
                            } catch (FatalDownloadException fde) {
                                if (chunkWritten > 0) downloadedBytes.addAndGet(-chunkWritten);
                                fatalError.compareAndSet(null, fde);
                                break;
                            } catch (IOException ioe) {
                                if (chunkWritten > 0) downloadedBytes.addAndGet(-chunkWritten);
                                attempt++;
                                if (!isNetworkAvailable(ctx)) {
                                    attempt = Math.max(0, attempt - 1);
                                    continue;
                                }
                                if (attempt >= 12) {
                                    fatalError.compareAndSet(null, new IOException(
                                            "Сбой сети при скачивании: " + ioe.getMessage(), ioe));
                                    break;
                                }
                                try {
                                    Thread.sleep(Math.min(15000L, 1000L << Math.min(4, attempt - 1)));
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    }
                }, "dl-worker-" + w);
                workers.add(t);
                t.start();
            }

            for (Thread t : workers) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Загрузка прервана.");
                }
            }
        } finally {
            reporting.set(false);
            reporter.interrupt();
        }

        if (cancelFlag.get()) throw new IOException("Загрузка отменена.");
        if (fatalError.get() != null) throw fatalError.get();
    }

    private static long chunkLength(int idx, long total) {
        long start = idx * CHUNK_SIZE;
        return Math.min(CHUNK_SIZE, total - start);
    }

    private static void saveState(File stateFile, String url, long total, byte[] done) throws IOException {
        File tmp = new File(stateFile.getParentFile(), stateFile.getName() + ".tmp");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            dos.writeUTF(url);
            dos.writeLong(total);
            dos.writeLong(CHUNK_SIZE);
            dos.writeInt(done.length);
            dos.write(done);
        }
        if (!tmp.renameTo(stateFile)) {
            try (FileOutputStream fos = new FileOutputStream(stateFile);
                 DataOutputStream dos = new DataOutputStream(fos)) {
                dos.writeUTF(url);
                dos.writeLong(total);
                dos.writeLong(CHUNK_SIZE);
                dos.writeInt(done.length);
                dos.write(done);
            }
            tmp.delete();
        }
    }

    private static void downloadSingleStream(Context ctx, String urlStr, File dest, long total,
                                             AtomicBoolean cancelFlag, Listener listener) throws IOException {
        HttpURLConnection conn = openConnection(urlStr, 0, null);
        try {
            long len = conn.getContentLengthLong();
            if (len > 0) total = len;
            byte[] buf = new byte[IO_BUFFER_SIZE];
            long downloaded = 0;
            long lastReport = System.currentTimeMillis();
            long lastBytes = 0;
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE);
                 FileOutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelFlag.get()) throw new IOException("Загрузка отменена.");
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 400) {
                        long dt = Math.max(1, now - lastReport);
                        double mbps = ((downloaded - lastBytes) * 1000.0 / dt) / 1048576.0;
                        lastReport = now;
                        lastBytes = downloaded;
                        if (total > 0) {
                            int pct = (int) Math.min(99, (downloaded * 100L) / total);
                            listener.onProgress(String.format(Locale.US,
                                    "Скачивание: %.2f / %.2f ГБ (%d%%) • %.1f МБ/с",
                                    downloaded / 1073741824.0, total / 1073741824.0, pct, mbps), pct);
                        } else {
                            listener.onProgress(String.format(Locale.US,
                                    "Скачивание: %.1f МБ • %.1f МБ/с",
                                    downloaded / 1048576.0, mbps), 0);
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static final class FatalDownloadException extends IOException {
        FatalDownloadException(String msg) { super(msg); }
    }

    private static HttpURLConnection openConnection(String urlStr, int redirects, String rangeHeader) throws IOException {
        if (redirects > 8) throw new IOException("Слишком много перенаправлений.");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) F12012Port/1.0");
        if (rangeHeader != null) {
            conn.setRequestProperty("Range", rangeHeader);
        }
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
            String next = conn.getHeaderField("Location");
            conn.disconnect();
            if (next == null || next.isEmpty()) throw new IOException("Пустой редирект (" + code + ").");
            return openConnection(new URL(new URL(urlStr), next).toString(), redirects + 1, rangeHeader);
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
