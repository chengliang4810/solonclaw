#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import argparse
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
EXPECTED_GHCR_IMAGE = "ghcr.io/chengliang4810/solon-claw"


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
    unexpected_images = [image for image in ghcr_images if image != EXPECTED_GHCR_IMAGE]

    if unexpected_images:
        print("GitHub Packages workflow must publish only the solon-claw image.", file=sys.stderr)
        for image in unexpected_images:
            print(f"unexpected image: {image}", file=sys.stderr)
        return 1

    if EXPECTED_GHCR_IMAGE not in ghcr_images:
        print(f"GitHub Packages workflow does not publish {EXPECTED_GHCR_IMAGE}.", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
