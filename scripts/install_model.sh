#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 /path/to/tinysd_vivo_executorch.zip" >&2
    exit 1
fi

archive=$1
if [[ ! -f "$archive" ]]; then
    echo "Model archive not found: $archive" >&2
    exit 1
fi

if [[ -n "${ADB:-}" ]]; then
    adb_command=$ADB
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    adb_command="$ANDROID_HOME/platform-tools/adb"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
    adb_command="$HOME/Library/Android/sdk/platform-tools/adb"
else
    adb_command=adb
fi

if ! command -v "$adb_command" >/dev/null 2>&1 && [[ ! -x "$adb_command" ]]; then
    echo "adb was not found. Set ANDROID_HOME or ADB and try again." >&2
    exit 1
fi

if [[ "$($adb_command get-state 2>/dev/null || true)" != "device" ]]; then
    echo "No running Android device was found. Start the emulator and try again." >&2
    exit 1
fi

package_name=org.arm.learningpath.tinysdstudio
if ! $adb_command shell pm path "$package_name" >/dev/null 2>&1; then
    echo "TinySD Studio is not installed. Run the app once from Android Studio, then retry." >&2
    exit 1
fi

model_entry=$(unzip -Z1 "$archive" | grep -m1 '/huggingface/optimized.pte$' || true)
schedule_entry=$(unzip -Z1 "$archive" | grep -m1 '/huggingface/schedule_data.json$' || true)
tokenizer_entry=$(unzip -Z1 "$archive" | grep -m1 '/tokenizer/tokenizer.json$' || true)
if [[ -z "$model_entry" || -z "$schedule_entry" || -z "$tokenizer_entry" ]]; then
    echo "The archive does not contain optimized.pte, schedule_data.json, and tokenizer.json." >&2
    exit 1
fi

temporary_directory=$(mktemp -d)
trap 'rm -rf "$temporary_directory"' EXIT

echo "Extracting the optimized TinySD artifact…"
unzip -p "$archive" "$model_entry" > "$temporary_directory/optimized.pte"
unzip -p "$archive" "$schedule_entry" > "$temporary_directory/schedule_data.json"
unzip -p "$archive" "$tokenizer_entry" > "$temporary_directory/tokenizer.json"

remote_directory="/sdcard/Android/data/$package_name/files/tinysd"
$adb_command shell mkdir -p "$remote_directory"

echo "Copying the model to the emulator…"
$adb_command push "$temporary_directory/optimized.pte" "$remote_directory/optimized.pte"
$adb_command push "$temporary_directory/schedule_data.json" "$remote_directory/schedule_data.json"
$adb_command push "$temporary_directory/tokenizer.json" "$remote_directory/tokenizer.json"

echo
echo "TinySD model installed in:"
echo "$remote_directory"
echo "Return to TinySD Studio, enter a prompt, and select Generate image."
