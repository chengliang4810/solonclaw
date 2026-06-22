#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from guardlib import GITHUB_PACKAGE_IMAGE, PROJECT_NAME, REPO_ROOT


def main() -> int:
    parser = argparse.ArgumentParser(description="Check GitHub Packages image naming.")
    parser.add_argument("--root-path", default=str(REPO_ROOT))
    args = parser.parse_args()

    packages_workflow = Path(args.root_path).resolve() / ".github" / "workflows" / "packages.yml"
    if not packages_workflow.exists():
        print(f"Packages workflow not found: {packages_workflow}", file=sys.stderr)
        return 1

    workflow_text = packages_workflow.read_text(encoding="utf-8")
    ghcr_images = sorted(set(re.findall(r"ghcr\.io/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", workflow_text)))
    unexpected_images = [image for image in ghcr_images if image != GITHUB_PACKAGE_IMAGE]

    if unexpected_images:
        print(f"GitHub Packages workflow must publish only the {PROJECT_NAME} image.", file=sys.stderr)
        for image in unexpected_images:
            print(f"unexpected image: {image}", file=sys.stderr)
        return 1

    if GITHUB_PACKAGE_IMAGE not in ghcr_images:
        print(f"GitHub Packages workflow does not publish {GITHUB_PACKAGE_IMAGE}.", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
