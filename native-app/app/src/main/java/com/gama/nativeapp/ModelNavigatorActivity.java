package com.gama.nativeapp;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
    private TextView logText;
    private ProgressBar progressBar;
    private ListView listView;
    private final StringBuilder logBuilder = new StringBuilder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ModelTreeItem libraryRoot;
    private ModelTreeItem myModelsRoot;
    private final List<ModelTreeItem> flatList = new ArrayList<>();

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
        statusText.setText("Initializing GAMA engine...");
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

        logText = new TextView(this);
        logText.setTextSize(10);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(0xFF666666);
        logText.setVisibility(View.GONE);
        logText.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(logText);

        setContentView(root);
        appendLog("Starting GAMA Native Android...");

        executor.execute(() -> {
            try {
                GamaNativeBootstrap.initialize(this, new GamaNativeBootstrap.ProgressCallback() {
                    @Override
                    public void onProgress(String message) {
                        appendLog("[PROGRESS] " + message);
                    }

                    @Override
                    public void onSuccess(String message) {
                        appendLog("[SUCCESS] " + message);
                        mainHandler.post(() -> {
                            statusText.setText("GAMA Ready");
                            progressBar.setVisibility(View.GONE);
                            buildAndShowTree();
                        });
                    }

                    @Override
                    public void onFailure(String message, Throwable t) {
                        appendLog("[FAILURE] " + message + ": " + (t != null ? t.getMessage() : ""));
                        mainHandler.post(() -> {
                            statusText.setText("FAILED: " + message);
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Bootstrap failed", e);
                appendLog("[ERROR] " + e.getMessage());
                mainHandler.post(() -> {
                    statusText.setText("ERROR: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                });
            }
        });
    }

    private void buildAndShowTree() {
        executor.execute(() -> {
            libraryRoot = buildLibraryTree();
            myModelsRoot = buildMyModelsTree();

            flatList.clear();
            if (libraryRoot != null) flattenTree(libraryRoot);
            if (myModelsRoot != null) flattenTree(myModelsRoot);

            appendLog("Total visible items: " + flatList.size());

            mainHandler.post(() -> {
                listView.setAdapter(new TreeFlatAdapter());
                listView.setVisibility(View.VISIBLE);
            });
        });
    }

    private ModelTreeItem buildLibraryTree() {
        ModelTreeItem root = new ModelTreeItem("GAMA Library", "", ModelTreeItem.Type.CATEGORY, 0, null);
        Map<String, ModelTreeItem> dirMap = new LinkedHashMap<>();
        dirMap.put("models/", root);

        try {
            JarFile jarFile = findLibraryJar();
            if (jarFile == null) {
                appendLog("Could not find gama.library JAR");
                return root;
            }

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF")) continue;
                if (name.equals("models/")) continue;

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
                    }
                } else if (name.endsWith(".gaml")) {
                    int lastSlash = name.lastIndexOf('/');
                    String parentPath = (lastSlash >= 0) ? name.substring(0, lastSlash + 1) : "";
                    String fileName = name.substring(lastSlash + 1);
                    String displayName = fileName.replace(".gaml", "");

                    ModelTreeItem parent = dirMap.get(parentPath);
                    if (parent != null) {
                        ModelTreeItem file = new ModelTreeItem(displayName, name,
                            ModelTreeItem.Type.MODEL_FILE, parent.getDepth() + 1, parent);
                        parent.getChildren().add(file);
                    }
                }
            }
            jarFile.close();
        } catch (Exception e) {
            appendLog("Error scanning library: " + e.getMessage());
        }

        pruneTree(root);
        sortTree(root);
        appendLog("Library: " + countFiles(root) + " models, " + root.getChildren().size() + " top categories");
        return root;
    }

    private JarFile findLibraryJar() {
        try {
            InputStream is = getAssets().open("gama.library.jar");
            java.io.File cacheJar = new java.io.File(getCacheDir(), "gama.library.jar");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheJar);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            is.close();
            return new JarFile(cacheJar);
        } catch (Exception e) {
            appendLog("Assets JAR open failed: " + e.getMessage());
        }
        return null;
    }

    private ModelTreeItem buildMyModelsTree() {
        ModelTreeItem root = new ModelTreeItem("My Models", "", ModelTreeItem.Type.CATEGORY, 0, null);
        try {
            String[] files = getAssets().list("models");
            if (files != null) {
                for (String file : files) {
                    if (file.endsWith(".gaml")) {
                        String displayName = file.replace(".gaml", "");
                        ModelTreeItem item = new ModelTreeItem(displayName, "models/" + file,
                            ModelTreeItem.Type.MODEL_FILE, 1, root);
                        root.getChildren().add(item);
                    }
                }
            }
        } catch (Exception e) {
            appendLog("Error scanning assets: " + e.getMessage());
        }
        appendLog("My Models: " + root.getChildren().size() + " files");
        return root;
    }

    private void sortTree(ModelTreeItem node) {
        node.getChildren().sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (ModelTreeItem child : node.getChildren()) {
            sortTree(child);
        }
    }

    private void pruneTree(ModelTreeItem node) {
        for (ModelTreeItem child : new ArrayList<>(node.getChildren())) {
            if (child.isDirectory()) {
                String name = child.getName().toLowerCase();
                if (name.equals(".settings") || name.equals("external") || name.equals("recipes") ||
                    name.equals("img") || name.equals("images") || name.equals("includes")) {
                    node.getChildren().remove(child);
                    continue;
                }
                pruneTree(child);
            }
        }

        List<ModelTreeItem> toFlatten = new ArrayList<>();
        for (ModelTreeItem child : new ArrayList<>(node.getChildren())) {
            if (child.isDirectory() && child.getName().equals("models") && !child.getChildren().isEmpty()) {
                boolean onlyFiles = true;
                for (ModelTreeItem grandchild : child.getChildren()) {
                    if (grandchild.isDirectory()) { onlyFiles = false; break; }
                }
                if (onlyFiles) {
                    toFlatten.add(child);
                }
            }
        }

        for (ModelTreeItem modelsDir : toFlatten) {
            ModelTreeItem grandparent = modelsDir.getParent();
            int idx = grandparent.getChildren().indexOf(modelsDir);
            grandparent.getChildren().remove(modelsDir);

            int newDepth = modelsDir.getDepth() - 1;
            for (ModelTreeItem file : modelsDir.getChildren()) {
                file.setDepth(newDepth);
                file.setParent(grandparent);
                grandparent.getChildren().add(idx, file);
                idx++;
            }
        }

        for (ModelTreeItem child : new ArrayList<>(node.getChildren())) {
            if (child.isDirectory() && child.getChildren().isEmpty()) {
                node.getChildren().remove(child);
            }
        }
    }

    private int countFiles(ModelTreeItem node) {
        int count = 0;
        for (ModelTreeItem child : node.getChildren()) {
            count += child.isDirectory() ? countFiles(child) : 1;
        }
        return count;
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
        if (myModelsRoot != null) flattenTree(myModelsRoot);
    }

    private void launchEditor(String modelName, String jarPath, boolean fromLibrary) {
        Intent intent = new Intent(this, ModelEditorActivity.class);
        intent.putExtra("model_name", modelName);
        intent.putExtra("jar_path", jarPath);
        intent.putExtra("from_library", fromLibrary);
        startActivity(intent);
    }

    private void launchExperiment(String modelName, String jarPath, boolean fromLibrary) {
        java.io.File savedFile = new java.io.File(getFilesDir(), "models/" + modelName + ".gaml");
        Intent intent = new Intent(this, ExperimentActivity.class);
        intent.putExtra("model_name", modelName);
        if (savedFile.exists()) {
            intent.putExtra("file_path", savedFile.getAbsolutePath());
        } else if (fromLibrary) {
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

    private void appendLog(String message) {
        mainHandler.post(() -> {
            logBuilder.append(message).append("\n");
            logText.setText(logBuilder.toString());
        });
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            value, getResources().getDisplayMetrics());
    }

    private class TreeFlatAdapter extends BaseAdapter {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_FOLDER = 1;
        private static final int TYPE_FILE = 2;

        @Override
        public int getCount() { return flatList.size(); }

        @Override
        public ModelTreeItem getItem(int position) { return flatList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public int getItemViewType(int position) {
            ModelTreeItem item = flatList.get(position);
            if (item.getDepth() == 0) return TYPE_HEADER;
            if (item.isDirectory()) return TYPE_FOLDER;
            return TYPE_FILE;
        }

        @Override
        public int getViewTypeCount() { return 3; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ModelTreeItem item = getItem(position);
            int type = getItemViewType(position);

            LinearLayout layout;
            TextView textView;

            if (convertView == null || !(convertView instanceof LinearLayout)) {
                layout = new LinearLayout(ModelNavigatorActivity.this);
                layout.setOrientation(LinearLayout.HORIZONTAL);
                layout.setGravity(Gravity.CENTER_VERTICAL);
                layout.setBackgroundResource(android.R.drawable.list_selector_background);

                textView = new TextView(ModelNavigatorActivity.this);
                textView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                layout.addView(textView);

                layout.setTag(textView);
            } else {
                layout = (LinearLayout) convertView;
                textView = (TextView) layout.getTag();
            }

            int indent = item.getDepth() * dp(20) + dp(12);

            if (type == TYPE_HEADER) {
                textView.setText("  " + item.getName() + "  (" + countFiles(item) + " models)");
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                textView.setTypeface(null, Typeface.BOLD);
                textView.setTextColor(0xFF1976D2);
                textView.setPadding(indent, dp(14), dp(12), dp(14));
                layout.setBackgroundColor(0xFFF5F5F5);
            } else if (type == TYPE_FOLDER) {
                String arrow = item.isExpanded() ? "▼ " : "▶ ";
                textView.setText(arrow + item.getName() + "  (" + item.getChildren().size() + ")");
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                textView.setTypeface(null, Typeface.BOLD);
                textView.setTextColor(0xFF333333);
                textView.setPadding(indent, dp(10), dp(12), dp(10));
                layout.setBackgroundColor(Color.WHITE);
            } else {
                textView.setText("    " + item.getName());
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                textView.setTypeface(null, Typeface.NORMAL);
                textView.setTextColor(0xFF555555);
                textView.setPadding(indent, dp(8), dp(12), dp(8));
                layout.setBackgroundColor(Color.WHITE);
            }

            layout.setOnClickListener(v -> {
                if (item.isDirectory()) {
                    item.setExpanded(!item.isExpanded());
                    refreshFlatList();
                    notifyDataSetChanged();
                } else {
                    String jarPath = item.getFullPath();
                    boolean fromLibrary = item.getFullPath().startsWith("models/");
                    ModelTreeItem parent2 = item.getParent();
                    while (parent2 != null && parent2.getParent() != null) {
                        parent2 = parent2.getParent();
                    }
                    fromLibrary = (parent2 == libraryRoot);
                    launchEditor(item.getName(), jarPath, fromLibrary);
                }
            });

            layout.setOnLongClickListener(v -> {
                if (!item.isDirectory()) {
                    String jarPath = item.getFullPath();
                    ModelTreeItem parent2 = item.getParent();
                    while (parent2 != null && parent2.getParent() != null) {
                        parent2 = parent2.getParent();
                    }
                    boolean fromLibrary = (parent2 == libraryRoot);
                    launchExperiment(item.getName(), jarPath, fromLibrary);
                    return true;
                }
                return false;
            });

            return layout;
        }
    }
}
