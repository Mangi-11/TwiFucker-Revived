#!/usr/bin/env python3
"""生成 Twitter/X 更新后的 hook 契约分析报告。"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


STATUS_PASS = "pass"
STATUS_PARTIAL = "partial"
STATUS_FAIL = "fail"


@dataclass
class Evidence:
    label: str
    ok: bool
    detail: str = ""
    file: str | None = None

    def to_json(self) -> dict:
        data = {"label": self.label, "ok": self.ok}
        if self.detail:
            data["detail"] = self.detail
        if self.file:
            data["file"] = self.file
        return data


class SourceTree:
    def __init__(self, jadx_dir: Path) -> None:
        self.jadx_dir = jadx_dir
        self.sources = jadx_dir / "sources"
        self._cache: dict[Path, str | None] = {}

    def class_path(self, class_name: str) -> Path:
        return self.sources / (class_name.replace(".", "/") + ".java")

    def read_path(self, path: Path) -> str | None:
        if path not in self._cache:
            if not path.is_file():
                self._cache[path] = None
            else:
                self._cache[path] = path.read_text(encoding="utf-8", errors="ignore")
        return self._cache[path]

    def read_class(self, class_name: str) -> tuple[Path, str | None]:
        path = self.class_path(class_name)
        return path, self.read_path(path)

    def has_class(self, class_name: str) -> Evidence:
        path, text = self.read_class(class_name)
        return Evidence(
            label=f"class {class_name}",
            ok=text is not None,
            file=rel(path),
        )

    def contains(self, class_name: str, label: str, patterns: Iterable[str]) -> Evidence:
        path, text = self.read_class(class_name)
        if text is None:
            return Evidence(label=label, ok=False, detail="class missing", file=rel(path))
        missing = [pattern for pattern in patterns if pattern not in text]
        return Evidence(
            label=label,
            ok=not missing,
            detail="" if not missing else "missing: " + ", ".join(missing),
            file=rel(path),
        )

    def contains_regex(self, class_name: str, label: str, patterns: Iterable[str]) -> Evidence:
        path, text = self.read_class(class_name)
        if text is None:
            return Evidence(label=label, ok=False, detail="class missing", file=rel(path))
        missing = [pattern for pattern in patterns if re.search(pattern, text) is None]
        return Evidence(
            label=label,
            ok=not missing,
            detail="" if not missing else "missing regex: " + ", ".join(missing),
            file=rel(path),
        )

    def find_files_with_all(self, needles: Iterable[str], subdir: str, limit: int = 12) -> list[str]:
        root = self.sources / subdir
        if not root.is_dir():
            return []

        found: list[str] = []
        required = list(needles)
        for path in root.rglob("*.java"):
            text = self.read_path(path)
            if text is None:
                continue
            if all(needle in text for needle in required):
                found.append(rel(path))
                if len(found) >= limit:
                    break
        return found


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(Path.cwd().resolve()))
    except ValueError:
        return str(path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def class_contract(
    tree: SourceTree,
    contract_id: str,
    title: str,
    evidence: list[Evidence],
    candidates: dict[str, list[str]] | None = None,
) -> dict:
    required_ok = [item.ok for item in evidence]
    if all(required_ok):
        status = STATUS_PASS
    elif any(required_ok):
        status = STATUS_PARTIAL
    else:
        status = STATUS_FAIL

    return {
        "id": contract_id,
        "title": title,
        "status": status,
        "evidence": [item.to_json() for item in evidence],
        "missing": [item.label for item in evidence if not item.ok],
        "candidates": candidates or {},
    }


def build_urt_contract(tree: SourceTree) -> dict:
    evidence = [
        tree.has_class("com.x.models.timelines.items.UrtTimelineItem"),
        tree.contains(
            "com.x.models.timelines.items.UrtTimelineItem",
            "UrtTimelineItem stable getters",
            ["getEntryId", "getClientEventInfo", "getSortIndex"],
        ),
        tree.contains(
            "com.x.models.timelines.items.UrtTimelinePost",
            "post promoted metadata getter",
            ["getPromotedMetadata"],
        ),
        tree.contains(
            "com.x.models.timelines.items.UrtTimelineUser",
            "user promoted metadata getter",
            ["getPromotedMetadata"],
        ),
        tree.contains(
            "com.x.models.timelines.items.UrtTimelineTrend",
            "trend payload getter",
            ["getTimelineTrend"],
        ),
        tree.contains(
            "com.x.models.TimelineTrend",
            "trend promoted metadata getter",
            ["getPromotedMetadata"],
        ),
        tree.contains(
            "com.x.models.timelines.items.UrtTimelineModule",
            "module copy and inner content getters",
            ["getInnerContent", "getModuleHeader", "getModuleFooter", "getDisplayType", "getEntryId", "copy("],
        ),
        tree.contains(
            "com.x.models.timelines.items.UrtTimelineModuleItem",
            "module item nested item and copy",
            ["getItem", "copy("],
        ),
        tree.contains_regex(
            "com.x.models.timelines.items.UrtTimelineModuleItem",
            "module item dispensable getter",
            [r"\b(isDispensable|getIsDispensable)\s*\("],
        ),
        tree.contains(
            "com.x.models.ClientEventInfo",
            "client event component getter",
            ["getComponent"],
        ),
        tree.contains(
            "com.x.urt.v",
            "timelineUpdates flow filter anchor",
            ["UrtTimelineItem", "UrtTimelineCursor", "public final java.lang.Object emit(java.lang.Object"],
        ),
    ]

    candidates: dict[str, list[str]] = {}
    if not evidence[-1].ok:
        candidates["timeline flow"] = tree.find_files_with_all(
            ["UrtTimelineItem", "UrtTimelineCursor", "kotlinx.coroutines.flow.h"],
            "com/x/urt",
        )
    if not evidence[2].ok:
        candidates["promoted post model"] = tree.find_files_with_all(
            ["UrtTimelineItem", "getPromotedMetadata"],
            "com/x/models",
        )

    return class_contract(
        tree,
        "new_urt_timeline_filter",
        "新版 Kotlin serialization URT 时间线过滤",
        evidence,
        candidates,
    )


def build_logansquare_contract(tree: SourceTree) -> dict:
    evidence = [
        tree.has_class("com.twitter.model.json.timeline.urt.JsonTimelineEntry"),
        tree.contains(
            "com.twitter.model.json.timeline.urt.JsonTimelineEntry",
            "JsonTimelineEntry conversion method and fields",
            ["String", " r()"],
        ),
        tree.has_class("com.twitter.model.json.timeline.urt.JsonTimelineEntry$$JsonObjectMapper"),
        tree.has_class("com.twitter.model.json.timeline.urt.JsonPromotedContentUrt"),
        tree.has_class("com.twitter.model.json.timeline.urt.JsonTimelineTweet"),
        tree.has_class("com.twitter.model.json.timeline.urt.JsonTimelineTrend"),
        tree.has_class("com.twitter.model.json.timeline.urt.JsonTimelineUser"),
        tree.has_class("com.twitter.model.json.timeline.urt.JsonTimelineModule"),
    ]
    return class_contract(
        tree,
        "legacy_logansquare_timeline_filter",
        "旧 LoganSquare URT 模型兜底过滤",
        evidence,
    )


def build_sensitive_media_contract(tree: SourceTree) -> dict:
    evidence = [
        tree.has_class("com.twitter.model.json.core.JsonSensitiveMediaWarning"),
        tree.has_class("com.twitter.model.json.core.JsonSensitiveMediaWarning$$JsonObjectMapper"),
        tree.contains_regex(
            "com.twitter.model.json.core.JsonSensitiveMediaWarning",
            "warning boolean fields",
            [r"\bboolean\b"],
        ),
    ]
    return class_contract(
        tree,
        "sensitive_media_warning",
        "敏感媒体警告模型清理",
        evidence,
    )


def build_translation_contract(tree: SourceTree) -> dict:
    evidence = [
        tree.has_class("com.x.urt.items.post.translate.grok.l"),
        tree.has_class("com.x.urt.items.post.translate.grok.c"),
        tree.has_class("com.twitter.translation.GrokTranslationStatusView"),
        tree.has_class("com.twitter.translation.dialog.h"),
        tree.has_class("com.x.groktranslate.h"),
    ]
    return class_contract(
        tree,
        "bilingual_translation",
        "Grok/legacy 翻译双语对照入口",
        evidence,
    )


def build_local_premium_contract(tree: SourceTree) -> dict:
    evidence = [
        tree.has_class("com.twitter.util.config.a0"),
        tree.contains_regex(
            "com.twitter.util.config.a0",
            "feature switch boolean getter",
            [r"\bboolean\s+\w+\(@org\.jetbrains\.annotations\.a String \w+, boolean \w+\)"],
        ),
        tree.has_class("com.twitter.subscriptions.features.api.i"),
        tree.contains(
            "com.twitter.subscriptions.features.api.i",
            "premium feature constants",
            ["feature/twitter_blue", "feature/premium_basic", "feature/twitter_blue_verified", "feature/premium_plus"],
        ),
        tree.contains(
            "com.twitter.subscriptions.features.api.i",
            "premium gate feature switch keys",
            ["subscriptions_enabled", "subscriptions_gating_bypass"],
        ),
        tree.contains_regex(
            "com.twitter.subscriptions.features.api.i",
            "userPreferences string-array gate",
            [r"\bboolean\s+\w+\(String\[\] \w+, l \w+\)"],
        ),
        tree.has_class("com.twitter.util.prefs.l"),
    ]

    candidates: dict[str, list[str]] = {}
    if not evidence[2].ok:
        candidates["subscription feature api"] = tree.find_files_with_all(
            ["feature/twitter_blue", "subscriptions_enabled", "subscriptions_gating_bypass"],
            "com/twitter/subscriptions",
        )
    if not evidence[0].ok:
        candidates["feature switch facade"] = tree.find_files_with_all(
            ["boolean", "String", "feature_switch"],
            "com/twitter/util/config",
        )

    return class_contract(
        tree,
        "local_premium_gate",
        "本地 Premium feature switch / userPreferences 门控",
        evidence,
        candidates,
    )


def build_google_ads_contract(tree: SourceTree) -> dict:
    evidence = [
        tree.has_class("com.google.android.gms.ads.nativead.NativeAdView"),
        tree.contains(
            "com.google.android.gms.ads.nativead.NativeAdView",
            "native ad visibility method",
            ["onVisibilityChanged"],
        ),
    ]
    return class_contract(
        tree,
        "google_native_ads",
        "Google NativeAdView 隐藏兜底",
        evidence,
    )


def summarize_contracts(contracts: list[dict]) -> dict:
    counts = {
        STATUS_PASS: 0,
        STATUS_PARTIAL: 0,
        STATUS_FAIL: 0,
    }
    for contract in contracts:
        counts[contract["status"]] += 1

    if counts[STATUS_FAIL] == 0 and counts[STATUS_PARTIAL] == 0:
        overall = STATUS_PASS
    elif counts[STATUS_PASS] > 0 or counts[STATUS_PARTIAL] > 0:
        overall = STATUS_PARTIAL
    else:
        overall = STATUS_FAIL

    return {"overall": overall, "counts": counts}


def load_previous_analysis(out_dir: Path, current_version_code: int) -> dict | None:
    twitter_root = out_dir.parent
    previous: list[tuple[int, Path, dict]] = []
    for path in twitter_root.glob("*/analysis.json"):
        if path.parent == out_dir:
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            version_code = int(data.get("version", {}).get("code", 0))
        except (OSError, ValueError, json.JSONDecodeError):
            continue
        if version_code <= current_version_code:
            previous.append((version_code, path, data))

    if not previous:
        return None

    previous.sort(key=lambda item: item[0], reverse=True)
    _, path, data = previous[0]
    return {"path": rel(path), "analysis": data}


def build_comparison(current: dict, previous: dict | None) -> dict | None:
    if previous is None:
        return None

    old_contracts = {
        contract["id"]: contract
        for contract in previous["analysis"].get("contracts", [])
    }
    changes = []
    for contract in current["contracts"]:
        old = old_contracts.get(contract["id"])
        if old is None:
            changes.append({
                "id": contract["id"],
                "from": "missing",
                "to": contract["status"],
            })
            continue
        if old.get("status") != contract["status"]:
            changes.append({
                "id": contract["id"],
                "from": old.get("status"),
                "to": contract["status"],
            })

    return {
        "previousAnalysis": previous["path"],
        "changes": changes,
    }


def write_markdown(report: dict, path: Path) -> None:
    lines = [
        f"# Twitter/X 更新分析：{report['version']['id']}",
        "",
        "## 摘要",
        "",
        f"- 目标包名：`{report['targetPackage']}`",
        f"- 版本：`{report['version']['name']}` / `{report['version']['code']}`",
        f"- base.apk SHA-256：`{report['apk']['baseSha256']}`",
        f"- jadx 源码：`{report['sourceRoot']}`",
        f"- 总体状态：`{report['summary']['overall']}`",
        "",
        "## Hook 契约",
        "",
        "| 契约 | 状态 | 缺失项 |",
        "| --- | --- | --- |",
    ]

    for contract in report["contracts"]:
        missing = ", ".join(contract["missing"]) if contract["missing"] else "-"
        lines.append(f"| `{contract['id']}` | `{contract['status']}` | {missing} |")

    for contract in report["contracts"]:
        lines.extend(["", f"### {contract['title']}", ""])
        lines.append(f"- ID：`{contract['id']}`")
        lines.append(f"- 状态：`{contract['status']}`")
        for item in contract["evidence"]:
            mark = "OK" if item["ok"] else "MISS"
            detail = f"；{item['detail']}" if item.get("detail") else ""
            file_text = f"（{item['file']}）" if item.get("file") else ""
            lines.append(f"- `{mark}` {item['label']}{file_text}{detail}")
        if contract["candidates"]:
            lines.append("- 候选：")
            for label, candidates in contract["candidates"].items():
                lines.append(f"  - {label}: {', '.join(candidates) if candidates else '-'}")

    comparison = report.get("comparison")
    if comparison:
        lines.extend(["", "## 与上一份报告对比", ""])
        lines.append(f"- 上一份报告：`{comparison['previousAnalysis']}`")
        if comparison["changes"]:
            for change in comparison["changes"]:
                lines.append(f"- `{change['id']}`: `{change['from']}` -> `{change['to']}`")
        else:
            lines.append("- 契约状态无变化。")

    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def build_apk_info(apk_dir: Path) -> dict:
    files = []
    for path in sorted(apk_dir.glob("*.apk")):
        files.append({
            "name": path.name,
            "size": path.stat().st_size,
            "sha256": sha256_file(path),
        })

    base = next((item for item in files if item["name"] == "base.apk"), None)
    if base is None:
        raise SystemExit(f"base.apk not found in {apk_dir}")

    return {
        "dir": rel(apk_dir),
        "baseSha256": base["sha256"],
        "files": files,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze Twitter/X hook contracts from jadx output.")
    parser.add_argument("--package", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--apk-dir", required=True, type=Path)
    parser.add_argument("--jadx-dir", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    tree = SourceTree(args.jadx_dir)
    if not tree.sources.is_dir():
        raise SystemExit(f"jadx sources not found: {tree.sources}")

    contracts = [
        build_urt_contract(tree),
        build_logansquare_contract(tree),
        build_sensitive_media_contract(tree),
        build_local_premium_contract(tree),
        build_translation_contract(tree),
        build_google_ads_contract(tree),
    ]

    version_id = f"{args.version_name}_{args.version_code}"
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "targetPackage": args.package,
        "version": {
            "id": version_id,
            "name": args.version_name,
            "code": args.version_code,
        },
        "apk": build_apk_info(args.apk_dir),
        "sourceRoot": rel(tree.sources),
        "summary": summarize_contracts(contracts),
        "contracts": contracts,
    }

    previous = load_previous_analysis(args.out_dir, args.version_code)
    comparison = build_comparison(report, previous)
    if comparison is not None:
        report["comparison"] = comparison

    args.out_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.out_dir / "analysis.json"
    md_path = args.out_dir / "analysis.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_markdown(report, md_path)

    print(f"overall={report['summary']['overall']}")
    for contract in contracts:
        print(f"{contract['id']}={contract['status']}")


if __name__ == "__main__":
    main()
