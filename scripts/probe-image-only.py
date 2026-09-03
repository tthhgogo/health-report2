#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LLM-A 纯图像输入探针：把整份报告的页面图 + 提示词发给模型，看它能否输出全部所需数据。

不是生产链路的一部分，只用于人工验证。仅用标准库。

用法：
    export EXTRACTION_BASE_URL=https://your-gateway
    export EXTRACTION_MODEL=your-model
    export EXTRACTION_API_KEY=sk-xxx
    python3 scripts/probe-image-only.py --images ./样本/报告A --out ./out.json

图片按文件名自然序排列，序号即提示词里的 page（从 1 起）。
PDF 请先转图：pdftoppm -r 150 -jpeg report.pdf ./报告A/p
"""

import argparse
import base64
import json
import mimetypes
import os
import re
import sys
import time
import urllib.error
import urllib.request

SUPPORTED = (".jpg", ".jpeg", ".png", ".webp")
DEFAULT_PROMPT = "prompt/extraction-image-only-probe.md"


def natural_key(name):
    """p1 / p2 / p10 按数字排，不按字典序。"""
    return [int(part) if part.isdigit() else part.lower()
            for part in re.split(r"(\d+)", name)]


def collect_images(paths):
    files = []
    for path in paths:
        if os.path.isdir(path):
            for name in os.listdir(path):
                if name.lower().endswith(SUPPORTED):
                    files.append(os.path.join(path, name))
        else:
            files.append(path)
    files.sort(key=lambda p: natural_key(os.path.basename(p)))
    if not files:
        sys.exit("没有找到任何图片，支持的后缀：%s" % ", ".join(SUPPORTED))
    return files


def load_prompt(path, keep_header):
    """整份 md 进 system；默认裁掉 '## System' 之前的说明性抬头。"""
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    if keep_header:
        return text
    index = text.find("\n## System")
    if index < 0:
        print("[warn] 提示词里没有 '## System' 小节，整份发送", file=sys.stderr)
        return text
    return text[index + 1:]


def to_data_url(path):
    mime = mimetypes.guess_type(path)[0] or "image/jpeg"
    with open(path, "rb") as handle:
        payload = base64.b64encode(handle.read()).decode("ascii")
    return "data:%s;base64,%s" % (mime, payload)


def build_body(prompt, images, model, max_tokens, temperature):
    content = [{
        "type": "text",
        "text": ("这是一份体检报告的全部页面图像，共 %d 张，按报告顺序给出。\n"
                 "第 1 张是第 1 页，依此类推；条目里的 page 字段填的就是这个序号。\n\n"
                 "按 System 中的规则抽取，只输出那一个 JSON 对象。" % len(images)),
    }]
    for path in images:
        content.append({"type": "image_url", "image_url": {"url": to_data_url(path)}})
    body = {
        "model": model,
        "stream": False,
        "temperature": temperature,
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": content},
        ],
    }
    if max_tokens > 0:
        body["max_tokens"] = max_tokens
    return json.dumps(body, ensure_ascii=False).encode("utf-8")


def post(base_url, api_key, body, timeout):
    url = base_url.rstrip("/") + "/v1/chat/completions"
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", "Bearer " + api_key)
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:2000]
        sys.exit("HTTP %s\n%s" % (error.code, detail))
    except urllib.error.URLError as error:
        sys.exit("请求失败：%s" % error.reason)
    return raw, time.time() - started


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", nargs="+", required=True, help="图片目录或图片文件列表")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT)
    parser.add_argument("--keep-header", action="store_true",
                        help="连提示词开头的差异清单一起发送（默认裁掉）")
    parser.add_argument("--base-url", default=os.environ.get("EXTRACTION_BASE_URL", ""))
    parser.add_argument("--model", default=os.environ.get("EXTRACTION_MODEL", ""))
    parser.add_argument("--api-key", default=os.environ.get("EXTRACTION_API_KEY", ""))
    parser.add_argument("--timeout", type=int, default=900, help="读超时秒数，默认 900")
    parser.add_argument("--max-tokens", type=int, default=32768, help="0 表示不传该字段")
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--out", default="probe-output.json", help="抽取结果落盘路径")
    parser.add_argument("--dump-request", default="", help="把请求体也存一份（含 base64，很大）")
    args = parser.parse_args()

    for name in ("base_url", "model", "api_key"):
        if not getattr(args, name):
            sys.exit("缺少 --%s（或对应环境变量）" % name.replace("_", "-"))

    images = collect_images(args.images)
    prompt = load_prompt(args.prompt, args.keep_header)
    body = build_body(prompt, images, args.model, args.max_tokens, args.temperature)

    print("图片 %d 张：%s" % (len(images), ", ".join(os.path.basename(p) for p in images)))
    print("提示词 %d 字，请求体 %.1f MB" % (len(prompt), len(body) / 1024.0 / 1024.0))
    if args.dump_request:
        with open(args.dump_request, "wb") as handle:
            handle.write(body)

    raw, elapsed = post(args.base_url, args.api_key, body, args.timeout)
    print("耗时 %.1f 秒" % elapsed)

    envelope = json.loads(raw)
    usage = envelope.get("usage") or {}
    choice = (envelope.get("choices") or [{}])[0]
    message = choice.get("message") or {}
    finish = choice.get("finish_reason")
    print("finish_reason=%s  prompt=%s  completion=%s"
          % (finish, usage.get("prompt_tokens"), usage.get("completion_tokens")))
    if finish != "stop":
        print("[warn] finish_reason 不是 stop —— 输出很可能被截断，下面的 JSON 不完整",
              file=sys.stderr)

    content = message.get("content") or ""
    if not content.strip():
        content = message.get("reasoning_content") or ""
        print("[warn] content 为空，改用 reasoning_content", file=sys.stderr)
    content = re.sub(r"^\s*```(?:json)?\s*|\s*```\s*$", "", content.strip())

    with open(args.out, "w", encoding="utf-8") as handle:
        try:
            parsed = json.loads(content)
        except ValueError as error:
            handle.write(content)
            sys.exit("模型输出不是合法 JSON（%s），原文已存到 %s" % (error, args.out))
        json.dump(parsed, handle, ensure_ascii=False, indent=2)

    print("已写入 %s" % args.out)
    print("条目数：indicators=%d textualFindings=%d summaryConclusions=%d "
          "allergens=%d nutritionSupplements=%d dietRequirements=%d sections=%d"
          % tuple(len(parsed.get(key) or []) for key in
                  ("indicators", "textualFindings", "summaryConclusions", "allergens",
                   "nutritionSupplements", "dietRequirements", "sections")))
    row_count = parsed.get("allergenDataRowCount")
    allergen_count = len(parsed.get("allergens") or [])
    if row_count != allergen_count:
        print("[warn] allergenDataRowCount=%s 与 allergens 数组长度 %d 不符 —— 自检 ⑤ 未过"
              % (row_count, allergen_count), file=sys.stderr)


if __name__ == "__main__":
    main()
