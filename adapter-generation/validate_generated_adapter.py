#!/usr/bin/env python3

import argparse
import re
from pathlib import Path


PLACEHOLDER_PATTERN = re.compile(
    r"<(?:MODEL|RUNTIME|ADAPTER|PLACEHOLDER|TODO)[A-Z0-9_-]*>"
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Check the files produced for a generated Android image adapter."
    )
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--layout", type=Path)
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parent.parent
    registry = project_root / (
        "app/src/main/java/org/arm/learningpath/tinysdstudio/"
        "GeneratedAdapterRegistry.java"
    )
    dependencies = project_root / "app/generated-runtime-dependencies.gradle.kts"
    adapter_path = (
        args.adapter if args.adapter.is_absolute() else project_root / args.adapter
    )
    paths = [adapter_path, registry, dependencies]
    if args.layout:
        paths.append(
            args.layout if args.layout.is_absolute() else project_root / args.layout
        )

    errors = []
    for path in paths:
        if not path.is_file():
            errors.append(f"Missing file: {path}")
            if path == adapter_path:
                supplied_adapters = {"TinySdImageGenerationAdapter.java"}
                candidates = []
                for candidate in sorted(registry.parent.glob("*.java")):
                    if candidate.name in supplied_adapters:
                        continue
                    source = candidate.read_text(encoding="utf-8")
                    if "implements ImageGenerationAdapter" in source:
                        candidates.append(candidate.relative_to(project_root).as_posix())
                if candidates:
                    errors.append(
                        "Possible generated adapter files: " + ", ".join(candidates)
                    )
            elif args.layout and path == (
                args.layout
                if args.layout.is_absolute()
                else project_root / args.layout
            ):
                errors.append(
                    "Omit --layout when the generated adapter does not add a layout file"
                )
            continue
        content = path.read_text(encoding="utf-8")
        if PLACEHOLDER_PATTERN.search(content):
            errors.append(f"Unresolved placeholder in {path}")

    if adapter_path.is_file():
        adapter_source = adapter_path.read_text(encoding="utf-8")
        if "implements ImageGenerationAdapter" not in adapter_source:
            errors.append("The generated class must implement ImageGenerationAdapter")
        if "model.runtimeName()" in adapter_source:
            errors.append(
                "Do not validate runtime compatibility using runtimeName; "
                "it is presentation text"
            )

    if registry.is_file():
        registry_source = registry.read_text(encoding="utf-8")
        if "return List.of();" in registry_source:
            errors.append(
                "GeneratedAdapterRegistry must register one adapter and one active model"
            )

    if errors:
        print("Generated adapter validation failed:")
        for error in errors:
            print(f"- {error}")
        raise SystemExit(1)

    print("Generated adapter file validation passed")
    print("Run the Gradle build, lint, and on-device model test next")


if __name__ == "__main__":
    main()
