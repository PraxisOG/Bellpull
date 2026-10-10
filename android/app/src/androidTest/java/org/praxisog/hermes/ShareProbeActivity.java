package org.praxisog.hermes;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.*;
import java.security.MessageDigest;

/** Separate test APK/UID: a chosen Sharesheet recipient must read its granted file. */
public class ShareProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String result;
        try {
            if (!getPackageName().endsWith(".test")) throw new Exception("Not the test recipient");
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            int flags = getIntent().getFlags();
            if (uri == null || !"content".equals(uri.getScheme())) throw new Exception("Missing content URI");
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 ||
                (flags & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) != 0)
                throw new Exception("Incorrect grant scope");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            }
            byte[] data = bytes.toByteArray();
            if (!new String(data, 0, Math.min(data.length, 5), "US-ASCII").equals("%PDF-")) throw new Exception("Not PDF bytes");
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) hash.append(String.format("%02x", value));
            result = "PASS: " + data.length + " bytes; SHA-256 " + hash;
        } catch (Exception error) { result = "FAIL: " + error.getMessage(); }
        try (FileWriter file = new FileWriter(new File(getFilesDir(), "share-probe.txt"))) { file.write(result); }
        catch (IOException error) { throw new RuntimeException(error); }
        TextView text = new TextView(this); text.setText(result); text.setTextSize(22); text.setPadding(24,24,24,24); setContentView(text);
    }
}
