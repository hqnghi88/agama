package org.gama.android;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import gama.core.runtime.GAMA;
import gaml.compiler.GamlStandaloneSetup;
import gama.core.kernel.model.IModel;
import gaml.compiler.gaml.validation.GamlModelBuilder;
import org.eclipse.emf.common.util.URI;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        statusText.setText("Initializing GAMA Platform...");

        new Thread(() -> {
            try {
                // 1. Initialize Gaml Standalone Setup (Injector)
                GamlStandaloneSetup.doSetup();
                
                updateStatus("GAMA Initialized. Compiling model...");

                // 2. Prepare a sample model from assets
                File modelFile = prepareSampleModel();
                
                // 3. Compile the model
                GamlModelBuilder builder = new GamlModelBuilder(GamlStandaloneSetup.doSetup());
                List<gama.gaml.compilation.GamlCompilationError> errors = new ArrayList<>();
                URI uri = URI.createFileURI(modelFile.getAbsolutePath());
                IModel model = builder.compile(uri, errors);

                if (model != null) {
                    updateStatus("Model compiled successfully: " + model.getName());
                    // In the future: Run simulation here
                } else {
                    updateStatus("Compilation failed. Errors: " + errors.size());
                }

            } catch (Exception e) {
                updateStatus("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void updateStatus(final String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private File prepareSampleModel() throws IOException {
        // Simple GAML model string
        String modelContent = "model sample\n\nglobal {\n    init {\n        write 'Hello from Native Android GAMA!';\n    }\n}\n\nexperiment simple type: gui {}";
        File dir = getExternalFilesDir(null);
        File file = new File(dir, "sample.gaml");
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write(modelContent.getBytes());
        }
        return file;
    }
}
