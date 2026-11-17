#!/usr/bin/env python3
"""Run yomiyasu_check.py on filesystems without os.link (Termux/Android).

yomiyasu_check.py uses os.link for atomic file placement. Termux's Python has no
os.link, so the tool exits with AttributeError. This wrapper installs a
same-filesystem copy fallback for os.link and then calls the tool's main().

No yomiyasu source file is modified. The repository is expected at --repo (or
YOMIYASU_REPO); see training/specs/YOMIYASU_PROCEDURE.md for installation.

Usage:
  python3 training/tools/yomiyasu_compat.py --repo /path/to/yomiyasu \
      run --source src.md --final final.md --stance=説明
  python3 training/tools/yomiyasu_compat.py --repo /path/to/yomiyasu \
      verify --final final.md
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import shutil
import sys
from pathlib import Path


def install_link_fallback() -> None:
    """os.link -> copy when unavailable, preserving FileExistsError semantics."""
    if hasattr(os, "link"):
        return

    def _link(src, dst):
        if os.path.exists(dst):
            raise FileExistsError(dst)
        shutil.copyfile(src, dst)

    os.link = _link  # type: ignore[attr-defined]


def main() -> int:
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--repo", default=os.environ.get("YOMIYASU_REPO", ""))
    ap.add_argument("-h", "--help", action="store_true")
    known, rest = ap.parse_known_args()

    if known.help and not rest:
        print(__doc__)
        return 0

    if not known.repo:
        print("yomiyasu リポジトリを --repo または YOMIYASU_REPO で指定してください。", file=sys.stderr)
        return 2
    script = Path(known.repo) / "scripts" / "yomiyasu_check.py"
    if not script.is_file():
        print(f"見つかりません: {script}", file=sys.stderr)
        return 2

    install_link_fallback()
    spec = importlib.util.spec_from_file_location("yomiyasu_check", script)
    if spec is None or spec.loader is None:
        print("モジュールを読み込めませんでした", file=sys.stderr)
        return 2
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    sys.argv = ["yomiyasu_check.py", *rest]
    return int(module.main())


if __name__ == "__main__":
    sys.exit(main())
