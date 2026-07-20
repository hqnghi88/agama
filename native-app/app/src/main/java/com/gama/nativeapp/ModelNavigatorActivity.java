package com.gama.nativeapp;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ModelNavigatorActivity extends AppCompatActivity {

    private static final String TAG = "ModelNavigator";
    private TextView statusText;
    private ProgressBar progressBar;
    private ListView listView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ModelTreeItem libraryRoot;
    private final List<ModelTreeItem> flatList = new ArrayList<>();
    private int totalFiles;
    private int totalDirs;
    private long totalSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setGuiActivity(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setBackgroundColor(0xFF1976D2);

        TextView navBackBtn = new TextView(this);
        navBackBtn.setText("\u25C0");
        navBackBtn.setTextSize(16);
        navBackBtn.setTextColor(Color.WHITE);
        navBackBtn.setPadding(dp(4), dp(4), dp(12), dp(4));
        navBackBtn.setOnClickListener(v -> finish());
        statusBar.addView(navBackBtn);

        statusText = new TextView(this);
        statusText.setText("Extracting library...");
        statusText.setTextSize(15);
        statusText.setTextColor(Color.WHITE);
        statusText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusBar.addView(statusText);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.VISIBLE);
        statusBar.addView(progressBar);

        root.addView(statusBar);

        listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setDivider(getResources().getDrawable(android.R.drawable.divider_horizontal_bright));
        listView.setVisibility(View.GONE);
        root.addView(listView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        executor.execute(() -> {
            try {
                GamaNativeBootstrap.initialize(this, new GamaNativeBootstrap.ProgressCallback() {
                    @Override public void onProgress(String msg) {
                        mainHandler.post(() -> statusText.setText(msg));
                    }
                    @Override public void onSuccess(String msg) {
                        mainHandler.post(() -> statusText.setText("Extracting library..."));
                        extractLibrary();
                    }
                    @Override public void onFailure(String msg, Throwable t) {
                        mainHandler.post(() -> statusText.setText("FAILED: " + msg));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Bootstrap failed", e);
                mainHandler.post(() -> statusText.setText("ERROR: " + e.getMessage()));
            }
        });
    }

    private void extractLibrary() {
        executor.execute(() -> {
            try {
                JarFile jarFile = findLibraryJar();
                if (jarFile == null) {
                    mainHandler.post(() -> { statusText.setText("JAR not found"); progressBar.setVisibility(View.GONE); });
                    return;
                }

                File cacheDir = getCacheDir();
                File modelsDir = new File(cacheDir, "models");
                int[] counts = {0};
                long[] bytes = {0};

                mainHandler.post(() -> statusText.setText("Extracting files..."));

                Enumeration<? extends JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("META-INF")) continue;
                    if (entry.isDirectory()) continue;

                    File outFile = new File(cacheDir, name);
                    outFile.getParentFile().mkdirs();

                    try (InputStream is = jarFile.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            bytes[0] += n;
                        }
                    }
                    counts[0]++;

                    final int c = counts[0];
                    if (c % 100 == 0) {
                        final String sizeStr = Formatter.formatFileSize(this, bytes[0]);
                        mainHandler.post(() -> statusText.setText("Extracted " + c + " files (" + sizeStr + ")..."));
                    }
                }
                jarFile.close();

                final int fileCount = counts[0];
                final String sizeStr = Formatter.formatFileSize(this, bytes[0]);
                mainHandler.post(() -> {
                    statusText.setText(fileCount + " files extracted (" + sizeStr + ")");
                    buildAndShowTree();
                });
            } catch (Exception e) {
                Log.e(TAG, "Extraction failed", e);
                mainHandler.post(() -> { statusText.setText("Extraction error: " + e.getMessage()); progressBar.setVisibility(View.GONE); });
            }
        });
    }

    private void buildAndShowTree() {
        libraryRoot = buildTree();
        flatList.clear();
        if (libraryRoot != null) {
            libraryRoot.setExpanded(true);
            for (ModelTreeItem child : libraryRoot.getChildren()) {
                child.setExpanded(true);
            }
            flattenTree(libraryRoot);
        }

        mainHandler.post(() -> {
            progressBar.setVisibility(View.GONE);
            statusText.setText(totalFiles + " files, " + totalDirs + " folders (" + Formatter.formatFileSize(this, totalSize) + ")");
            listView.setAdapter(new TreeFlatAdapter());
            listView.setVisibility(View.VISIBLE);
        });
    }

    private ModelTreeItem buildTree() {
        ModelTreeItem root = new ModelTreeItem("GAMA Library", "", ModelTreeItem.Type.CATEGORY, 0, null);
        Map<String, ModelTreeItem> dirMap = new LinkedHashMap<>();
        dirMap.put("", root);

        totalFiles = 0;
        totalDirs = 0;
        totalSize = 0;

        try {
            JarFile jarFile = findLibraryJar();
            if (jarFile == null) return root;

            Enumeration<? extends JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF")) continue;

                if (entry.isDirectory()) {
                    if (!name.endsWith("/")) name += "/";
                    String parentPath = name.substring(0, name.lastIndexOf('/', name.length() - 2) + 1);
                    String dirName = name.substring(parentPath.length(), name.length() - 1);
                    if (dirName.isEmpty()) continue;

                    ModelTreeItem parent = dirMap.get(parentPath);
                    if (parent != null) {
                        ModelTreeItem dir = new ModelTreeItem(dirName, name,
                            ModelTreeItem.Type.CATEGORY, parent.getDepth() + 1, parent);
                        parent.getChildren().add(dir);
                        dirMap.put(name, dir);
                        totalDirs++;
                    }
                } else {
                    int lastSlash = name.lastIndexOf('/');
                    String parentPath = (lastSlash >= 0) ? name.substring(0, lastSlash + 1) : "";
                    String fileName = name.substring(lastSlash + 1);
                    String ext = "";
                    int dot = fileName.lastIndexOf('.');
                    if (dot >= 0) ext = fileName.substring(dot + 1).toLowerCase();

                    ModelTreeItem parent = dirMap.get(parentPath);
                    if (parent != null) {
                        ModelTreeItem.Type fileType;
                        if ("gaml".equals(ext)) {
                            fileType = ModelTreeItem.Type.MODEL_FILE;
                        } else {
                            fileType = ModelTreeItem.Type.FILE;
                        }
                        ModelTreeItem file = new ModelTreeItem(fileName, name,
                            fileType, parent.getDepth() + 1, parent);
                        file.setFileSize(entry.getSize());
                        parent.getChildren().add(file);
                        totalFiles++;
                        totalSize += entry.getSize();
                    }
                }
            }
            jarFile.close();
        } catch (Exception e) {
            Log.e(TAG, "Error scanning library", e);
        }

        sortTree(root);
        pruneEmptyDirs(root);
        return root;
    }

    private JarFile findLibraryJar() {
        try {
            File cacheJar = new File(getCacheDir(), "gama.library.jar");
            if (!cacheJar.exists()) {
                InputStream is = getAssets().open("gama.library.jar");
                FileOutputStream fos = new FileOutputStream(cacheJar);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();
            }
            return new JarFile(cacheJar);
        } catch (Exception e) {
            Log.e(TAG, "JAR open failed", e);
        }
        return null;
    }

    private void sortTree(ModelTreeItem node) {
        node.getChildren().sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (ModelTreeItem child : node.getChildren()) {
            if (child.isDirectory()) sortTree(child);
        }
    }

    private void pruneEmptyDirs(ModelTreeItem node) {
        for (ModelTreeItem child : new ArrayList<>(node.getChildren())) {
            if (child.isDirectory()) {
                pruneEmptyDirs(child);
                if (child.getChildren().isEmpty()) {
                    node.getChildren().remove(child);
                }
            }
        }
    }

    private void flattenTree(ModelTreeItem node) {
        flatList.add(node);
        if (node.isDirectory() && node.isExpanded()) {
            for (ModelTreeItem child : node.getChildren()) {
                flattenTree(child);
            }
        }
    }

    private void refreshFlatList() {
        flatList.clear();
        if (libraryRoot != null) flattenTree(libraryRoot);
    }

    private void launchEditor(String name, String jarPath, boolean fromLibrary) {
        Intent intent = new Intent(this, ModelEditorActivity.class);
        intent.putExtra("model_name", name);
        intent.putExtra("jar_path", jarPath);
        intent.putExtra("from_library", fromLibrary);
        startActivity(intent);
    }

    private void launchExperiment(String name, String jarPath, boolean fromLibrary) {
        Intent intent = new Intent(this, ExperimentActivity.class);
        intent.putExtra("model_name", jarPath);
        if (fromLibrary) {
            intent.putExtra("jar_path", jarPath);
            intent.putExtra("from_library", true);
        } else {
            intent.putExtra("asset_path", jarPath);
        }
        startActivity(intent);
    }

    private static void setGuiActivity(ModelNavigatorActivity activity) {
        try {
            Class<?> handlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            handlerClass.getMethod("setActivity", android.app.Activity.class).invoke(null, activity);
        } catch (Throwable e) {
            Log.w(TAG, "Could not set GUI activity", e);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            value, getResources().getDisplayMetrics());
    }

    private class TreeFlatAdapter extends BaseAdapter {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_FOLDER = 1;
        private static final int TYPE_MODEL = 2;
        private static final int TYPE_FILE = 3;

        @Override public int getCount() { return flatList.size(); }
        @Override public ModelTreeItem getItem(int position) { return flatList.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public int getItemViewType(int position) {
            ModelTreeItem item = flatList.get(position);
            if (item.getDepth() == 0) return TYPE_HEADER;
            if (item.isDirectory()) return TYPE_FOLDER;
            if (item.getType() == ModelTreeItem.Type.MODEL_FILE) return TYPE_MODEL;
            return TYPE_FILE;
        }

        @Override public int getViewTypeCount() { return 4; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ModelTreeItem item = getItem(position);
            int type = getItemViewType(position);

            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(ModelNavigatorActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(android.R.drawable.list_selector_background);
            }
            row.removeAllViews();

            int indent = item.getDepth() * dp(18) + dp(8);

            if (type == TYPE_HEADER) {
                TextView tv = makeTextView("  " + item.getName() + "  (" + totalFiles + " files, " + totalDirs + " dirs)",
                    14, Typeface.BOLD, 0xFF1976D2, indent, dp(14));
                row.addView(tv);
                row.setBackgroundColor(0xFFF5F5F5);
                row.setOnClickListener(null);
                row.setOnLongClickListener(null);
            } else if (type == TYPE_FOLDER) {
                String arrow = item.isExpanded() ? "\u25BC " : "\u25B6 ";
                int count = item.getChildren().size();
                int fileCount = 0;
                for (ModelTreeItem c : item.getChildren()) {
                    if (!c.isDirectory()) fileCount++;
                }
                TextView tv = makeTextView(arrow + item.getName() + "  (" + fileCount + ")",
                    13, Typeface.BOLD, 0xFF333333, indent, dp(10));
                row.addView(tv);
                row.setBackgroundColor(Color.WHITE);
                row.setOnClickListener(v -> {
                    item.setExpanded(!item.isExpanded());
                    refreshFlatList();
                    notifyDataSetChanged();
                });
                row.setOnLongClickListener(null);
            } else if (type == TYPE_MODEL) {
                TextView badge = makeBadge("GAML", 0xFF4CAF50);
                row.addView(badge);
                TextView tv = makeTextView(item.getName(),
                    13, Typeface.BOLD, 0xFF1B5E20, dp(4), dp(8));
                row.addView(tv);
                row.setBackgroundColor(Color.WHITE);
                row.setOnClickListener(v -> {
                    boolean fromLibrary = isFromLibrary(item);
                    launchEditor(item.getName(), item.getFullPath(), fromLibrary);
                });
                row.setOnLongClickListener(v -> {
                    boolean fromLibrary = isFromLibrary(item);
                    launchExperiment(item.getName(), item.getFullPath(), fromLibrary);
                    return true;
                });
            } else {
                String ext = item.getExtension();
                int color = ModelTreeItem.getExtensionColor(ext);
                String label = ModelTreeItem.getExtensionLabel(ext);
                TextView badge = makeBadge(label, color);
                row.addView(badge);
                String sizeStr = "";
                if (item.getFileSize() > 0) {
                    sizeStr = "  " + Formatter.formatFileSize(ModelNavigatorActivity.this, item.getFileSize());
                }
                TextView tv = makeTextView(item.getName() + sizeStr,
                    12, Typeface.NORMAL, 0xFF555555, dp(4), dp(8));
                row.addView(tv);
                row.setBackgroundColor(Color.WHITE);
                row.setOnClickListener(v -> {
                    boolean fromLibrary = isFromLibrary(item);
                    launchEditor(item.getName(), item.getFullPath(), fromLibrary);
                });
                row.setOnLongClickListener(null);
            }

            return row;
        }

        private boolean isFromLibrary(ModelTreeItem item) {
            ModelTreeItem p = item.getParent();
            while (p != null && p.getParent() != null) {
                p = p.getParent();
            }
            return p == libraryRoot;
        }

        private TextView makeTextView(String text, float sp, int typeface, int color, int leftPad, int vertPad) {
            TextView tv = new TextView(ModelNavigatorActivity.this);
            tv.setText(text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            tv.setTypeface(null, typeface);
            tv.setTextColor(color);
            tv.setPadding(leftPad, vertPad, dp(8), vertPad);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setSingleLine(true);
            return tv;
        }

        private TextView makeBadge(String label, int bgColor) {
            TextView badge = new TextView(ModelNavigatorActivity.this);
            badge.setText(" " + label + " ");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badge.setTextColor(Color.WHITE);
            badge.setBackgroundColor(bgColor);
            badge.setPadding(dp(4), dp(2), dp(4), dp(2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), dp(6), dp(2), dp(6));
            badge.setLayoutParams(lp);
            return badge;
        }
    }
}
