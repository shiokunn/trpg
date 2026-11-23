#!/usr/bin/env python3
"""Detect simplified-Chinese characters inside Japanese training JSONL files.

Only reads files; never writes to the files it inspects. Scans training/raw/
recursively by default and reports file name, line number, character and the
surrounding text. Results can be emitted as JSON, with counts per file and per
character.

Design notes
------------
The character set below contains forms that are *not* ordinary Japanese kanji.
Characters that are valid Japanese (例: 来, 近, 里, 与, 図, 実, 芸, 国, 車) are
deliberately excluded, so a Japanese sentence does not trigger a finding.

Three ambiguous characters need context handling:
  * 采  -> usually simplified 採 (採用 / 采り). But 采配 and 采女 are legitimate
          Japanese words and are whitelisted.
  * 后  -> simplified 後. No common Japanese use in this corpus.
  * 并  -> simplified 並. No common Japanese use in this corpus.

The characters discovered in this project (误 / 录 / 采) are all included.

Usage:
  python3 training/tools/check_simplified_chinese.py
  python3 training/tools/check_simplified_chinese.py --json
  python3 training/tools/check_simplified_chinese.py --json --out report.json
  python3 training/tools/check_simplified_chinese.py --files /tmp/test/*.jsonl
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parent.parent / "raw"

# Simplified-only forms. Japanese kanji with the same shape are NOT listed here.
SIMPLIFIED_ONLY = set(
    "测历认说话违记忆报查储误录门问间经给过还进远边书长东车马鸟鱼"
    "众达岁术们个为这时间见觉开关产发验义艺电资讯转实图团园围采"
    "办师带灭压厌圣对顺则别总练线组细织终统绝观规视设证评识"
    "课调谈谊贝财购贵贸贷费资贼质贤卖赞赵转轮软轻载递适选遗"
    "镜钟钟银钱铜铁铝锁链铺锐师绘纸纹纷纱纯纲纳纵纷纹练细织"
)

# Characters that are sometimes Japanese: detected only outside these words.
CONTEXT_WHITELIST: dict[str, tuple[str, ...]] = {
    "采": ("采配", "采女"),
}

# Human-readable suggested replacements for the characters we care most about.
SUGGESTIONS = {
    "误": "誤", "录": "録", "采": "採", "记": "記", "忆": "憶",
    "报": "報", "查": "査", "储": "儲", "违": "違", "测": "測",
    "历": "歴", "认": "認", "说": "説", "话": "話", "铁": "鉄",
}


def is_finding(char: str, line: str, index: int) -> bool:
    """Context-aware filter to reduce false positives on valid Japanese words."""
    for word in CONTEXT_WHITELIST.get(char, ()):
        start = index - len(word) + 1
        if start >= 0 and line[start:index + 1] == word:
            return False
        if line[index:index + len(word)] == word:
            return False
    return True


def context(line: str, index: int, width: int = 24) -> str:
    start = max(0, index - width)
    end = min(len(line), index + width + 1)
    return line[start:end].replace("\n", "")


def scan_files(files: list[Path]) -> list[dict]:
    findings: list[dict] = []
    for path in sorted(files):
        if not path.is_file():
            continue
        with path.open(encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                for index, char in enumerate(line):
                    if char not in SIMPLIFIED_ONLY:
                        continue
                    if not is_finding(char, line, index):
                        continue
                    findings.append({
                        "file": str(path),
                        "line": lineno,
                        "column": index + 1,
                        "char": char,
                        "suggested": SUGGESTIONS.get(char, ""),
                        "context": context(line, index),
                    })
    return findings


def aggregate(findings: list[dict]) -> dict:
    by_file: dict[str, Counter] = defaultdict(Counter)
    by_char: Counter = Counter()
    for item in findings:
        by_file[item["file"]][item["char"]] += 1
        by_char[item["char"]] += 1
    return {
        "by_file": {f: dict(sorted(c.items())) for f, c in sorted(by_file.items())},
        "by_char": dict(sorted(by_char.items())),
        "total": len(findings),
    }


def collect_files(args) -> list[Path]:
    if args.files:
        return [Path(p) for p in args.files]
    return sorted(args.root.rglob("*.jsonl"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", type=Path, default=DEFAULT_ROOT,
                    help="directory scanned recursively (default: training/raw)")
    ap.add_argument("--files", nargs="+", default=None,
                    help="explicit files to scan instead of --root")
    ap.add_argument("--json", action="store_true", help="emit a JSON report")
    ap.add_argument("--out", type=Path, default=None,
                    help="write the JSON report to this NEW file (never overwrites)")
    args = ap.parse_args()

    files = collect_files(args)
    findings = scan_files(files)
    report = {
        "scanned_files": len([p for p in files if p.is_file()]),
        "characters_checked": len(SIMPLIFIED_ONLY | set(CONTEXT_WHITELIST)),
        "findings": findings,
        "summary": aggregate(findings),
    }

    if args.out:
        if args.out.exists():
            print(f"出力先が既に存在します: {args.out}", file=sys.stderr)
            return 2
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"走査ファイル: {report['scanned_files']}")
        print(f"検出: {report['summary']['total']}件")
        for item in findings:
            path = Path(item["file"]).name
            print(f"{path}:{item['line']}:{item['column']} [{item['char']}] "
                  f"…{item['context']}…")
        print("--- ファイル別件数")
        for path, counts in report["summary"]["by_file"].items():
            print(f"  {Path(path).name}: {dict(counts)}")
        print("--- 文字別件数")
        print("  " + dict(report["summary"]["by_char"]).__str__())

    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
