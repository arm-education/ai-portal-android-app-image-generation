#!/usr/bin/env python3
"""Download and package the TinySD artifacts expected by TinySD Studio."""

from __future__ import annotations

import argparse
import os
import sys
import zipfile
from pathlib import Path


DEFAULT_REPO_ID = "Arm/tiny-sd-int8-xnnpack-executorch-vivo-x300"
DEFAULT_ARCHIVE_NAME = "tinysd_vivo_executorch.zip"
ARTIFACTS = (
    ("tiny-sd-int8-executorch.pte", "huggingface/optimized.pte"),
    ("schedule_data.json", "huggingface/schedule_data.json"),
    ("tokenizer/tokenizer.json", "tokenizer/tokenizer.json"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Download TinySD from Hugging Face and create the uncompressed ZIP "
            "accepted by TinySD Studio."
        )
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument(
        "--repo-id",
        help=f"Hugging Face repository ID (default: {DEFAULT_REPO_ID})",
    )
    source.add_argument(
        "--local-dir",
        type=Path,
        help="Package an existing local model repository instead of downloading it",
    )
    parser.add_argument(
        "--revision",
        default="main",
        help="Hugging Face revision to download (default: main)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "downloads" / DEFAULT_ARCHIVE_NAME,
        help="Output ZIP path",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Replace an existing output archive",
    )
    parser.add_argument(
        "--print-path",
        action="store_true",
        help="Print only the absolute output path after success",
    )
    return parser.parse_args()


def resolve_artifacts(args: argparse.Namespace) -> list[tuple[Path, str]]:
    resolved: list[tuple[Path, str]] = []
    if args.local_dir is not None:
        root = args.local_dir.expanduser().resolve()
        for source_name, archive_name in ARTIFACTS:
            resolved.append((root / Path(source_name), archive_name))
    else:
        try:
            from huggingface_hub import hf_hub_download
        except ImportError as error:
            raise RuntimeError(
                "huggingface_hub is required. Install it with: "
                "python -m pip install --upgrade huggingface_hub"
            ) from error

        repo_id = args.repo_id or DEFAULT_REPO_ID
        for source_name, archive_name in ARTIFACTS:
            downloaded = hf_hub_download(
                repo_id=repo_id,
                filename=source_name,
                revision=args.revision,
            )
            resolved.append((Path(downloaded), archive_name))

    missing = [str(path) for path, _ in resolved if not path.is_file()]
    if missing:
        raise FileNotFoundError("Required TinySD artifacts are missing:\n" + "\n".join(missing))
    if resolved[0][0].stat().st_size < 100 * 1024 * 1024:
        raise ValueError("The TinySD .pte file is unexpectedly small or incomplete")
    return resolved


def validate_archive(path: Path) -> None:
    expected = {archive_name for _, archive_name in ARTIFACTS}
    with zipfile.ZipFile(path, "r") as archive:
        entries = {entry.filename: entry for entry in archive.infolist()}
        if set(entries) != expected:
            raise ValueError("The generated ZIP does not contain the expected TinySD entries")
        for name in expected:
            entry = entries[name]
            if entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"Required entry is compressed: {name}")
            if entry.file_size == 0:
                raise ValueError(f"Required entry is empty: {name}")


def build_archive(
    artifacts: list[tuple[Path, str]],
    output: Path,
    force: bool,
) -> Path:
    output = output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    if output.exists() and not force:
        validate_archive(output)
        return output

    temporary = output.with_name(output.name + ".partial")
    try:
        if temporary.exists():
            temporary.unlink()
        with zipfile.ZipFile(
            temporary,
            mode="w",
            compression=zipfile.ZIP_STORED,
            allowZip64=True,
        ) as archive:
            for source, archive_name in artifacts:
                archive.write(source, arcname=archive_name, compress_type=zipfile.ZIP_STORED)
        validate_archive(temporary)
        os.replace(temporary, output)
    finally:
        if temporary.exists():
            temporary.unlink()
    return output


def main() -> int:
    args = parse_args()
    try:
        artifacts = resolve_artifacts(args)
        output = build_archive(artifacts, args.output, args.force)
    except Exception as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1

    if args.print_path:
        print(output)
    else:
        print(f"TinySD Android model archive: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
