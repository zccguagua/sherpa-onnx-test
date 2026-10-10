#!/usr/bin/env python3
"""下载 sherpa-onnx 离线模型到 app/src/main/assets/。

默认只下载「语音合成（TTS）」模型；「语音识别（ASR）」的 SenseVoice 模型如已存在可跳过。

用法（在项目根目录执行）：
    python download_models.py          # 只下载 TTS 模型（默认）
    python download_models.py --asr    # 只下载 ASR 模型
    python download_models.py --all    # 两个都下载

只依赖 Python 标准库，无需 pip 安装任何东西。
"""
import argparse
import os
import sys
import tarfile
import tempfile
import urllib.request

BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
MODELS = {
    # kind: (下载地址, 压缩包内顶层目录名 / assets 下的目录名)
    "tts": (
        f"{BASE_URL}/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2",
        "sherpa-onnx-vits-zh-ll",
    ),
    "asr": (
        f"{BASE_URL}/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
    ),
}

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets")


def _progress(block_num, block_size, total_size):
    done = block_num * block_size
    if total_size > 0:
        pct = min(100, done * 100 // total_size)
        sys.stdout.write(f"\r  {pct:3d}%  {done / 1e6:7.1f} / {total_size / 1e6:.1f} MB")
    else:
        sys.stdout.write(f"\r  {done / 1e6:7.1f} MB")
    sys.stdout.flush()


def fetch(kind):
    url, name = MODELS[kind]
    target = os.path.join(ASSETS_DIR, name)
    if os.path.isdir(target) and os.listdir(target):
        print(f"[{kind}] 已存在，跳过：{target}")
        return
    os.makedirs(ASSETS_DIR, exist_ok=True)
    print(f"[{kind}] 下载 {url}")
    with tempfile.TemporaryDirectory() as tmp:
        archive = os.path.join(tmp, os.path.basename(url))
        urllib.request.urlretrieve(url, archive, _progress)
        print()
        print(f"[{kind}] 解压到 {ASSETS_DIR}")
        with tarfile.open(archive, "r:bz2") as tar:
            # filter='data' 防止路径穿越；旧版本 Python 没有该参数则回退
            try:
                tar.extractall(ASSETS_DIR, filter="data")
            except TypeError:
                tar.extractall(ASSETS_DIR)
    print(f"[{kind}] 完成：{target}")


def main():
    parser = argparse.ArgumentParser(description="下载 sherpa-onnx 模型到 assets")
    parser.add_argument("--tts", action="store_true", help="下载语音合成模型（默认）")
    parser.add_argument("--asr", action="store_true", help="下载语音识别模型")
    parser.add_argument("--all", action="store_true", help="TTS 和 ASR 都下载")
    args = parser.parse_args()

    if args.all:
        jobs = ["tts", "asr"]
    elif args.asr and not args.tts:
        jobs = ["asr"]
    else:
        jobs = ["tts"]

    for kind in jobs:
        fetch(kind)


if __name__ == "__main__":
    main()
