#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调用意图识别评测接口，并生成便于排查的 Markdown 报告。"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from statistics import mean
from typing import Any
from urllib.parse import urlsplit


DEFAULT_DATASET = Path(__file__).resolve().parents[1] / "dataset" / "intent-recognition-dataset-v1-20261118.json"
DEFAULT_ENDPOINT = "/business/eval/intent/recognize"


def parse_args() -> argparse.Namespace:
    """解析命令行参数，允许通过位置参数或选项指定评测数据集。"""
    parser = argparse.ArgumentParser(
        description="批量评测后端意图识别接口并生成 Markdown 报告",
        epilog="示例：python run_intent_evaluation.py ..\\dataset\\intent-recognition-dataset-v2.json",
    )
    parser.add_argument("dataset_path", nargs="?", type=Path,
                        help="评测数据集 JSON 路径；也可通过 --dataset 指定")
    parser.add_argument("--dataset", dest="dataset_option", type=Path, default=None,
                        help="评测数据集 JSON 路径；未指定时使用默认数据集")
    parser.add_argument("--base-url", default="http://localhost:8080", help="后端服务根地址")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="意图评测接口路径")
    parser.add_argument("--token", default=None, help="Sa-Token 登录令牌；也可通过环境变量提供")
    parser.add_argument("--token-env", default="CORPTRIP_TOKEN", help="令牌环境变量名，默认 CORPTRIP_TOKEN")
    parser.add_argument("--token-header", default="Authorization", help="令牌请求头名称，默认 Authorization")
    parser.add_argument("--token-prefix", default="Bearer", help="直接传入令牌时使用的前缀，默认 Bearer")
    parser.add_argument("--username", default=None, help="管理员账号；未提供 token 时可通过环境变量登录")
    parser.add_argument("--username-env", default="CORPTRIP_USERNAME", help="管理员账号环境变量名")
    parser.add_argument("--password-env", default="CORPTRIP_PASSWORD", help="管理员密码环境变量名；不支持命令行明文密码")
    parser.add_argument("--login-endpoint", default="/auth/login", help="登录接口路径")
    parser.add_argument("--timeout", type=float, default=60, help="单次请求超时秒数")
    parser.add_argument("--sleep-ms", type=int, default=0, help="每次请求之间的间隔毫秒数")
    parser.add_argument("--report", type=Path, default=None, help="报告文件路径；默认写入 eval/intent/report")
    parser.add_argument("--rewrites-output", type=Path, default=None,
                        help="改写问题 JSON 输出路径；默认写入数据集目录")
    parser.add_argument("--stop-on-error", action="store_true", help="首个请求或响应错误时停止")
    args = parser.parse_args()
    if args.dataset_path is not None and args.dataset_option is not None:
        parser.error("数据集路径只能通过位置参数或 --dataset 指定其中一种")
    args.dataset = args.dataset_option or args.dataset_path or DEFAULT_DATASET
    return args


def load_dataset(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """读取数据集并检查批量评测所需的基本字段。"""
    with path.open("r", encoding="utf-8") as dataset_file:
        dataset = json.load(dataset_file)
    cases = dataset.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("数据集必须包含非空 cases 数组")
    for index, case in enumerate(cases, start=1):
        if not isinstance(case, dict) or not isinstance(case.get("question"), str):
            raise ValueError(f"第 {index} 条 case 缺少字符串类型的 question")
        expected = case.get("expected")
        if not isinstance(expected, dict) or not isinstance(expected.get("intents"), list):
            raise ValueError(f"第 {index} 条 case 缺少 expected.intents 数组")
    return dataset, cases


def post_json(url: str, payload: dict[str, Any], headers: dict[str, str], timeout: float) -> dict[str, Any]:
    """发送 JSON POST 请求并解析统一 JSON 响应。"""
    request_body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=request_body,
        headers={"Content-Type": "application/json; charset=utf-8", "Accept": "application/json", **headers},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        try:
            error_body = error.read().decode("utf-8")
            error_payload = json.loads(error_body)
            detail = error_payload.get("message") or error_payload.get("errorCode") if isinstance(error_payload, dict) else None
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            detail = None
        suffix = f"：{detail}" if detail else ""
        message = f"HTTP {error.code}{suffix}"
        if error.code in (401, 403):
            message += "；请确认令牌有效且对应账号具有 ADMIN 角色"
        raise RuntimeError(message) from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        reason = getattr(error, "reason", None)
        label = type(reason).__name__ if reason is not None else type(error).__name__
        raise RuntimeError(f"请求失败：{label}") from None
    try:
        envelope = json.loads(response_body)
    except json.JSONDecodeError:
        raise RuntimeError("响应不是有效 JSON") from None
    if not isinstance(envelope, dict):
        raise RuntimeError("响应不是 JSON 对象")
    return envelope


def check_backend_port(base_url: str, timeout: float) -> None:
    """在批量请求前确认后端主机和端口可连接。"""
    parsed = urlsplit(base_url)
    if not parsed.hostname or parsed.port is None:
        raise ValueError("后端地址必须包含主机和端口，例如 http://localhost:8080")
    try:
        with socket.create_connection((parsed.hostname, parsed.port), timeout=min(timeout, 3)):
            return
    except OSError as error:
        raise RuntimeError(
            f"无法连接后端 {parsed.hostname}:{parsed.port}（{type(error).__name__}）。"
            "请先启动 Spring Boot 后端，并确认 server.port 与 --base-url 一致。"
        ) from None


def request_case(url: str, question: str, headers: dict[str, str], timeout: float) -> dict[str, Any]:
    """向评测接口发送单条原始问题，并校验统一响应包装。"""
    envelope = post_json(url, {"question": question}, headers, timeout)
    if envelope.get("success") is not True:
        code = envelope.get("errorCode")
        raise RuntimeError(f"接口返回失败{f'（{code}）' if code else ''}")
    data = envelope.get("data")
    if not isinstance(data, dict):
        raise RuntimeError("响应缺少 data 对象")
    intents = data.get("intents")
    if not isinstance(intents, list):
        raise RuntimeError("响应缺少 data.intents 数组")
    return data


def login(base_url: str, login_endpoint: str, username: str, password: str,
          token_header: str, timeout: float) -> dict[str, str]:
    """使用管理员账号登录并返回本次进程使用的认证请求头。"""
    envelope = post_json(base_url.rstrip("/") + "/" + login_endpoint.lstrip("/"),
                         {"username": username, "password": password}, {}, timeout)
    if envelope.get("success") is not True or not isinstance(envelope.get("data"), dict):
        code = envelope.get("errorCode")
        raise RuntimeError(f"登录失败{f'（{code}）' if code else ''}")
    data = envelope["data"]
    token = data.get("tokenValue")
    if not token:
        raise RuntimeError("登录响应缺少 data.tokenValue")
    actual_header = str(data.get("tokenName") or token_header)
    prefix = str(data.get("tokenPrefix") or "").strip()
    return {actual_header: f"{prefix} {token}".strip()}


def evaluate_cases(cases: list[dict[str, Any]], url: str, headers: dict[str, str],
                   timeout: float, sleep_ms: int, stop_on_error: bool) -> list[dict[str, Any]]:
    """逐条请求接口，保留预测、耗时和错误供后续汇总。"""
    results: list[dict[str, Any]] = []
    for index, case in enumerate(cases):
        item: dict[str, Any] = {"case": case, "data": None, "error": None, "requestMs": None}
        started = time.perf_counter()
        try:
            item["data"] = request_case(url, case["question"], headers, timeout)
        except RuntimeError as error:
            item["error"] = str(error)
            # 认证失败对整批请求都成立，继续发送只会造成重复失败并污染准确率报告。
            if "HTTP 401" in item["error"] or "HTTP 403" in item["error"]:
                raise RuntimeError(
                    f"case {case.get('id', index + 1)}：{error}。已停止批量评测；"
                    "请重新获取有效管理员令牌，并核对 token-header 配置。"
                ) from None
            if stop_on_error:
                item["requestMs"] = round((time.perf_counter() - started) * 1000, 2)
                results.append(item)
                raise RuntimeError(f"case {case.get('id', index + 1)}：{error}") from None
        item["requestMs"] = round((time.perf_counter() - started) * 1000, 2)
        results.append(item)
        print(f"[{index + 1}/{len(cases)}] {case.get('id', index + 1)}: {'失败' if item['error'] else '完成'}")
        if sleep_ms > 0 and index + 1 < len(cases):
            time.sleep(sleep_ms / 1000)
    return results


def prediction(result: dict[str, Any]) -> tuple[list[str], bool]:
    """从接口 data 中提取按返回顺序排列的意图编码及多意图标记。"""
    data = result["data"]
    labels = [str(entry.get("intent")) for entry in data.get("intents", [])
              if isinstance(entry, dict) and entry.get("intent")]
    return labels, bool(data.get("multiIntent", False))


def rewritten_question(result: dict[str, Any]) -> str:
    """读取接口返回的有效改写问题，没有发生改写时返回空字符串。"""
    if result["data"] is None:
        return ""
    value = result["data"].get("rewrittenQuestion")
    return value.strip() if isinstance(value, str) else ""


def is_case_correct(result: dict[str, Any]) -> bool:
    """判断意图数组顺序和多意图标记是否都与标注一致。"""
    if result["data"] is None:
        return False
    expected = result["case"]["expected"]["intents"]
    expected_multi = bool(result["case"]["expected"].get("isMultiIntent", len(expected) > 1))
    actual, actual_multi = prediction(result)
    return expected == actual and expected_multi == actual_multi


def percentile(values: list[float], fraction: float) -> float | None:
    """使用 nearest-rank 方法计算百分位，空样本返回空值。"""
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((len(ordered) * fraction + 0.999999)) - 1))
    return ordered[index]


def ratio(numerator: int, denominator: int) -> float | None:
    """计算比例并避免分母为零。"""
    return numerator / denominator if denominator else None


def calculate_metrics(results: list[dict[str, Any]]) -> dict[str, Any]:
    """计算总体、分意图、分样本类型和时延指标。"""
    total = len(results)
    successful = [item for item in results if item["data"] is not None]
    exact_count = primary_count = multi_flag_count = 0
    category_stats: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    difficulty_stats: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    label_stats: dict[str, dict[str, int]] = defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0, "support": 0})
    confusion: Counter[tuple[str, str]] = Counter()
    latencies: list[float] = []
    stages: dict[str, list[float]] = defaultdict(list)
    for item in results:
        case = item["case"]
        expected = list(case["expected"]["intents"])
        expected_multi = bool(case["expected"].get("isMultiIntent", len(expected) > 1))
        actual, actual_multi = prediction(item) if item["data"] is not None else ([], False)
        exact = item["data"] is not None and expected == actual and expected_multi == actual_multi
        primary = item["data"] is not None and bool(expected) and bool(actual) and expected[0] == actual[0]
        flag = item["data"] is not None and expected_multi == actual_multi
        exact_count += int(exact)
        primary_count += int(primary)
        multi_flag_count += int(flag)
        for group, key in ((category_stats, str(case.get("category", "未分类"))),
                           (difficulty_stats, str(case.get("difficulty", "未分类")))):
            group[key][1] += 1
            group[key][0] += int(exact)
        expected_set, actual_set = set(expected), set(actual)
        for label in expected_set | actual_set:
            stats = label_stats[label]
            stats["support"] += int(label in expected_set)
            stats["tp"] += int(label in expected_set and label in actual_set and item["data"] is not None)
            stats["fn"] += int(label in expected_set and (label not in actual_set or item["data"] is None))
            stats["fp"] += int(label in actual_set and label not in expected_set)
        if expected and actual and expected[0] != actual[0]:
            confusion[(expected[0], actual[0])] += 1
        data = item["data"]
        if data is not None:
            timings = data.get("timings") or {}
            total_ms = timings.get("totalMs")
            if isinstance(total_ms, (int, float)):
                latencies.append(float(total_ms))
            for stage, key in (("L1累计", "l1Ms"), ("L2累计", "l2Ms"),
                               ("L1原问题", "originalL1Ms"), ("L2原问题", "originalL2Ms"),
                               ("L1改写后", "rewrittenL1Ms"), ("L2改写后", "rewrittenL2Ms"),
                               ("L3", "l3Ms"), ("改写", "rewriteMs"), ("总计", "totalMs")):
                value = timings.get(key)
                if isinstance(value, (int, float)):
                    stages[stage].append(float(value))
    return {
        "total": total, "success": len(successful), "failed": total - len(successful),
        "exact": exact_count, "primary": primary_count, "multiFlag": multi_flag_count,
        "categories": category_stats, "difficulties": difficulty_stats,
        "labels": label_stats, "confusion": confusion, "latencies": latencies, "stages": stages,
    }


def md_cell(value: Any) -> str:
    """清理 Markdown 表格单元格中的换行和分隔符。"""
    return str(value if value is not None else "").replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def percent(value: float | None) -> str:
    """格式化比例为百分数。"""
    return "N/A" if value is None else f"{value * 100:.2f}%"


def metric_table(title: str, grouped: dict[str, list[int]]) -> list[str]:
    """生成分类维度的准确率 Markdown 表格。"""
    lines = [f"### {title}", "", "| 分组 | 样本数 | 完全正确 | 准确率 |", "|---|---:|---:|---:|"]
    for name, (correct, count) in sorted(grouped.items()):
        lines.append(f"| {md_cell(name)} | {count} | {correct} | {percent(ratio(correct, count))} |")
    lines.append("")
    return lines


def build_report(dataset_path: Path, dataset: dict[str, Any], results: list[dict[str, Any]],
                 evaluated_at: datetime, endpoint: str,
                 rewrite_export_path: Path | None = None) -> str:
    """根据评测结果生成包含整体指标、错误归因和逐条明细的报告。"""
    metrics = calculate_metrics(results)
    errors = [item for item in results if not is_case_correct(item)]
    lines = [
        "# 意图识别评测报告", "",
        f"- 评测日期：{evaluated_at.strftime('%Y-%m-%d %H:%M:%S %Z')}",
        f"- 数据集路径：`{dataset_path.resolve()}`",
        f"- 数据集版本：{dataset.get('version', '未知')}",
        f"- 接口地址：`{endpoint}`", f"- Case 数量：{metrics['total']}", "",
        "## 总体指标", "",
        "| 指标 | 数值 |", "|---|---:|",
        f"| 请求成功 | {metrics['success']} / {metrics['total']} |",
        f"| 请求或响应失败 | {metrics['failed']} |",
        f"| 完全匹配准确率（全量 case，失败请求按未命中计） | {metrics['exact']} / {metrics['total']}（{percent(ratio(metrics['exact'], metrics['total']))}） |",
        f"| 完全匹配准确率（接口成功样本） | {metrics['exact']} / {metrics['success']}（{percent(ratio(metrics['exact'], metrics['success']))}） |",
        f"| 主意图准确率（接口成功样本） | {metrics['primary']} / {metrics['success']}（{percent(ratio(metrics['primary'], metrics['success']))}） |",
        f"| 多意图标记准确率（接口成功样本） | {metrics['multiFlag']} / {metrics['success']}（{percent(ratio(metrics['multiFlag'], metrics['success']))}） |", "",
        "## 分意图指标", "",
        "以下 precision、recall、F1 按意图标签采用 one-vs-rest 统计；准确率为该意图样本的完全匹配率。", "",
        "| 意图 | 样本数 | 完全正确 | 准确率 | Precision | Recall | F1 |", "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for label, stats in sorted(metrics["labels"].items()):
        precision_value = ratio(stats["tp"], stats["tp"] + stats["fp"])
        recall_value = ratio(stats["tp"], stats["tp"] + stats["fn"])
        f1 = ratio(2 * precision_value * recall_value, precision_value + recall_value) if precision_value is not None and recall_value is not None and precision_value + recall_value else None
        exact_for_label = sum(1 for item in results if label in item["case"]["expected"]["intents"] and
                              item["data"] is not None and item["case"]["expected"]["intents"] == prediction(item)[0] and
                              bool(item["case"]["expected"].get("isMultiIntent", len(item["case"]["expected"]["intents"]) > 1)) == prediction(item)[1])
        lines.append(f"| {label} | {stats['support']} | {exact_for_label} | {percent(ratio(exact_for_label, stats['support']))} | {percent(precision_value)} | {percent(recall_value)} | {percent(f1)} |")
    lines.extend(["", "## 错误意图总结", ""])
    if not errors:
        lines.append("本次没有识别错误或请求失败。")
    else:
        lines.extend(["| 错误类型 / 期望 → 实际 | 数量 |", "|---|---:|"])
        error_counts: Counter[str] = Counter()
        for item in errors:
            if item["error"]:
                key = f"请求失败：{item['error']}"
            else:
                expected = item["case"]["expected"]["intents"]
                actual, _ = prediction(item)
                key = f"{' + '.join(expected) or '无意图'} → {' + '.join(actual) or '无意图'}"
            error_counts[key] += 1
        for key, count in error_counts.most_common():
            lines.append(f"| {md_cell(key)} | {count} |")
    lines.extend(["", "## 错误详情", ""])
    if not errors:
        lines.append("本次没有识别错误或请求失败。")
    else:
        lines.extend([
            "| ID | Category | 原始问题 | 改写问题 | 期望意图 | 实际意图 | 判定 | 命中层级 | 错误信息 |",
            "|---|---|---|---|---|---|---|---|---|",
        ])
        for item in errors:
            case, data = item["case"], item["data"]
            actual = prediction(item)[0] if data else []
            lines.append("| " + " | ".join(md_cell(value) for value in [
                case.get("id", ""), case.get("category", ""), case["question"],
                rewritten_question(item), ", ".join(case["expected"]["intents"]),
                ", ".join(actual), "正确" if is_case_correct(item) else "错误",
                (data or {}).get("hitLevel", "-"), item["error"] or "",
            ]) + " |")
    lines.extend(["", "## 按样本属性统计", ""])
    lines.extend(metric_table("Category", metrics["categories"]))
    lines.extend(metric_table("Difficulty", metrics["difficulties"]))
    lines.extend(["## 耗时指标", "", "接口耗时采用响应中的 `timings` 字段，单位为毫秒；请求耗时另计网络往返。", "",
                  "| 指标 | 平均值 | P50 | P95 | P99 | 最大值 |", "|---|---:|---:|---:|---:|---:|"])
    def latency_row(name: str, values: list[float]) -> str:
        """格式化一个阶段的延迟统计行。"""
        if not values:
            return f"| {name} | N/A | N/A | N/A | N/A | N/A |"
        return f"| {name} | {mean(values):.2f} | {percentile(values, .50):.2f} | {percentile(values, .95):.2f} | {percentile(values, .99):.2f} | {max(values):.2f} |"
    lines.append(latency_row("总识别耗时", metrics["latencies"]))
    for stage, values in metrics["stages"].items():
        lines.append(f"| {stage} 平均耗时 | {mean(values):.2f} | - | - | - | {max(values):.2f} |" if values else f"| {stage} 平均耗时 | N/A | - | - | - | N/A |")
    network_values = [item["requestMs"] for item in results if item["requestMs"] is not None]
    lines.append(latency_row("请求往返耗时", network_values))
    lines.extend(["", "## Case 明细（全部）", "", "| ID | Category | Difficulty | 原始问题 | 改写问题 | 期望意图 | 实际意图 | 判定 | 命中层级 | L1累计 | L2累计 | L1原问题 | L2原问题 | L1改写后 | L2改写后 | L3 | 改写耗时 | 总耗时 | 错误信息 |", "|---|---|---|---|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"])
    for item in results:
        case, data = item["case"], item["data"]
        expected = case["expected"]["intents"]
        actual, _ = prediction(item) if data else ([], False)
        timings = (data or {}).get("timings") or {}
        fmt = lambda key: timings.get(key, "-")
        lines.append("| " + " | ".join(md_cell(value) for value in [
            case.get("id", ""), case.get("category", ""), case.get("difficulty", ""), case["question"],
            rewritten_question(item), ", ".join(expected), ", ".join(actual),
            "正确" if is_case_correct(item) else "错误", (data or {}).get("hitLevel", "-"),
            fmt("l1Ms"), fmt("l2Ms"), fmt("originalL1Ms"), fmt("originalL2Ms"),
            fmt("rewrittenL1Ms"), fmt("rewrittenL2Ms"), fmt("l3Ms"), fmt("rewriteMs"),
            fmt("totalMs"), item["error"] or "",
        ]) + " |")
    if rewrite_export_path:
        lines.extend(["", f"- 改写问题 JSON：`{rewrite_export_path.resolve()}`"])
    lines.append("")
    return "\n".join(lines)


def export_rewritten_cases(results: list[dict[str, Any]], output_path: Path) -> int:
    """将发生有效问题改写的样本导出为只含原始问题和改写问题的 JSON 数组。"""
    rewritten_cases = [
        {"originalQuestion": item["case"]["question"], "rewrittenQuestion": rewritten_question(item)}
        for item in results
        if rewritten_question(item)
        and rewritten_question(item) != item["case"]["question"].strip()
    ]
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(rewritten_cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return len(rewritten_cases)


def main() -> int:
    """执行评测并保存报告，错误时返回非零状态码。"""
    args = parse_args()
    try:
        dataset_path = args.dataset.expanduser().resolve()
        dataset, cases = load_dataset(dataset_path)
        endpoint = args.base_url.rstrip("/") + "/" + args.endpoint.lstrip("/")
        check_backend_port(args.base_url, args.timeout)
        print(f"后端端口可连接：{args.base_url}")
        token = args.token or os.environ.get(args.token_env)
        prefix = args.token_prefix.strip()
        headers = {args.token_header: f"{prefix} {token}".strip()} if token else {}
        if not headers:
            username = args.username or os.environ.get(args.username_env)
            password = os.environ.get(args.password_env)
            if username and password:
                headers = login(args.base_url, args.login_endpoint, username, password,
                                args.token_header, args.timeout)
                print("已通过登录接口获取评测令牌（令牌不会写入报告）")
            else:
                print("未提供认证令牌；接口若要求 ADMIN 权限将记录为请求失败", file=sys.stderr)
        started_at = datetime.now().astimezone()
        results = evaluate_cases(cases, endpoint, headers, args.timeout, args.sleep_ms, args.stop_on_error)
        report_path = args.report or (Path(__file__).resolve().parents[1] / "report" /
                                      f"intent-evaluation-report-{started_at.strftime('%Y%m%d-%H%M%S')}.md")
        report_path = report_path.expanduser().resolve()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        rewrite_path = args.rewrites_output or (dataset_path.parent /
                                                f"intent-rewritten-cases-{started_at.strftime('%Y%m%d-%H%M%S')}.json")
        rewrite_path = rewrite_path.expanduser().resolve()
        rewrite_count = export_rewritten_cases(results, rewrite_path)
        report_path.write_text(build_report(dataset_path, dataset, results, started_at, endpoint, rewrite_path), encoding="utf-8")
        metrics = calculate_metrics(results)
        print(f"报告已生成：{report_path}")
        print(f"改写问题已导出：{rewrite_path}（{rewrite_count} 条）")
        print(f"完全匹配准确率：{metrics['exact']}/{metrics['total']} ({percent(ratio(metrics['exact'], metrics['total']))})；请求失败：{metrics['failed']}")
        return 1 if metrics["failed"] else 0
    except (OSError, ValueError, json.JSONDecodeError, RuntimeError) as error:
        print(f"评测失败：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
