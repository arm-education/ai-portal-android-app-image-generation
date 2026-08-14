package org.arm.learningpath.tinysdstudio;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int MODEL_ARCHIVE_REQUEST = 1001;
    private static final int SAVE_IMAGE_REQUEST = 1002;
    private static final long MINIMUM_RAM_BYTES = 7L * 1024 * 1024 * 1024;
    private static final String MODEL_NAME = "optimized.pte";
    private static final String SCHEDULE_NAME = "schedule_data.json";
    private static final int BACKGROUND = Color.rgb(246, 247, 251);
    private static final int SURFACE = Color.WHITE;
    private static final int PRIMARY = Color.rgb(104, 74, 255);
    private static final int PRIMARY_DARK = Color.rgb(76, 51, 211);
    private static final int TEXT = Color.rgb(36, 30, 48);
    private static final int MUTED = Color.rgb(112, 104, 124);
    private static final int BORDER = Color.rgb(229, 226, 236);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private File modelDirectory;
    private TinySdRunner runner;
    private ClipTokenizer tokenizer;
    private EditText promptInput;
    private EditText seedInput;
    private Button generateButton;
    private Button importButton;
    private Button saveButton;
    private ProgressBar progressBar;
    private TextView statusView;
    private LinearLayout resultCard;
    private TextView resultCaption;
    private TextView resultMeta;
    private ImageView imageView;
    private Bitmap generatedBitmap;
    private boolean generating;
    private boolean importing;
    private boolean saving;
    private boolean modelReady;
    private long deviceMemoryBytes;
    private boolean memoryReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        File externalFiles = getExternalFilesDir(null);
        modelDirectory = new File(externalFiles != null ? externalFiles : getFilesDir(), "tinysd");
        deviceMemoryBytes = readDeviceMemoryBytes();
        memoryReady = deviceMemoryBytes >= MINIMUM_RAM_BYTES;

        setContentView(createContent());
        updateModelState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null && !generating && !importing && !saving) {
            updateModelState();
        }
    }

    private ScrollView createContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BACKGROUND);
        scrollView.setClipToPadding(true);
        scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = Math.max(
                    insets.getSystemWindowInsetTop(),
                    insets.getStableInsetTop()
            );
            int bottomInset = Math.max(
                    insets.getSystemWindowInsetBottom(),
                    insets.getStableInsetBottom()
            );
            view.setPadding(0, topInset, 0, bottomInset);
            return insets;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(40));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        content.addView(createHeader());
        content.addView(createComposerCard(), matchWrap(dp(22)));
        content.addView(createStatusCard(), matchWrap(dp(14)));

        TextView feedLabel = label("YOUR CREATION");
        feedLabel.setPadding(dp(4), 0, 0, 0);
        content.addView(feedLabel, matchWrap(dp(24)));

        resultCard = createResultCard();
        resultCard.setVisibility(View.GONE);
        content.addView(resultCard, matchWrap(dp(10)));

        TextView privacy = text("Your prompt and image stay on this device.", 13, MUTED);
        privacy.setGravity(Gravity.CENTER);
        content.addView(privacy, matchWrap(dp(22)));

        return scrollView;
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView avatar = avatar("TS", PRIMARY, 46);
        header.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        header.addView(titles, titleParams);

        TextView title = text("TinySD Studio", 23, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(title);

        TextView subtitle = text("Create images on-device", 14, MUTED);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle);

        TextView localBadge = text("LOCAL AI", 11, PRIMARY_DARK);
        localBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        localBadge.setGravity(Gravity.CENTER);
        localBadge.setPadding(dp(11), dp(7), dp(11), dp(7));
        localBadge.setBackground(roundedDrawable(Color.rgb(237, 233, 255), 30, 0, Color.TRANSPARENT));
        header.addView(localBadge);

        return header;
    }

    private LinearLayout createComposerCard() {
        LinearLayout card = card();

        TextView eyebrow = label("CREATE A POST");
        card.addView(eyebrow);

        TextView heading = text("What do you want to imagine?", 21, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading, matchWrap(dp(8)));

        TextView description = text("Describe the scene, style, lighting, and details.", 14, MUTED);
        description.setLineSpacing(0, 1.15f);
        card.addView(description, matchWrap(dp(5)));

        promptInput = new EditText(this);
        promptInput.setHint("A watercolor lighthouse on a cliff at sunset");
        promptInput.setHintTextColor(Color.rgb(154, 147, 164));
        promptInput.setTextColor(TEXT);
        promptInput.setTextSize(16);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        promptInput.setMinLines(3);
        promptInput.setMaxLines(5);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        promptInput.setPadding(dp(15), dp(13), dp(15), dp(13));
        promptInput.setBackground(roundedDrawable(Color.rgb(250, 249, 252), 16, 1, BORDER));
        card.addView(promptInput, matchWrap(dp(18)));

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(options, matchWrap(dp(14)));

        LinearLayout seedText = new LinearLayout(this);
        seedText.setOrientation(LinearLayout.VERTICAL);
        options.addView(seedText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView seedLabel = text("Variation seed", 14, TEXT);
        seedLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        seedText.addView(seedLabel);
        seedText.addView(text("Reuse a seed to repeat a result", 12, MUTED), matchWrap(dp(2)));

        seedInput = new EditText(this);
        seedInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        seedInput.setText("42");
        seedInput.setTextColor(TEXT);
        seedInput.setTextSize(15);
        seedInput.setGravity(Gravity.CENTER);
        seedInput.setSingleLine(true);
        seedInput.setPadding(dp(8), dp(8), dp(8), dp(8));
        seedInput.setBackground(roundedDrawable(Color.rgb(250, 249, 252), 24, 1, BORDER));
        options.addView(seedInput, new LinearLayout.LayoutParams(dp(84), dp(44)));

        generateButton = new Button(this);
        generateButton.setText("Generate image");
        generateButton.setTextColor(Color.WHITE);
        generateButton.setTextSize(16);
        generateButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        generateButton.setAllCaps(false);
        generateButton.setGravity(Gravity.CENTER);
        generateButton.setPadding(dp(16), 0, dp(16), 0);
        generateButton.setBackground(roundedDrawable(PRIMARY, 18, 0, Color.TRANSPARENT));
        generateButton.setOnClickListener(view -> startGeneration());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        buttonParams.topMargin = dp(20);
        card.addView(generateButton, buttonParams);

        return card;
    }

    private LinearLayout createStatusCard() {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(15), dp(18), dp(15));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(titleRow);

        TextView dot = text("•", 25, PRIMARY);
        dot.setGravity(Gravity.CENTER);
        titleRow.addView(dot, new LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("On-device generation", 14, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(27);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(232, 229, 240)));
        card.addView(progressBar, matchWrap(dp(9)));

        statusView = text("Checking model files…", 13, MUTED);
        statusView.setLineSpacing(0, 1.15f);
        card.addView(statusView, matchWrap(dp(7)));

        importButton = new Button(this);
        importButton.setText("Import TinySD model");
        importButton.setTextColor(PRIMARY_DARK);
        importButton.setTextSize(14);
        importButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        importButton.setAllCaps(false);
        importButton.setBackground(roundedDrawable(Color.rgb(245, 242, 255), 16, 1, Color.rgb(211, 202, 255)));
        importButton.setOnClickListener(view -> openModelArchive());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        importParams.topMargin = dp(13);
        card.addView(importButton, importParams);
        return card;
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
        int permissionFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(selectedUri, permissionFlags);
        } catch (SecurityException ignored) {
        }
        importModelArchive(selectedUri);
    }

    private void importModelArchive(Uri archiveUri) {
        setImporting(true);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        statusView.setText("Checking the TinySD archive…");

        executor.submit(() -> {
            try {
                ModelImporter.importArchive(
                        getContentResolver(),
                        archiveUri,
                        modelDirectory,
                        (message, percent) -> runOnUiThread(() -> {
                            progressBar.setProgress(percent);
                            statusView.setText(message);
                        })
                );
                runOnUiThread(() -> {
                    setImporting(false);
                    updateModelState();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    setImporting(false);
                    progressBar.setProgress(0);
                    statusView.setText(
                            "Import failed: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                    );
                });
            }
        });
    }

    private LinearLayout createResultCard() {
        LinearLayout card = card();
        card.setPadding(0, 0, 0, dp(17));

        LinearLayout authorRow = new LinearLayout(this);
        authorRow.setOrientation(LinearLayout.HORIZONTAL);
        authorRow.setGravity(Gravity.CENTER_VERTICAL);
        authorRow.setPadding(dp(17), dp(16), dp(17), dp(14));
        card.addView(authorRow);

        authorRow.addView(avatar("TS", PRIMARY, 38), new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout authorText = new LinearLayout(this);
        authorText.setOrientation(LinearLayout.VERTICAL);
        authorText.setPadding(dp(10), 0, 0, 0);
        authorRow.addView(authorText);

        TextView author = text("TinySD Studio", 15, TEXT);
        author.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        authorText.addView(author);
        authorText.addView(text("Generated privately on this device", 12, MUTED), matchWrap(dp(1)));

        imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackgroundColor(Color.rgb(232, 229, 238));
        imageView.setMinimumHeight(dp(320));
        card.addView(imageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        resultCaption = text("", 15, TEXT);
        resultCaption.setLineSpacing(0, 1.15f);
        resultCaption.setPadding(dp(17), dp(15), dp(17), 0);
        card.addView(resultCaption);

        resultMeta = text("", 12, MUTED);
        resultMeta.setPadding(dp(17), dp(8), dp(17), 0);
        card.addView(resultMeta);

        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        dividerParams.setMargins(dp(17), dp(15), dp(17), 0);
        card.addView(divider, dividerParams);

        TextView footer = text("512 × 512  •  ExecuTorch + XNNPACK", 12, PRIMARY_DARK);
        footer.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        footer.setPadding(dp(17), dp(13), dp(17), 0);
        card.addView(footer);

        saveButton = new Button(this);
        saveButton.setText("Save image");
        saveButton.setTextColor(PRIMARY_DARK);
        saveButton.setTextSize(14);
        saveButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        saveButton.setAllCaps(false);
        saveButton.setEnabled(false);
        saveButton.setAlpha(0.55f);
        saveButton.setBackground(roundedDrawable(Color.rgb(245, 242, 255), 16, 1, Color.rgb(211, 202, 255)));
        saveButton.setOnClickListener(view -> openSaveImageDialog());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        saveParams.setMargins(dp(17), dp(15), dp(17), 0);
        card.addView(saveButton, saveParams);
        return card;
    }

    private void openSaveImageDialog() {
        if (generatedBitmap == null || generatedBitmap.isRecycled()) {
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
                runOnUiThread(() -> {
                    setSaving(false);
                    statusView.setText("Image saved. Choose another prompt or seed to create a variation.");
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    setSaving(false);
                    statusView.setText(
                            "Save failed: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                    );
                });
            }
        });
    }

    private void startGeneration() {
        if (!memoryReady) {
            statusView.setText(memoryWarning());
            return;
        }
        File modelFile = new File(modelDirectory, MODEL_NAME);
        File scheduleFile = new File(modelDirectory, SCHEDULE_NAME);
        File tokenizerFile = new File(modelDirectory, "tokenizer.json");
        if (!modelFile.isFile() || !scheduleFile.isFile() || !tokenizerFile.isFile()) {
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
        resultCard.setVisibility(View.GONE);
        progressBar.setProgress(0);
        statusView.setText("Loading the tokenizer and TinySD model…");

        executor.submit(() -> {
            try {
                if (runner == null) {
                    runner = new TinySdRunner(modelFile, scheduleFile);
                }
                if (tokenizer == null) {
                    tokenizer = new ClipTokenizer(tokenizerFile);
                }
                long[] promptTokens = tokenizer.encode(promptText);
                long[] unconditionalTokens = tokenizer.encode("");
                TinySdRunner.GenerationResult result = runner.generate(
                        promptTokens,
                        unconditionalTokens,
                        seed,
                        (message, completed, total) -> runOnUiThread(() -> {
                            progressBar.setMax(total);
                            progressBar.setProgress(completed);
                            statusView.setText(message);
                        })
                );
                runOnUiThread(() -> {
                    generatedBitmap = result.bitmap;
                    imageView.setImageBitmap(result.bitmap);
                    resultCaption.setText(promptText);
                    resultMeta.setText(String.format(
                            Locale.US,
                            "Seed %d  •  Generated in %.1f seconds",
                            seed,
                            result.elapsedMs / 1000.0
                    ));
                    resultCard.setVisibility(View.VISIBLE);
                    saveButton.setEnabled(true);
                    saveButton.setAlpha(1.0f);
                    statusView.setText("Image ready. Try another prompt or seed to create a new variation.");
                    setGenerating(false);
                });
            } catch (OutOfMemoryError error) {
                runOnUiThread(() -> showFailure(
                        "The emulator ran out of memory. Stop it, increase AVD RAM to 8 GB, and try again."
                ));
            } catch (Throwable error) {
                runOnUiThread(() -> showFailure(
                        "Generation failed: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                ));
            }
        });
    }

    private void updateModelState() {
        File modelFile = new File(modelDirectory, MODEL_NAME);
        File scheduleFile = new File(modelDirectory, SCHEDULE_NAME);
        File tokenizerFile = new File(modelDirectory, "tokenizer.json");
        modelReady = modelFile.isFile() && scheduleFile.isFile() && tokenizerFile.isFile();
        boolean canGenerate = modelReady && memoryReady && !generating && !importing && !saving;
        generateButton.setEnabled(canGenerate);
        generateButton.setAlpha(canGenerate ? 1.0f : 0.55f);
        importButton.setVisibility(modelReady ? View.GONE : View.VISIBLE);
        importButton.setEnabled(!importing);
        if (modelReady) {
            statusView.setText(memoryReady
                    ? "Model ready. Enter a prompt and generate an image."
                    : memoryWarning());
        } else {
            statusView.setText(
                    "Model not installed. Import tinysd_vivo_executorch.zip to continue."
            );
        }
    }

    private void showFailure(String message) {
        statusView.setText(message);
        setGenerating(false);
    }

    private void setGenerating(boolean value) {
        generating = value;
        promptInput.setEnabled(!value);
        seedInput.setEnabled(!value);
        boolean canGenerate = modelReady && memoryReady && !value && !importing && !saving;
        generateButton.setEnabled(canGenerate);
        generateButton.setAlpha(value ? 0.65f : (canGenerate ? 1.0f : 0.55f));
        generateButton.setText(value ? "Creating your image…" : "Generate image");
    }

    private void setImporting(boolean value) {
        importing = value;
        promptInput.setEnabled(!value);
        seedInput.setEnabled(!value);
        boolean canGenerate = modelReady && memoryReady && !value && !generating && !saving;
        generateButton.setEnabled(canGenerate);
        generateButton.setAlpha(canGenerate ? 1.0f : 0.55f);
        importButton.setEnabled(!value);
        importButton.setText(value ? "Importing model…" : "Import TinySD model");
    }

    private void setSaving(boolean value) {
        saving = value;
        promptInput.setEnabled(!value);
        seedInput.setEnabled(!value);
        boolean canGenerate = modelReady && memoryReady && !value && !generating && !importing;
        generateButton.setEnabled(canGenerate);
        generateButton.setAlpha(canGenerate ? 1.0f : 0.55f);
        saveButton.setEnabled(!value && generatedBitmap != null && !generatedBitmap.isRecycled());
        saveButton.setAlpha(saveButton.isEnabled() ? 1.0f : 0.55f);
        saveButton.setText(value ? "Saving image…" : "Save image");
    }

    private void clearGeneratedImage() {
        imageView.setImageDrawable(null);
        if (generatedBitmap != null && !generatedBitmap.isRecycled()) {
            generatedBitmap.recycle();
        }
        generatedBitmap = null;
        saveButton.setEnabled(false);
        saveButton.setAlpha(0.55f);
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

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundedDrawable(SURFACE, 22, 1, Color.rgb(238, 236, 242)));
        card.setElevation(dp(2));
        return card;
    }

    private TextView avatar(String value, int color, int sizeDp) {
        TextView avatar = text(value, sizeDp > 40 ? 15 : 13, Color.WHITE);
        avatar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(roundedDrawable(color, sizeDp, 0, Color.TRANSPARENT));
        return avatar;
    }

    private TextView label(String value) {
        TextView label = text(value, 11, PRIMARY_DARK);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setLetterSpacing(0.12f);
        return label;
    }

    private GradientDrawable roundedDrawable(int fillColor, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (runner != null) {
            runner.close();
        }
        super.onDestroy();
    }
}
