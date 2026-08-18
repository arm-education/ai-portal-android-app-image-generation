package org.arm.learningpath.tinysdstudio;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int MODEL_ARCHIVE_REQUEST = 1001;
    private static final int SAVE_IMAGE_REQUEST = 1002;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AdapterRegistry adapterRegistry;
    private ImageGenerationAdapter generationAdapter;
    private ModelDescriptor modelDescriptor;
    private EditText promptInput;
    private EditText seedInput;
    private Button generateButton;
    private Button importButton;
    private Button saveButton;
    private ProgressBar progressBar;
    private TextView modelStatus;
    private TextView statusView;
    private LinearLayout resultCard;
    private TextView resultCaption;
    private TextView resultMeta;
    private TextView resultFooter;
    private ImageView imageView;
    private Bitmap generatedBitmap;
    private boolean generating;
    private boolean importing;
    private boolean saving;
    private boolean modelReady;
    private long deviceMemoryBytes;
    private boolean memoryReady;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        adapterRegistry = new AdapterRegistry(this);
        if (CompatibleModelRegistry.models().isEmpty()) {
            throw new IllegalStateException("No compatible image-generation model is registered");
        }
        modelDescriptor = CompatibleModelRegistry.models().get(0);
        generationAdapter = adapterRegistry.requireAdapter(modelDescriptor.adapterId());
        deviceMemoryBytes = readDeviceMemoryBytes();
        memoryReady = deviceMemoryBytes >= modelDescriptor.minimumMemoryBytes();

        setContentView(R.layout.activity_main);
        bindContent();
        updateModelState();
    }

    private void bindContent() {
        ScrollView screen = findViewById(R.id.screen);
        View hero = findViewById(R.id.hero);
        View body = findViewById(R.id.body);
        applyWindowInsets(screen, hero, body);
        applySystemBarAppearance();

        promptInput = findViewById(R.id.prompt_input);
        seedInput = findViewById(R.id.seed_input);
        generateButton = findViewById(R.id.generate_button);
        importButton = findViewById(R.id.import_button);
        saveButton = findViewById(R.id.save_button);
        progressBar = findViewById(R.id.progress_bar);
        modelStatus = findViewById(R.id.model_status);
        statusView = findViewById(R.id.status_view);
        resultCard = findViewById(R.id.result_card);
        resultCaption = findViewById(R.id.result_caption);
        resultMeta = findViewById(R.id.result_meta);
        resultFooter = findViewById(R.id.result_footer);
        resultFooter.setText(
                modelDescriptor.outputDescription() + "  •  " + modelDescriptor.runtimeName()
        );
        imageView = findViewById(R.id.image_view);
        imageView.setClipToOutline(true);

        generateButton.setOnClickListener(view -> startGeneration());
        importButton.setOnClickListener(view -> openModelArchive());
        saveButton.setOnClickListener(view -> openSaveImageDialog());
    }

    private void applyWindowInsets(ScrollView screen, View hero, View body) {
        int heroLeftPadding = hero.getPaddingLeft();
        int heroTopPadding = hero.getPaddingTop();
        int heroRightPadding = hero.getPaddingRight();
        int heroBottomPadding = hero.getPaddingBottom();
        int bodyLeftPadding = body.getPaddingLeft();
        int bodyTopPadding = body.getPaddingTop();
        int bodyRightPadding = body.getPaddingRight();
        int bodyBottomPadding = body.getPaddingBottom();
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = Math.max(
                    insets.getSystemWindowInsetTop(),
                    insets.getStableInsetTop()
            );
            int leftInset = Math.max(
                    insets.getSystemWindowInsetLeft(),
                    insets.getStableInsetLeft()
            );
            int rightInset = Math.max(
                    insets.getSystemWindowInsetRight(),
                    insets.getStableInsetRight()
            );
            int bottomInset = Math.max(
                    insets.getSystemWindowInsetBottom(),
                    insets.getStableInsetBottom()
            );
            hero.setPadding(
                    heroLeftPadding + leftInset,
                    heroTopPadding + topInset,
                    heroRightPadding + rightInset,
                    heroBottomPadding
            );
            body.setPadding(
                    bodyLeftPadding + leftInset,
                    bodyTopPadding,
                    bodyRightPadding + rightInset,
                    bodyBottomPadding
            );
            view.setPadding(0, 0, 0, bottomInset);
            return insets;
        });
        screen.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarAppearance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
            return;
        }

        View decorView = getWindow().getDecorView();
        int systemUiVisibility = decorView.getSystemUiVisibility();
        systemUiVisibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decorView.setSystemUiVisibility(systemUiVisibility);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null && !isBusy()) {
            updateModelState();
        }
    }

    private void openModelArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
        });
        startActivityForResult(intent, MODEL_ARCHIVE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri selectedUri = data.getData();
        if (requestCode == SAVE_IMAGE_REQUEST) {
            if (selectedUri != null) {
                saveGeneratedImage(selectedUri);
            }
            return;
        }
        if (requestCode != MODEL_ARCHIVE_REQUEST) {
            return;
        }
        if (selectedUri == null) {
            statusView.setText("No model archive was selected.");
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
        }
        importModelArchive(selectedUri);
    }

    private void importModelArchive(Uri archiveUri) {
        setImporting(true);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        statusView.setText("Checking the " + modelDescriptor.displayName() + " archive…");
        if (generatedBitmap == null) {
            resultCaption.setText(
                    "Checking and installing the " + modelDescriptor.displayName()
                            + " model on this device…"
            );
        }

        executor.submit(() -> {
            try {
                generationAdapter.importModel(
                        modelDescriptor,
                        archiveUri,
                        (message, percent) -> postToUi(() -> {
                            progressBar.setProgress(percent);
                            statusView.setText(message);
                            if (generatedBitmap == null) {
                                resultCaption.setText(message);
                            }
                        })
                );
                postToUi(() -> {
                    setImporting(false);
                    updateModelState();
                });
            } catch (Throwable error) {
                postToUi(() -> {
                    setImporting(false);
                    progressBar.setProgress(0);
                    updateModelState();
                    String detail = "Import failed: " + error.getClass().getSimpleName()
                            + ": " + error.getMessage();
                    String message;
                    if (modelReady) {
                        message = "Model update failed; the existing model is still available. "
                                + detail;
                    } else {
                        modelStatus.setText("Model setup failed");
                        message = detail;
                    }
                    statusView.setText(message);
                    if (!hasGeneratedImage()) {
                        resultCaption.setText(message);
                    }
                });
            }
        });
    }

    private void openSaveImageDialog() {
        if (!hasGeneratedImage()) {
            statusView.setText("Generate an image before saving it.");
            return;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, "tinysd-" + timestamp + ".png");
        startActivityForResult(intent, SAVE_IMAGE_REQUEST);
    }

    private void saveGeneratedImage(Uri outputUri) {
        Bitmap bitmap = generatedBitmap;
        if (bitmap == null || bitmap.isRecycled()) {
            statusView.setText("The generated image is no longer available.");
            return;
        }

        setSaving(true);
        statusView.setText("Saving image…");
        executor.submit(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("Could not encode the image as PNG");
                }
                output.flush();
                postToUi(() -> {
                    setSaving(false);
                    statusView.setText(
                            "Image saved. Choose another prompt or seed to create a variation."
                    );
                });
            } catch (Throwable error) {
                postToUi(() -> {
                    setSaving(false);
                    statusView.setText(
                            "Save failed: " + error.getClass().getSimpleName()
                                    + ": " + error.getMessage()
                    );
                });
            }
        });
    }

    private void startGeneration() {
        if (!memoryReady) {
            statusView.setText(memoryWarning());
            resultCaption.setText(memoryWarning());
            return;
        }

        if (!generationAdapter.isModelReady(modelDescriptor)) {
            updateModelState();
            return;
        }

        String promptText = promptInput.getText().toString().trim();
        if (promptText.isEmpty()) {
            promptInput.setError("Enter a description for the image");
            return;
        }

        long seed;
        try {
            seed = Long.parseLong(seedInput.getText().toString().trim());
        } catch (NumberFormatException exception) {
            seedInput.setError("Enter a whole-number seed");
            return;
        }

        setGenerating(true);
        clearGeneratedImage();
        progressBar.setProgress(0);
        String loadingMessage = "Loading the tokenizer and "
                + modelDescriptor.displayName() + " model…";
        statusView.setText(loadingMessage);
        resultCaption.setText(loadingMessage);

        executor.submit(() -> {
            try {
                ImageGenerationAdapter.GenerationResult result = generationAdapter.generate(
                        modelDescriptor,
                        promptText,
                        seed,
                        (message, completed, total) -> postToUi(() -> {
                            progressBar.setMax(total);
                            progressBar.setProgress(completed);
                            statusView.setText(message);
                            resultCaption.setText(message);
                        })
                );
                runOnUiThread(() -> {
                    if (destroyed) {
                        if (result.bitmap != null && !result.bitmap.isRecycled()) {
                            result.bitmap.recycle();
                        }
                        return;
                    }
                    generatedBitmap = result.bitmap;
                    imageView.setImageBitmap(result.bitmap);
                    resultCaption.setText(promptText);
                    resultMeta.setText(String.format(
                            Locale.US,
                            "Seed %d  •  Generated in %.1f seconds",
                            seed,
                            result.elapsedMs / 1000.0
                    ));
                    resultMeta.setVisibility(View.VISIBLE);
                    resultFooter.setVisibility(View.VISIBLE);
                    resultCard.setVisibility(View.VISIBLE);
                    setGenerating(false);
                    statusView.setText(
                            "Image ready. Try another prompt or seed to create a new variation."
                    );
                });
            } catch (OutOfMemoryError error) {
                postToUi(() -> showFailure(
                        "The emulator ran out of memory. Stop it, increase AVD RAM to 8 GB, and try again."
                ));
            } catch (Throwable error) {
                postToUi(() -> showFailure(
                        "Generation failed: " + error.getClass().getSimpleName()
                                + ": " + error.getMessage()
                ));
            }
        });
    }

    private void updateModelState() {
        modelReady = generationAdapter.isModelReady(modelDescriptor);
        importButton.setText(R.string.import_model);

        if (modelReady) {
            modelStatus.setText(memoryReady
                    ? modelDescriptor.displayName() + " is ready"
                    : "More device memory needed");
            statusView.setText(memoryReady
                    ? "Model ready. Enter a prompt and generate an image."
                    : memoryWarning());
        } else {
            modelStatus.setText(R.string.model_setup_needed);
            statusView.setText("Add " + modelDescriptor.archiveFileName() + " to continue.");
        }

        if (!hasGeneratedImage() && !generating && !importing) {
            if (modelReady && memoryReady) {
                resultCaption.setText(
                        modelDescriptor.displayName()
                                + " is ready. Enter a prompt and generate your first image."
                );
            } else if (modelReady) {
                resultCaption.setText(memoryWarning());
            } else {
                resultCaption.setText(R.string.initial_instructions);
            }
        }
        applyControlState();
    }

    private void showFailure(String message) {
        statusView.setText(message);
        resultCaption.setText(message);
        resultMeta.setVisibility(View.GONE);
        resultFooter.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        setGenerating(false);
    }

    private void postToUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    private void setGenerating(boolean value) {
        generating = value;
        applyControlState();
    }

    private void setImporting(boolean value) {
        importing = value;
        applyControlState();
    }

    private void setSaving(boolean value) {
        saving = value;
        applyControlState();
    }

    private boolean isBusy() {
        return generating || importing || saving;
    }

    private boolean hasGeneratedImage() {
        return generatedBitmap != null && !generatedBitmap.isRecycled();
    }

    private void applyControlState() {
        boolean busy = isBusy();
        promptInput.setEnabled(!busy);
        seedInput.setEnabled(!busy);
        generateButton.setEnabled(modelReady && memoryReady && !busy);
        importButton.setEnabled(!busy);
        saveButton.setEnabled(hasGeneratedImage() && !busy);
        progressBar.setVisibility(generating || importing ? View.VISIBLE : View.GONE);

        generateButton.setText(generating
                ? "Creating your image…"
                : getString(R.string.generate_image));
        importButton.setText(importing
                ? "Setting up model…"
                : getString(R.string.import_model));
        saveButton.setText(saving
                ? "Saving image…"
                : getString(R.string.save_image));
    }

    private void clearGeneratedImage() {
        imageView.setImageDrawable(null);
        if (hasGeneratedImage()) {
            generatedBitmap.recycle();
        }
        generatedBitmap = null;
        resultCard.setVisibility(View.GONE);
        resultMeta.setVisibility(View.GONE);
        resultFooter.setVisibility(View.GONE);
        applyControlState();
    }

    private long readDeviceMemoryBytes() {
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return 0;
        }
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.totalMem;
    }

    private String memoryWarning() {
        return String.format(
                Locale.US,
                "This AVD exposes %.1f GB of RAM. Set AVD RAM to 8192 MB and cold boot it before generating.",
                deviceMemoryBytes / (1024.0 * 1024.0 * 1024.0)
        );
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (adapterRegistry != null) {
            // Queue cleanup after any active import, save, or native inference call.
            executor.execute(adapterRegistry::close);
        }
        executor.shutdown();
        super.onDestroy();
    }
}
