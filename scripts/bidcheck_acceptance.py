#!/usr/bin/env python3
"""BidCheck 真实文件验收脚本。只读结果和代码，不调用模型，不修改任何文件。

用法：
  python bidcheck_acceptance.py --comparison comparison.md --baseline baseline-comparison.md \
      --findings findings.md --claims confirmations.json --repo 仓库目录 [--reference-date-confirmed]

退出码：0 全部 hard 项通过；1 有 hard 项失败；2 输入文件有问题。
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

GAP = re.compile(r"\b([A-Z][A-Z0-9_]{6,})\b:")


def parse_rows(text: str) -> dict[str, dict[str, str]]:
    rows = {}
    for line in text.splitlines():
        if not line.startswith("|") or "<br>`" not in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split(" | ")]
        if len(cells) < 5:
            continue
        full_id = cells[0].split("`")[1]
        rows[full_id] = {"name": cells[0].split("<br>")[0], "before": cells[1], "status": cells[2],
                         "reason": " | ".join(cells[4:])}
    return rows


def find(rows: dict, prefix: str):
    hits = [row for key, row in rows.items() if key.startswith(prefix)]
    return hits[0] if len(hits) == 1 else None


class Report:
    def __init__(self):
        self.lines, self.hard_fail, self.soft_fail, self.passed, self.skipped = [], 0, 0, 0, 0

    def add(self, level, ok, label, detail=""):
        if ok is None:
            self.skipped += 1
            mark = "跳过"
        elif ok:
            self.passed += 1
            mark = "通过"
        elif level == "hard":
            self.hard_fail += 1
            mark = "失败"
        else:
            self.soft_fail += 1
            mark = "警告"
        self.lines.append(f"[{mark}] {label}" + (f"：{detail}" if detail else ""))


def check_case(case, rows, claims, confirmed, report):
    label = f"{case['ticket']} {case['id']} {case['name']}"
    if case.get("needs_reference_date") and not confirmed:
        report.add(case["level"], None, label, "实际截止日未确认")
        return
    row = find(rows, case["id"])
    if row is None:
        report.add(case["level"], False, label, "结果里找不到这一项，或 ID 前缀不唯一")
        return
    problems = []
    status, gaps = row["status"], set(GAP.findall(row["reason"]))
    if case.get("allowed_status") and status not in case["allowed_status"]:
        problems.append(f"状态是「{status}」，应为 {case['allowed_status']}")
    if status in case.get("forbidden_status", []):
        problems.append(f"状态不能是「{status}」")
    hit = gaps.intersection(case.get("forbidden_gaps", []))
    if hit:
        problems.append(f"不应出现缺口 {sorted(hit)}")
    text_hit = [t for t in case.get("forbidden_reason", []) if t in row["reason"]]
    if text_hit:
        problems.append(f"原因里不应出现 {text_hit}")
    wanted = case.get("required_claim_type")
    if wanted and claims is not None:
        have = any(c.get("inferenceType") == wanted and str(c.get("checkpointId", "")).startswith(case["id"])
                   for c in claims)
        if not have and status != "满足要求":
            problems.append(f"缺少 {wanted} 确认卡")
    report.add(case["level"], not problems, label, "；".join(problems))


def check_global(rows, base_rows, findings, claims, spec, report):
    if len(rows) != 109:
        report.add("hard", False, "总项数", f"应为 109，实际 {len(rows)}")
    else:
        report.add("hard", True, "总项数 109")
    if base_rows is not None:
        allowed = tuple(spec.get("allow_pass_removed", []))
        lost = [f"{k[:8]} {v['name'][:24]}" for k, v in base_rows.items()
                if v["status"] == "满足要求" and not k.startswith(allowed)
                and rows.get(k, {}).get("status") != "满足要求"]
        report.add("hard", not lost, "旧通过不回退", "；".join(lost[:10]))
    if findings is not None:
        m = re.search(r"(\d+)\s*条已结束补查处理记录，其中\s*\*{0,2}(\d+)\s*条 progressMade=false，累计明确耗时\s*\*{0,2}(\d+)\s*分", findings)
        limits = spec["limits"]
        if m:
            total, idle, minutes = map(int, m.groups())
            ratio = idle / total if total else 0
            report.add("hard", ratio <= limits["max_recovery_noprogress_ratio"], "补查无进展占比",
                       f"{idle}/{total} = {ratio:.0%}，上限 {limits['max_recovery_noprogress_ratio']:.0%}")
            report.add("hard", minutes <= limits["max_recovery_noprogress_minutes"], "补查无进展耗时",
                       f"{minutes} 分钟，上限 {limits['max_recovery_noprogress_minutes']} 分钟")
        else:
            report.add("hard", False, "补查统计", "findings 里没找到补查统计，无法判定")
    if claims is not None:
        pairs = {}
        for claim in claims:
            key = (claim.get("checkpointId"), tuple(sorted(claim.get("proposedFact", {}).get("sourceIds", []))))
            pairs[key] = pairs.get(key, 0) + 1
        repeated = sum(1 for n in pairs.values() if n > 1)
        report.add("hard", repeated == 0, "同一来源配对只出一张卡", f"{repeated} 组重复" if repeated else "")
        same_name = [c for c in claims if c.get("inferenceType") == "PERSON_IDENTITY"
                     and "身份证" not in json.dumps(c.get("risks", []), ensure_ascii=False)
                     and any(re.search(r"\d{17}[0-9Xx]", e.get("quote", "")) for e in c.get("evidences", []))]
        report.add("soft", not same_name, "身份证号可比对时不再问人", f"{len(same_name)} 张卡引用了完整身份证号" if same_name else "")


def check_time(comparison, spec, report):
    limits = spec["limits"]
    if limits.get("must_finish_naturally"):
        cancelled = "**CANCELLED**" in comparison
        report.add("hard", not cancelled, "任务自然结束", "本轮是被取消的" if cancelled else "")
    m = re.search(r"本轮：([\d.]+)\s*分钟", comparison)
    if m:
        minutes = float(m.group(1))
        report.add("hard", minutes <= limits["max_total_minutes"], "总耗时", f"{minutes} 分钟，上限 {limits['max_total_minutes']} 分钟")
    else:
        report.add("hard", False, "总耗时", "没找到耗时")


def java_regex(source: str, name: str):
    m = re.search(rf'{name}\s*=\s*(?:Pattern\.compile\()?"((?:[^"\\]|\\.)*)"', source)
    if not m:
        return None
    return m.group(1).replace("\\\\", "\\").replace("\\p{IsHan}", "一-鿿")


def java_test_receipt(log_file: Path, reports: Path):
    """Read actual Java execution counts. The migrated 55a0af4 baseline ran 4177, skipped 18."""
    log = log_file.read_text(encoding="utf-8-sig")
    suites = set(re.findall(r"(?m)^\[INFO\] Running ([\w.$]+)\s*$", log))
    summaries = re.findall(
        r"(?m)^\[(?:INFO|ERROR|WARNING)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$",
        log)
    if not suites or not summaries or not re.search(r"\[INFO\] BUILD (?:SUCCESS|FAILURE)", log):
        raise ValueError("缺少完整 Maven 结束记录")
    expected = tuple(map(int, summaries[-1]))
    actual = [0, 0, 0, 0]
    passed = set()
    for name in suites:
        root = ET.parse(reports / ("TEST-" + name + ".xml")).getroot()
        if root.tag != "testsuite" or root.get("name") != name:
            raise ValueError("Surefire suite 与运行日志不一致")
        counts = tuple(int(root.get(k, "-1")) for k in ("tests", "failures", "errors", "skipped"))
        cases = root.findall("testcase")
        measured = (len(cases),
                    sum(c.find("failure") is not None for c in cases),
                    sum(c.find("error") is not None for c in cases),
                    sum(c.find("skipped") is not None for c in cases))
        if counts != measured:
            raise ValueError("Surefire 汇总与实际用例不一致")
        actual = [a + b for a, b in zip(actual, counts)]
        for case in cases:
            if all(case.find(tag) is None for tag in ("failure", "error", "skipped")):
                passed.add(case.get("classname", "") + "#" + case.get("name", ""))
    if tuple(actual) != expected:
        raise ValueError("Maven 完整汇总与 Surefire XML 不一致")
    return {"counts": actual, "passed": passed}


def check_code(repo: Path, spec, base, report, java_receipt=None):
    code = spec["code_checks"]
    for item in code["guards_must_remain"]:
        path = repo / item["file"]
        ok = path.exists() and item["text"] in path.read_text(encoding="utf-8")
        report.add("hard", ok, f"保护逻辑仍在 {Path(item['file']).name}", item["text"])
    for item in code["must_contain"]:
        path = repo / item["file"]
        ok = path.exists() and item["text"] in path.read_text(encoding="utf-8")
        report.add("hard", ok, f"{item['ticket']} {Path(item['file']).name} 应包含", item["text"])
    for item in code["must_not_contain"]:
        if java_receipt is not None and item == {"ticket": "工单11", "file": "agent-worker/worker.py", "text": "max_retries=0"}:
            required = {
                "cn.bidcheck.verification.agent.JavaAgentHttpTest#retriesServerFailuresTwiceButNeverRetriesUnauthorized",
                "cn.bidcheck.verification.agent.JavaAgentHttpTest#brokenConnectionCanRecoverAndExhaustionKeepsTransportCategory",
                "cn.bidcheck.verification.agent.JavaAgentHttpTest#absoluteRequestDeadlineStopsSlowProvider",
                "cn.bidcheck.verification.agent.JavaAgentRunnerTest#hostDeadlineKeepsStartedModelAndToolCounts",
            }
            missing = required - java_receipt["passed"]
            report.add("hard", not missing, "工单11 Java 实际重试与超时行为",
                       "未通过 " + "、".join(sorted(missing)) if missing else "连接及5xx重试2次、401不重试、绝对期限及调用计数均通过实际回归")
            continue
        path = repo / item["file"]
        ok = path.exists() and item["text"] not in path.read_text(encoding="utf-8")
        report.add("hard", ok, f"{item['ticket']} {Path(item['file']).name} 不应包含", item["text"])

    review = repo / "backend/src/main/java/cn/bidcheck/engine/FullTextReview.java"
    engine = repo / "backend/src/main/java/cn/bidcheck/engine/EngineText.java"
    if review.exists() and engine.exists():
        label = java_regex(review.read_text(encoding="utf-8"), "SIGNING_LABEL")
        date = java_regex(engine.read_text(encoding="utf-8"), "DATE")
        ok_label = ok_date = False
        try:
            ok_label = bool(label and re.search(label, "日期：2025年10月8日"))
            ok_date = bool(date and re.search(date, "2025 年10 月8 日"))
        except re.error:
            pass
        report.add("hard", ok_label, "工单1 落款标签能识别「日期：」")
        report.add("hard", ok_date, "工单1 日期规则接受「2025 年10 月8 日」")

    if java_receipt is not None:
        total, failures, errors, skipped = java_receipt["counts"]
        report.add("hard", total >= 4177 and total - skipped >= 4159, "测试数量不减少（Java完整执行）",
                   f"Java迁移基线4177条、非跳过4159条；本轮{total}条、非跳过{total - skipped}条，失败{failures}、错误{errors}、跳过{skipped}另行保留")
        return

    def count(ref: str) -> int | None:
        total = 0
        for pattern, paths in (("@Test", ["backend/src/test"]), ("def test_", ["agent-worker"])):
            args = ["git", "-C", str(repo), "grep", "-c", pattern]
            if ref:
                args.append(ref)
            args += ["--", *paths]
            done = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
            if done.returncode not in (0, 1):
                return None
            total += sum(int(line.rsplit(":", 1)[1]) for line in done.stdout.splitlines() if ":" in line)
        return total

    before, after = count(base), count("")
    if before is None or after is None:
        report.add("hard", False, "测试数量不减少", "无法读取 git 记录")
    else:
        report.add("hard", after >= before, "测试数量不减少", f"基线 {before}，当前 {after}")


def read_text(path):
    if not path:
        return None
    file = Path(path)
    if not file.exists():
        print(f"文件不存在：{path}")
        raise SystemExit(2)
    return file.read_text(encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="BidCheck 真实文件验收")
    parser.add_argument("--comparison", required=True, help="本轮 comparison.md")
    parser.add_argument("--baseline", help="基线 comparison.md，用来检查旧通过是否回退")
    parser.add_argument("--findings", help="本轮 findings.md")
    parser.add_argument("--claims", help="本轮确认记录 JSON")
    parser.add_argument("--repo", help="代码仓库目录")
    parser.add_argument("--java-test-log", type=Path, help="已授权的纯Java适配，完整Maven原始日志；须同时提供--java-test-reports")
    parser.add_argument("--java-test-reports", type=Path, help="与完整Maven日志对应的Surefire XML目录")
    parser.add_argument("--cases", default=str(Path(__file__).with_name("acceptance_cases.json")))
    parser.add_argument("--reference-date-confirmed", action="store_true", help="客户已确认实际截止日且已按它重跑")
    args = parser.parse_args()
    if bool(args.java_test_log) != bool(args.java_test_reports) or (args.java_test_log and not args.repo):
        parser.error("纯Java适配需同时提供 --repo、--java-test-log、--java-test-reports")
    receipt = None
    if args.java_test_log:
        try:
            receipt = java_test_receipt(args.java_test_log, args.java_test_reports)
        except (OSError, ValueError, ET.ParseError) as error:
            print(f"Java回归记录不可验证：{error}")
            return 2

    spec = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    comparison = read_text(args.comparison)
    rows = parse_rows(comparison)
    if not rows:
        print("comparison 里没有解析到检查项")
        return 2
    baseline_text = read_text(args.baseline)
    claims_text = read_text(args.claims)
    claims = json.loads(claims_text).get("claims", []) if claims_text else None

    report = Report()
    check_global(rows, parse_rows(baseline_text) if baseline_text else None, read_text(args.findings), claims, spec, report)
    check_time(comparison, spec, report)
    for case in spec["cases"]:
        check_case(case, rows, claims, args.reference_date_confirmed, report)
    if args.repo:
        check_code(Path(args.repo), spec, spec["base_commit"], report, receipt)

    order = {"失败": 0, "警告": 1, "跳过": 2, "通过": 3}
    for line in sorted(report.lines, key=lambda x: order[x[1:3]]):
        print(line)
    print(f"\n合计：通过 {report.passed}，失败 {report.hard_fail}，警告 {report.soft_fail}，跳过 {report.skipped}")
    print("结论：验收失败" if report.hard_fail else "结论：hard 项全部通过（仍需人工复核警告和跳过项）")
    return 1 if report.hard_fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
