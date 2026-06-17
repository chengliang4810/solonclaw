#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path

from guardlib import (
    GuardFailure,
    fetch_github_release,
    scan_directory,
    temp_prefix,
)


def release_snapshot(release: dict) -> dict:
    assets = []
    for asset in release.get("assets") or []:
        if asset is None:
            continue
        assets.append(
            {
                "name": asset.get("name"),
                "label": asset.get("label"),
                "content_type": asset.get("content_type"),
            }
        )
    return {
        "name": release.get("name"),
        "tag_name": release.get("tag_name"),
        "body": release.get("body"),
        "assets": assets,
    }


def read_release(args: argparse.Namespace) -> dict:
    if args.local_json_path:
        local_path = Path(args.local_json_path)
        if not local_path.exists():
            raise GuardFailure(f"Local release JSON was not found: {local_path}")
        return json.loads(local_path.read_text(encoding="utf-8"))
    return fetch_github_release(args.repository, args.tag, args.token, args.api_base_url)


def main() -> int:
    parser = argparse.ArgumentParser(description="Check published release metadata naming.")
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--tag", default="")
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN", ""))
    parser.add_argument("--api-base-url", default="https://api.github.com")
    parser.add_argument("--local-json-path", default="")
    args = parser.parse_args()

    release = read_release(args)
    with tempfile.TemporaryDirectory(prefix=temp_prefix("release-naming")) as temp_dir:
        snapshot_path = Path(temp_dir) / "release.json"
        snapshot_path.write_text(json.dumps(release_snapshot(release), ensure_ascii=False, indent=2), encoding="utf-8")
        findings = scan_directory(Path(temp_dir))
        if findings:
            raise GuardFailure("Release naming guard failed for published release metadata.\n" + "\n".join(findings))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardFailure as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
