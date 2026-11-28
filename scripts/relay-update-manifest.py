#!/usr/bin/env python3
"""生成伴机自动升级清单 relay-update.json（仅本地生成，人工随制品上传 relay-stable Release）。

用法（输出到 stdout，用 shell 重定向落盘）：
    python3 scripts/relay-update-manifest.py <版本> <dmg> <msi> > relay-update.json
    python3 scripts/relay-update-manifest.py --verify <json> <dmg> <msi>
    （示例：python3 scripts/relay-update-manifest.py 1.1.0 GuoyingRelay-1.1.0.dmg GuoyingRelay-1.1.0.msi > relay-update.json）

输出 JSON 含 version/notes 与 mac/win 平台块（fileName/size/sha256），字段语义见
docs/superpowers/specs/2026-11-28-relay-auto-update-design.md；notes 可用 --notes 覆盖。
"""
import argparse
import hashlib
import json
import os
import sys


def asset(path: str) -> dict:
    digest = hashlib.sha256()
    size = 0
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    return {
        "fileName": os.path.basename(path),
        "size": size,
        "sha256": digest.hexdigest(),
    }


def build(version: str, dmg: str, msi: str, notes: str) -> dict:
    return {
        "version": version,
        "notes": notes,
        "mac": asset(dmg),
        "win": asset(msi),
    }


def verify(manifest_path: str, dmg: str, msi: str) -> bool:
    with open(manifest_path, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    expected = build(manifest["version"], dmg, msi, manifest.get("notes", ""))
    for key in ("mac", "win"):
        if manifest.get(key) != expected[key]:
            print(f"不一致：{key} 块与本地制品不符")
            return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="反向校验模式：<版本> 位置改传既有清单 JSON 路径")
    parser.add_argument("--notes", default="", help="更新说明（缺省空）")
    parser.add_argument("version", nargs="?", help="生成模式=版本号（如 1.1.0）；--verify 模式=既有清单 JSON 路径")
    parser.add_argument("dmg", nargs="?", help="macOS dmg 制品路径")
    parser.add_argument("msi", nargs="?", help="Windows msi 制品路径")
    args = parser.parse_args()

    if args.verify:
        if not args.version or not args.dmg or not args.msi:
            parser.error("--verify 需要 <清单json> <dmg> <msi> 三个路径")
        ok = verify(args.version, args.dmg, args.msi)
        print("校验通过" if ok else "校验未通过")
        return 0 if ok else 1

    if not args.version or not args.dmg or not args.msi:
        parser.error("生成模式需要 <版本> <dmg> <msi>")
    manifest = build(args.version, args.dmg, args.msi, args.notes)
    sys.stdout.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
