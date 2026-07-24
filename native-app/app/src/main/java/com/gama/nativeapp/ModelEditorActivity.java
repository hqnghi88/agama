package com.gama.nativeapp;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelEditorActivity extends AppCompatActivity {

    private static final String TAG = "ModelEditor";

    private static final int COLOR_KEYWORD = 0xFF569CD6;
    private static final int COLOR_TYPE = 0xFF4EC9B0;
    private static final int COLOR_CONSTANT = 0xFF569CD6;
    private static final int COLOR_STRING = 0xFFCE9178;
    private static final int COLOR_COMMENT = 0xFF6A9955;
    private static final int COLOR_NUMBER = 0xFFB5CEA8;
    private static final int COLOR_FUNCTION = 0xFFDCDCAA;
    private static final int COLOR_DEFAULT = 0xFFD4D4D4;
    private static final int COLOR_LINE_NUMBER = 0xFF858585;
    private static final int COLOR_LINE_NUMBER_BG = 0xFF2D2D2D;
    private static final int COLOR_EDITOR_BG = 0xFF1E1E1E;
    private static final int COLOR_CURRENT_LINE = 0xFF2A2D2E;
    private static final int COLOR_TOOLBAR = 0xFF2D2D2D;

    private static final Pattern PATTERN_KEYWORD = Pattern.compile(
        "\\b(model|species|grid|experiment|reflex|action|rule|output|init|global|test|" +
        "when|if|else|loop|ask|create|die|move|turn|release|capture|restore|write|save|" +
        "user_command|user_panel|user_input|display|layout|dialog|chart|monitor|" +
        "return|break|continue|while|for|each|in|as|of|from|to|step|every|" +
        "assert|error|warning|note|debug|info|" +
        "not|and|or|xor|" +
        "aspect|parent|definition|skills|location|text|" +
        "parameter|returns|type|returns|let|var|" +
        "schedule|status|update|constant|value|" +
        "equation|solve|diffuse|" +
        "state|enter|exit|on_match|" +
        "invalidate|message|condition|" +
        "species|of|parent|children|host|" +
        "using|neighbors|mesh|with_agent_type|" +
        "field|overlay|agate|draw|" +
        "light|camera|" +
        "data|image|file|database|" +
        "menu|button|slider|" +
        "inspect|ask|tell|do|kill|create|" +
        "match|switch|try|catch|throw|" +
        "abs|acos|asin|atan|atan2|ceil|cos|exp|floor|ln|log|max|min|mod|round|sin|sqrt|tan|" +
        "length|empty|contains|copy|reverse|sort|among|first|last|one_of|n_of|" +
        "shuffle|where|collect|accumulate|all_match|any_match|none_match|count|" +
        "mean|variance|std_dev|min_of|max_of|sum_of|product_of|" +
        "remove|add|at|index_of|sort_by|group_by|aggregat|" +
        "self|myself|world|nil|pi|e|" +
        "true|false|" +
        "geometry|point|polygon|polyline|line|rectangle|circle|ellipse|triangle|cone|sphere|cylinder|cube|hexagon|band|" +
        "graph|node|edge|agent|species|file|container|matrix|int|float|bool|string|rgb|date|list|map|pair|" +
        "pair|matrix|" +
        "container|date|image|" +
        "int|float|bool|string|rgb|date|list|map|matrix|pair|point|geometry|agent|species|file|container)\\b");

    private static final Pattern PATTERN_TYPE = Pattern.compile(
        "\\b(int|float|bool|string|rgb|date|list|map|matrix|pair|point|geometry|agent|species|file|container|graph)\\b");

    private static final Pattern PATTERN_STRING = Pattern.compile(
        "\"[^\"]*\"|'[^']*'");

    private static final Pattern PATTERN_COMMENT_LINE = Pattern.compile(
        "//[^\n]*");

    private static final Pattern PATTERN_COMMENT_BLOCK = Pattern.compile(
        "/\\*[\\s\\S]*?\\*/");

    private static final Pattern PATTERN_NUMBER = Pattern.compile(
        "\\b\\d+\\.?\\d*([eE][+-]?\\d+)?\\b");

    private static final Pattern PATTERN_FUNCTION = Pattern.compile(
        "\\b([a-zA-Z_]\\w*)\\s*(?=\\()");

    private EditText codeEditor;
    private TextView lineNumbers;
    private TextView titleText;
    private String modelName;
    private String jarPath;
    private boolean fromLibrary;
    private String currentContent = "";
    private boolean isModified = false;
    private boolean isLoading = true;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setGuiActivity(this);

        modelName = getIntent().getStringExtra("model_name");
        jarPath = getIntent().getStringExtra("jar_path");
        fromLibrary = getIntent().getBooleanExtra("from_library", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_EDITOR_BG);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(COLOR_TOOLBAR);
        toolbar.setMinimumHeight(dp(56));
        toolbar.setContentInsetsRelative(0, 0);
        LinearLayout toolbarContent = new LinearLayout(toolbar.getContext());
        toolbarContent.setOrientation(LinearLayout.HORIZONTAL);
        toolbarContent.setGravity(Gravity.CENTER_VERTICAL);
        toolbarContent.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(toolbarContent);

        TextView backBtn = new TextView(toolbar.getContext());
        backBtn.setText("←");
        backBtn.setTextSize(20);
        backBtn.setTextColor(Color.WHITE);
        backBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        backBtn.setOnClickListener(v -> finish());
        toolbarContent.addView(backBtn);

        titleText = new TextView(toolbar.getContext());
        titleText.setText(modelName + (isModified ? " *" : ""));
        titleText.setTextSize(15);
        titleText.setTextColor(Color.WHITE);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setPadding(dp(8), 0, 0, 0);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        toolbarContent.addView(titleText);

        TextView saveBtn = new TextView(toolbar.getContext());
        saveBtn.setText("Save");
        saveBtn.setTextSize(13);
        saveBtn.setTextColor(0xFF90CAF9);
        saveBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        saveBtn.setOnClickListener(v -> saveFile());
        toolbarContent.addView(saveBtn);

        TextView runBtn = new TextView(toolbar.getContext());
        runBtn.setText("▶ Run");
        runBtn.setTextSize(13);
        runBtn.setTextColor(0xFF66BB6A);
        runBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        runBtn.setOnClickListener(v -> runModel());
        toolbarContent.addView(runBtn);

        root.addView(toolbar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout editorArea = new LinearLayout(this);
        editorArea.setOrientation(LinearLayout.HORIZONTAL);
        editorArea.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        lineNumbers = new TextView(this);
        lineNumbers.setBackgroundColor(COLOR_LINE_NUMBER_BG);
        lineNumbers.setTextColor(COLOR_LINE_NUMBER);
        lineNumbers.setTextSize(13);
        lineNumbers.setTypeface(Typeface.MONOSPACE);
        lineNumbers.setPadding(dp(8), dp(8), dp(4), dp(8));
        lineNumbers.setGravity(Gravity.TOP | Gravity.END);
        lineNumbers.setText("1\n");
        editorArea.addView(lineNumbers, new LinearLayout.LayoutParams(
            dp(48), LinearLayout.LayoutParams.MATCH_PARENT));

        codeEditor = new EditText(this);
        codeEditor.setBackgroundColor(COLOR_EDITOR_BG);
        codeEditor.setTextColor(COLOR_DEFAULT);
        codeEditor.setTextSize(13);
        codeEditor.setTypeface(Typeface.MONOSPACE);
        codeEditor.setPadding(dp(4), dp(8), dp(8), dp(8));
        codeEditor.setGravity(Gravity.TOP | Gravity.START);
        codeEditor.setHorizontallyScrolling(true);
        codeEditor.setVerticalScrollBarEnabled(true);
        codeEditor.setHorizontalScrollBarEnabled(true);
        codeEditor.setRawInputType(0xF001);
        codeEditor.setTextIsSelectable(true);

        codeEditor.getViewTreeObserver().addOnScrollChangedListener(() -> {
            lineNumbers.scrollTo(0, codeEditor.getScrollY());
        });

        editorArea.addView(codeEditor, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        root.addView(editorArea);

        setContentView(root);

        codeEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (isLoading) return;
                if (!s.toString().equals(currentContent)) {
                    isModified = true;
                    titleText.setText(modelName + " *");
                }
                highlightSyntax(s);
                updateLineNumbers();
            }
        });

        loadFile();
    }

    private void loadFile() {
        executor.execute(() -> {
            try {
                String content = null;

                if (fromLibrary && jarPath != null) {
                    content = readFromJar(jarPath);
                } else {
                    File internalFile = new File(getFilesDir(), "models/" + modelName + ".gaml");
                    if (internalFile.exists()) {
                        content = readFile(internalFile);
                    } else if (jarPath != null) {
                        content = readFromAssets(jarPath);
                    }
                }

                if (content == null) {
                    content = "// " + modelName + "\n// Model not found\n";
                }

                final String finalContent = content;
                mainHandler.post(() -> {
                    isLoading = true;
                    currentContent = finalContent;
                    isModified = false;
                    codeEditor.setText(finalContent);
                    codeEditor.setSelection(0);
                    titleText.setText(modelName);
                    highlightSyntax(codeEditor.getEditableText());
                    updateLineNumbers();
                    isLoading = false;
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Error loading: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    codeEditor.setText("// Error loading model: " + e.getMessage());
                });
            }
        });
    }

    private String readFromJar(String entryPath) {
        try {
            JarFile jarFile = findLibraryJar();
            if (jarFile == null) return null;
            JarEntry entry = jarFile.getJarEntry(entryPath);
            if (entry == null) {
                jarFile.close();
                return null;
            }
            InputStream is = jarFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
            reader.close();
            is.close();
            jarFile.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String readFromAssets(String path) {
        try {
            InputStream is = getAssets().open(path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
            reader.close();
            is.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String readFile(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
            reader.close();
            fis.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private JarFile findLibraryJar() {
        try {
            java.io.File cacheJar = new java.io.File(getCacheDir(), "gama.library.jar");
            if (!cacheJar.exists()) {
                InputStream is = getAssets().open("gama.library.jar");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheJar);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();
            }
            return new JarFile(cacheJar);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveFile() {
        executor.execute(() -> {
            try {
                File modelsDir = new File(getFilesDir(), "models");
                modelsDir.mkdirs();
                File file = new File(modelsDir, modelName + ".gaml");
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(codeEditor.getText().toString().getBytes("UTF-8"));
                fos.close();

                mainHandler.post(() -> {
                    isModified = false;
                    titleText.setText(modelName);
                    Toast.makeText(this, "Saved: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                    Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void runModel() {
        try {
            File modelsDir = new File(getFilesDir(), "models");
            modelsDir.mkdirs();
            File file = new File(modelsDir, modelName + ".gaml");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(codeEditor.getText().toString().getBytes("UTF-8"));
            fos.close();
            isModified = false;

            Intent intent = new Intent(this, ExperimentActivity.class);
            intent.putExtra("model_name", modelName);
            if (fromLibrary && jarPath != null) {
                intent.putExtra("jar_path", jarPath);
                intent.putExtra("from_library", true);
            } else {
                intent.putExtra("file_path", file.getAbsolutePath());
            }
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static final int MAX_HIGHLIGHT_LENGTH = 50000;

    private void highlightSyntax(Editable text) {
        if (text.length() > MAX_HIGHLIGHT_LENGTH) return;

        ForegroundColorSpan[] spans = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) text.removeSpan(span);
        StyleSpan[] styleSpans = text.getSpans(0, text.length(), StyleSpan.class);
        for (StyleSpan span : styleSpans) text.removeSpan(span);

        String code = text.toString();

        Matcher commentBlock = PATTERN_COMMENT_BLOCK.matcher(code);
        while (commentBlock.find()) {
            text.setSpan(new ForegroundColorSpan(COLOR_COMMENT),
                commentBlock.start(), commentBlock.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        Matcher commentLine = PATTERN_COMMENT_LINE.matcher(code);
        while (commentLine.find()) {
            boolean inBlock = false;
            Matcher blockChecker = PATTERN_COMMENT_BLOCK.matcher(code);
            while (blockChecker.find()) {
                if (blockChecker.start() <= commentLine.start() && blockChecker.end() >= commentLine.end()) {
                    inBlock = true;
                    break;
                }
            }
            if (!inBlock) {
                text.setSpan(new ForegroundColorSpan(COLOR_COMMENT),
                    commentLine.start(), commentLine.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher stringMatch = PATTERN_STRING.matcher(code);
        while (stringMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(stringMatch.start(), stringMatch.end(), ForegroundColorSpan.class);
            if (existing.length > 0) {
                for (ForegroundColorSpan s : existing) {
                    int sStart = text.getSpanStart(s);
                    int sEnd = text.getSpanEnd(s);
                    if (sStart <= stringMatch.start() && sEnd >= stringMatch.end()) {
                        covered = true;
                        break;
                    }
                }
            }
            if (!covered) {
                text.setSpan(new ForegroundColorSpan(COLOR_STRING),
                    stringMatch.start(), stringMatch.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher numberMatch = PATTERN_NUMBER.matcher(code);
        while (numberMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(numberMatch.start(), numberMatch.end(), ForegroundColorSpan.class);
            for (ForegroundColorSpan s : existing) {
                int sStart = text.getSpanStart(s);
                int sEnd = text.getSpanEnd(s);
                if (sStart <= numberMatch.start() && sEnd >= numberMatch.end()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                text.setSpan(new ForegroundColorSpan(COLOR_NUMBER),
                    numberMatch.start(), numberMatch.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher keywordMatch = PATTERN_KEYWORD.matcher(code);
        while (keywordMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(keywordMatch.start(), keywordMatch.end(), ForegroundColorSpan.class);
            for (ForegroundColorSpan s : existing) {
                int sStart = text.getSpanStart(s);
                int sEnd = text.getSpanEnd(s);
                if (sStart <= keywordMatch.start() && sEnd >= keywordMatch.end()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                text.setSpan(new ForegroundColorSpan(COLOR_KEYWORD),
                    keywordMatch.start(), keywordMatch.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new StyleSpan(Typeface.BOLD),
                    keywordMatch.start(), keywordMatch.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher funcMatch = PATTERN_FUNCTION.matcher(code);
        while (funcMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(funcMatch.start(), funcMatch.end(), ForegroundColorSpan.class);
            for (ForegroundColorSpan s : existing) {
                int sStart = text.getSpanStart(s);
                int sEnd = text.getSpanEnd(s);
                if (sStart <= funcMatch.start() && sEnd >= funcMatch.end()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                String funcName = funcMatch.group(1);
                if (funcName != null && !PATTERN_KEYWORD.matcher(funcName).matches()) {
                    text.setSpan(new ForegroundColorSpan(COLOR_FUNCTION),
                        funcMatch.start(1), funcMatch.end(1),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }

    private void updateLineNumbers() {
        String text = codeEditor.getText().toString();
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        StringBuilder sb = new StringBuilder(lines * 4);
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append('\n');
        }
        lineNumbers.setText(sb.toString());
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            value, getResources().getDisplayMetrics());
    }

    private static void setGuiActivity(ModelEditorActivity activity) {
        try {
            Class<?> handlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            handlerClass.getMethod("setActivity", android.app.Activity.class).invoke(null, activity);
        } catch (Throwable e) { /* ignore */ }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setGuiActivity(this);
    }

    @Override
    public void onBackPressed() {
        if (isModified) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("Unsaved Changes")
                .setMessage("Save changes before closing?")
                .setPositiveButton("Save", (d, w) -> {
                    saveFile();
                    finish();
                })
                .setNegativeButton("Discard", (d, w) -> finish())
                .setNeutralButton("Cancel", null)
                .show();
        } else {
            super.onBackPressed();
        }
    }
}
