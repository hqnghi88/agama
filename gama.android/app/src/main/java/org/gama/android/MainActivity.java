package org.gama.android;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.utils.IOUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GamaAndroid";
    private TextView statusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusTextView = new TextView(this);
        setContentView(statusTextView);
        statusTextView.setText("GAMA Platform Android Wrapper\n\n");

        new Thread(() -> {
            try {
                setupEnvironment();
                launchGama();
            } catch (Exception e) {
                updateStatus("Error: " + e.getMessage());
                Log.e(TAG, "Exception", e);
            }
        }).start();
    }

    private void setupEnvironment() throws IOException {
        File rootfsDir = new File(getFilesDir(), "ubuntu");
        File prootPath = new File(getFilesDir(), "proot");
        
        // 1. Extract proot binary from assets
        if (!prootPath.exists()) {
            updateStatus("extracting proot...");
            copyAssetToFile("proot", prootPath);
            prootPath.setExecutable(true);
        }

        // 2. Extract GAMA binaries
        File gamaDir = new File(getFilesDir(), "gama");
        if (!new File(gamaDir, "Gama").exists()) {
            updateStatus("extracting GAMA...");
            gamaDir.mkdirs();
            File gamaTar = new File(getFilesDir(), "gama.tar.gz");
            copyAssetToFile("gama_aarch64.bundle", gamaTar);
            // Extract using robust shell pipe
            String cmd = "gunzip -c " + gamaTar.getAbsolutePath() + " | tar -xf - -C " + gamaDir.getAbsolutePath();
            runCommand("/system/bin/sh", "-c", cmd);
            gamaTar.delete();
        }
        
        // 3. Rootfs setup - use Java extraction to handle symlinks correctly
        if (!rootfsDir.exists() || !new File(rootfsDir, "usr").exists()) {
            updateStatus("Extracting Ubuntu Rootfs (this will take time)...");
            rootfsDir.mkdirs();
            
            // Extract directly from asset stream (avoid temp file if possible)
            try (InputStream assetStream = getAssets().open("ubuntu_rootfs.bundle");
                 GZIPInputStream gzipIn = new GZIPInputStream(assetStream);
                 TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
                
                extractTarWithSymlinks(tarIn, rootfsDir);
            }
            updateStatus("Rootfs ready.");
        }
    }

    /**
     * Extracts a tar archive to a destination directory.
     * Handles regular files, directories, and symbolic links correctly.
     * This is required because Android's system 'tar' cannot create symlinks
     * inside /data due to filesystem restrictions.
     */
    private void extractTarWithSymlinks(TarArchiveInputStream tarIn, File destDir) throws IOException {
        TarArchiveEntry entry;
        int count = 0;
        int symlinkErrors = 0;
        
        while ((entry = tarIn.getNextTarEntry()) != null) {
            // Skip problematic virtual filesystems
            String name = entry.getName();
            if (name.startsWith("dev/") || name.equals("dev") ||
                name.startsWith("proc/") || name.equals("proc") ||
                name.startsWith("sys/") || name.equals("sys")) {
                continue;
            }
            
            File outFile = new File(destDir, name);
            
            if (entry.isSymbolicLink()) {
                // Create symlink using Java NIO (works on Android /data)
                try {
                    Path linkPath = outFile.toPath();
                    Path targetPath = Paths.get(entry.getLinkName());
                    // Remove existing symlink if present
                    if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
                        Files.delete(linkPath);
                    }
                    outFile.getParentFile().mkdirs();
                    Files.createSymbolicLink(linkPath, targetPath);
                } catch (Exception e) {
                    symlinkErrors++;
                    Log.w(TAG, "Symlink skipped: " + name + " -> " + entry.getLinkName() + ": " + e.getMessage());
                }
            } else if (entry.isDirectory()) {
                outFile.mkdirs();
            } else if (entry.isFile()) {
                outFile.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(outFile)) {
                    IOUtils.copy(tarIn, out);
                }
                // Preserve executable bit
                if ((entry.getMode() & 0111) != 0) {
                    outFile.setExecutable(true, false);
                }
            }
            
            count++;
            if (count % 500 == 0) {
                updateStatus("Extracting... (" + count + " files)");
            }
        }
        
        Log.d(TAG, "Extracted " + count + " entries, " + symlinkErrors + " symlink warnings");
        updateStatus("Extracted " + count + " files (" + symlinkErrors + " symlink warnings).");
    }

    private void copyAssetToFile(String assetName, File outFile) throws IOException {
        try {
            try (InputStream in = getAssets().open(assetName);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[1024 * 64];
                int len;
                long totalBytes = 0;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                    totalBytes += len;
                }
                Log.d(TAG, "Copied asset " + assetName + " (" + totalBytes + " bytes)");
            }
        } catch (Exception e) {
            String msg = "Failed to copy " + assetName + ": " + e.getMessage();
            Log.e(TAG, msg, e);
            throw new IOException(msg, e);
        }
    }

    private void runCommand(String... cmd) throws IOException {
        try {
            Log.d(TAG, "Running command: " + String.join(" ", cmd));
            Process p = Runtime.getRuntime().exec(cmd);
            
            // Log outputs
            BufferedReader stdReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdReader.readLine()) != null) Log.d(TAG, "[STDOUT] " + line);
            while ((line = errReader.readLine()) != null) Log.e(TAG, "[STDERR] " + line);

            int exitCode = p.waitFor();
            Log.d(TAG, "Command finished with exit code: " + exitCode);
            if (exitCode != 0) {
                throw new IOException("Command failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted", e);
        }
    }

    private void launchGama() {
        updateStatus("Launching via PRoot...");
        try {
            File proot = new File(getFilesDir(), "proot");
            File libTalloc = new File(getFilesDir(), "libtalloc.so.2");
            File gamaDir = new File(getFilesDir(), "gama");
            File ubuntuDir = new File(getFilesDir(), "ubuntu");

            // Extract the library if missing
            if (!libTalloc.exists()) {
                copyAssetToFile("libtalloc.so.2", libTalloc);
            }

            ProcessBuilder pb = new ProcessBuilder(
                proot.getAbsolutePath(),
                "-v", "-1",           // Keep diagnostics (helps with timing/seccomp)
                "-r", ubuntuDir.getAbsolutePath(),
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", gamaDir.getAbsolutePath() + ":/gama",
                "-w", "/gama",
                "/bin/sh", "-c", "/gama/Gama"
            );
            
            pb.directory(gamaDir);
            pb.environment().put("PROOT_NO_SECCOMP", "1");
            pb.environment().put("PROOT_FORCE_PTRACE_TRACEME", "1");
            pb.environment().put("PROOT_TMP_DIR", getFilesDir().getAbsolutePath());
            pb.environment().put("LD_LIBRARY_PATH", getFilesDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            
            // Ensure EVERYTHING in Ubuntu and GAMA is executable
            updateStatus("Setting permissions...");
            setExecutableRecursively(ubuntuDir);
            setExecutableRecursively(gamaDir);
            updateStatus("Permissions set.");
            
            pb.environment().put("DISPLAY", ":0");
            pb.environment().put("GDK_BACKEND", "x11");
            pb.environment().put("HOME", "/root");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                updateStatus("[LINUX] " + line);
            }
        } catch (Exception e) {
            updateStatus("Launch failed: " + e.getMessage());
        }
    }

    private void updateStatus(final String text) {
        runOnUiThread(() -> {
            statusTextView.append(text + "\n");
        });
    }

    private int getPid(Process p) {
        return p.hashCode();
    }

    private void setExecutableRecursively(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                setExecutableRecursively(file);
            } else {
                file.setExecutable(true, false);
            }
        }
        dir.setExecutable(true, false);
    }
}
