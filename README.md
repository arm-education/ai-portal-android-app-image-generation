# TinySD Studio

TinySD Studio is the starter Android application for the Arm Learning Path that runs an optimized TinySD ExecuTorch model on an Arm64 Android emulator.

The model is not included in this project. Build and run the app, create
`tinysd_vivo_executorch.zip` with the supplied downloader, copy it to the Android
`Downloads` directory, then select **Import TinySD model** in the app.

Create a Python environment and install the Hugging Face Hub package:

```bash
python3 -m venv .hf-venv
source .hf-venv/bin/activate
python -m pip install --upgrade huggingface_hub
```

Download the required model artifacts and package them in the uncompressed ZIP
layout expected by the Android importer:

```bash
MODEL_FILE="$(python download_model.py \
  --repo-id Arm/tiny-sd-int8-xnnpack-executorch-vivo-x300 \
  --print-path)"
```

If the Hugging Face repository is private, authenticate first with `hf auth login`.

The terminal installer remains available as an alternative:

```bash
./scripts/install_model.sh /path/to/tinysd_vivo_executorch.zip
```

The application expects an Arm64 Android emulator with at least 8 GB of RAM. It tokenizes free-text prompts with the model's CLIP tokenizer, loads `optimized.pte` with memory mapping, runs the `text_encoder`, `unet`, and `vae_decoder` methods, displays the generated 512 × 512 bitmap, and saves the result as PNG through Android's document picker.
