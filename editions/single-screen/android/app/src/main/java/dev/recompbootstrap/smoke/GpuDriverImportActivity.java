package dev.recompbootstrap.smoke;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Keeps user-selected Vulkan driver packages in app-private storage.
 *
 * Android does not let an ordinary application replace the system Vulkan HAL or force-load an arbitrary
 * libvulkan driver. Importing here is therefore storage/management only; it does not activate the driver.
 * Activation needs an OS-supported driver package or privileged/root integration.
 */
public final class GpuDriverImportActivity extends Activity {
    private static final int PICK_DRIVER = 4107;
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("GPU Driver Packages");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("Import a driver library (.so) or driver archive (.zip). Files are kept privately inside GTA5 ALG. "
                + "Important: Android does not allow a normal app to replace or activate a custom Vulkan system driver. "
                + "Imported files are stored only; the game continues using the phone's active system driver.");
        note.setTextSize(16);
        note.setPadding(0, dp(16), 0, dp(16));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        Button add = new Button(this);
        add.setText("Add Driver File");
        add.setOnClickListener(v -> pickDriver());
        root.addView(add, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        refreshList();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void pickDriver() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_DRIVER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DRIVER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            String name = displayName(uri);
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".so") && !lower.endsWith(".zip")) {
                throw new IllegalArgumentException("Choose a .so or .zip driver file.");
            }
            File folder = new File(getFilesDir(), "gpu-drivers");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Cannot create driver folder.");
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            File target = new File(folder, safe);
            File temp = new File(folder, safe + ".part");
            long total = 0;
            byte[] header = new byte[4];
            int headerCount = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null) throw new IllegalStateException("Cannot read selected file.");
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) throw new IllegalArgumentException("Driver file is larger than 512 MiB.");
                    if (headerCount < 4) {
                        int copy = Math.min(4 - headerCount, n);
                        System.arraycopy(buffer, 0, header, headerCount, copy);
                        headerCount += copy;
                    }
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            } catch (Exception failure) {
                temp.delete();
                throw failure;
            }
            boolean valid = lower.endsWith(".so")
                    ? total >= 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                    : total >= 4 && header[0] == 'P' && header[1] == 'K';
            if (!valid) {
                temp.delete();
                throw new IllegalArgumentException("The file header does not match its .so or .zip extension.");
            }
            if (target.exists() && !target.delete()) {
                temp.delete();
                throw new IllegalStateException("Cannot replace the existing file.");
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                throw new IllegalStateException("Cannot save the driver file.");
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            Toast.makeText(this, "Imported: " + safe, Toast.LENGTH_LONG).show();
            status.setText("Imported: " + safe + "\nSize: " + total + " bytes\nSHA-256: " + hex
                    + "\n\nStored only — not activated by Android.");
            refreshList();
        } catch (Exception failure) {
            Toast.makeText(this, failure.getMessage() == null ? "Import failed." : failure.getMessage(), Toast.LENGTH_LONG).show();
            refreshList();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "driver.bin" : last;
    }

    private void refreshList() {
        File folder = new File(getFilesDir(), "gpu-drivers");
        File[] files = folder.listFiles();
        StringBuilder text = new StringBuilder("Stored driver files:\n");
        if (files == null || files.length == 0) text.append("None");
        else {
            for (File file : files) {
                if (file.isFile() && !file.getName().endsWith(".part")) {
                    text.append("• ").append(file.getName()).append(" (").append(file.length()).append(" bytes)\n");
                }
            }
        }
        status.setText(text.toString());
    }
}
