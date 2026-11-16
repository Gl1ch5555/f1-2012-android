// SPDX-License-Identifier: GPL-3.0-only
package com.f12012.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** SAF documents, private backups and background transactions. No storage permission or automatic upload. */
final class LauncherSaves {
    private static final int EXPORT = 81, IMPORT = 82;
    private final Activity activity;
    private final Executor worker;
    private final Consumer<Boolean> setBusy;
    private final File root, backups;
    private boolean busy, failedRecovery;
    private final Handler main = new Handler(Looper.getMainLooper());
    LauncherSaves(Activity activity, Executor worker, Consumer<Boolean> setBusy) {
        this.activity = activity; this.worker = worker; this.setBusy = setBusy;
        root = new File(activity.getFilesDir(), "nfsmw/user");
        backups = new File(activity.getFilesDir(), "save-backups");
    }
    boolean blocked() { return busy || failedRecovery; }
    private void message(String text) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity).setTitle("Сохранения").setMessage(text).setPositiveButton("ОК", null).show();
    }
    void recover() {
        if (GameActivity.gameRunning) return;
        busy = true; setBusy.accept(true);
        worker.execute(() -> {
            String error = null;
            try { SaveArchive.recover(root); }
            catch (IOException failure) { error = "Не удалось восстановить прерванный импорт: " + failure.getMessage(); }
            String report = error;
            main.post(() -> { busy = false; failedRecovery = report != null; setBusy.accept(failedRecovery); if (report != null) message(report); });
        });
    }
    private File latest() {
        File[] files = backups.listFiles(file -> file.isFile() && file.getName().endsWith(".zip"));
        return files == null ? null : Arrays.stream(files).max(Comparator.comparingLong(File::lastModified)).orElse(null);
    }
    void menu() {
        if (blocked()) { message("Дождитесь завершения восстановления сохранений."); return; }
        if (GameActivity.gameRunning) { message("Выйдите из игры перед импортом или экспортом."); return; }
        File last = latest();
        String info = last == null ? "Локальных резервных копий пока нет." : "Последняя копия: " + DateFormat.getDateTimeInstance().format(new Date(last.lastModified()));
        new AlertDialog.Builder(activity).setTitle("Сохранения")
            .setItems(new String[]{"Экспорт ZIP", "Создать локальную копию", "Импорт ZIP", "Восстановить последнюю копию"}, (dialog, which) -> {
                if (which == 0) picker(EXPORT);
                else if (which == 1) run(() -> "Копия создана и проверена: " + SaveArchive.backup(root, backups).getName());
                else if (which == 2) picker(IMPORT);
                else if (last == null) message(info);
                else confirm(() -> run(() -> { SaveArchive.restore(root, new FileInputStream(last), backups); return "Резервная копия восстановлена."; }));
            }).setNeutralButton("Справка", (dialog, which) -> message(info + "\nЛокальные копии удаляются при удалении приложения; экспортируйте ZIP для сохранности."))
            .setNegativeButton("Закрыть", null).show();
    }
    private void picker(int request) {
        Intent intent = new Intent(request == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
        if (request == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, "NFSMW-partidas-" + System.currentTimeMillis() + ".zip");
        try { activity.startActivityForResult(intent, request); }
        catch (Exception error) { message("No se pudo abrir el selector de documentos."); }
    }
    private void confirm(Runnable action) {
        new AlertDialog.Builder(activity).setTitle("Importar partidas")
            .setMessage("Se sustituirán los perfiles, partidas y logros. Primero se verificará el ZIP y se respaldarán los actuales. Los ajustes y shaders se conservan.")
            .setPositiveButton("Importar con respaldo", (dialog, which) -> action.run()).setNegativeButton("Cancelar", null).show();
    }
    boolean result(int request, int result, Intent data) {
        if (request != EXPORT && request != IMPORT) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        if (request == EXPORT) run(() -> {
            OutputStream out = activity.getContentResolver().openOutputStream(uri, "w");
            if (out == null) throw new IOException("No se puede escribir el documento.");
            int count = SaveArchive.export(root, out);
            return "ZIP exportado: " + count + " archivos de partidas. Ajustes, shaders, cachés y drivers quedan fuera.";
        });
        else confirm(() -> run(() -> {
            InputStream in = activity.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("No se puede leer el documento.");
            File backup = SaveArchive.restore(root, in, backups);
            return "Partidas importadas y verificadas. Respaldo previo: " + backup.getName();
        }));
        return true;
    }
    interface Job { String run() throws IOException; }
    private void run(Job job) {
        if (blocked() || GameActivity.gameRunning) { message("Sal del juego y espera a que termine la operación actual."); return; }
        busy = true; setBusy.accept(true);
        worker.execute(() -> {
            String result;
            try { SaveArchive.recover(root); result = job.run(); }
            catch (IOException error) { result = "No se completó la operación: " + error.getMessage(); }
            boolean recoveryFailed = false;
            try { SaveArchive.recover(root); }
            catch (IOException error) { recoveryFailed = true; result += " Conserva los datos de la app: hay una recuperación pendiente."; }
            String report = result;
            boolean blocked = recoveryFailed;
            main.post(() -> { busy = false; failedRecovery = blocked; setBusy.accept(blocked); message(report); });
        });
    }
}
