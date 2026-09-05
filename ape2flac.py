#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ape2flac.py — Monkey's Audio (.ape) 无损转 FLAC，自动修复不规范 ID3 标签。

背景 / 为什么不能直接 `ffmpeg -i x.ape x.flac`
--------------------------------------------------
中文音乐站分发的 APE 文件常带「ID3v2.3 头 + syncsafe size」的不规范标签：
头部版本字节是 `0x03`（v2.3，规范要求 size 字段用普通 big-endian），但 size
字段实际按 v2.4 的 syncsafe 编码写入。FFmpeg 按版本号硬算会跳过错误的字节数，
找不到 `MAC ` 头，报：

    [in#0] Error opening input: Invalid data found when processing input

此时即便 `-f ape` 强制指定格式也无效（ID3 跳过逻辑仍会执行）。本脚本用启发式
定位真实 MAC 头偏移，再通过 `-skip_initial_bytes` 修正。

用法
----
    python ape2flac.py <输入> [输出目录] [选项]

    <输入>        单个 .ape 文件，或含 .ape 的目录
    [输出目录]    默认 <输入>/flac（单文件时取其所在目录的 flac/ 子目录）

选项
----
    --compression N   FLAC 压缩等级 0~8，默认 8（最高压缩）
    --no-verify       跳过无损性校验（默认每个文件都做 PCM md5 比对）
    --ffmpeg PATH     指定 ffmpeg 路径，默认取 PATH 中的 `ffmpeg`

无损性校验
----------
每个文件都会把「源 APE（带 -skip_initial_bytes）」与「产物 FLAC」分别解码到
`pcm_s32le` 后比对 md5，一致即证明位级无损。校验依赖 ffmpeg 能完整解码，
若源文件本身损坏会明确报失败，而不是产出假无损。

依赖
----
    - Python 3.8+
    - FFmpeg（含 ape 解码器与 flac 编码器，绝大多数发行版自带）

注意事项
--------
1. 仅支持 Monkey's Audio 3.97+（FFmpeg `apedec.c` 的下限），更老版本会报错。
2. ID3 标签偏移按文件动态探测，不假设所有文件一致——批量处理时逐文件计算。
3. 内嵌封面（mjpeg attached_pic）会被保留到 FLAC 的 PICTURE 块。
4. 输出若与输入同名（仅扩展名不同），重跑会覆盖，属预期行为。
"""

import argparse
import hashlib
import os
import struct
import subprocess
import sys


def probe_mac_offset(path):
    """返回 APE 数据（`MAC ` 魔数）的字节偏移，即需要跳过的标签长度。

    启发式：ID3v2 头可能按 v2.3（非 syncsafe）或 v2.4（syncsafe）解释 size，
    依次试探各候选值 + 0，取第一个「跳过该长度后紧跟 `MAC `」的偏移。
    """
    with open(path, "rb") as f:
        head = f.read(10)
        if head[:3] != b"ID3":
            return 0
        ver = head[3]
        b = head[6:10]
        syncsafe = ((b[0] & 0x7F) << 21) | ((b[1] & 0x7F) << 14) \
                 | ((b[2] & 0x7F) << 7) | (b[3] & 0x7F)
        plain = struct.unpack(">I", b)[0]

        # 按版本规范顺序试，再试反过来的解释，最后试无标签
        order = [plain, syncsafe] if ver >= 4 else [syncsafe, plain]
        seen = set()
        for cand in order + [0, plain, syncsafe]:
            off = 10 + cand
            if off in seen:
                continue
            seen.add(off)
            try:
                f.seek(off)
                if f.read(4) == b"MAC ":
                    return off
            except OSError:
                continue
    return 0


def pcm_md5(path, skip=0, ffmpeg="ffmpeg"):
    """把音频解码为 s32 PCM 并计算 md5，用于无损性校验。"""
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error"]
    if skip:
        cmd += ["-skip_initial_bytes", str(skip)]
    cmd += ["-i", path, "-map", "0:a", "-f", "wav",
            "-c:a", "pcm_s32le", "-"]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        return None
    return hashlib.md5(p.stdout).hexdigest()


def convert_one(src, dst, skip, compression, verify, ffmpeg):
    cmd = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error"]
    if skip:
        cmd += ["-skip_initial_bytes", str(skip)]
    cmd += [
        "-i", src,
        "-map", "0:a",
        "-map", "0:v?",                     # 内嵌封面，无则跳过
        "-c:a", "flac",
        "-compression_level", str(compression),
        "-c:v", "copy",
        "-disposition:v", "attached_pic",
        dst,
    ]
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0 or not os.path.exists(dst):
        return False, r.stderr.decode("utf-8", "replace")[:400]

    lossless = True
    if verify:
        m_src = pcm_md5(src, skip, ffmpeg)
        m_dst = pcm_md5(dst, 0, ffmpeg)
        lossless = (m_src is not None and m_src == m_dst)

    sb, db = os.path.getsize(src), os.path.getsize(dst)
    tag = "OK  " if lossless else "LOSS!"
    print(f"[{tag}] {os.path.basename(src)}")
    print(f"      id3_skip={skip}  {sb:,} B -> {db:,} B ({db / sb * 100:.1f}%)")
    if verify:
        print(f"      pcm_md5 src={pcm_md5(src, skip, ffmpeg)}")
        print(f"      pcm_md5 dst={pcm_md5(dst, 0, ffmpeg)}")
    return lossless, ""


def collect_apes(path):
    """返回要处理的 .ape 文件列表；path 可为单文件或目录。"""
    if os.path.isfile(path):
        if not path.lower().endswith(".ape"):
            sys.exit(f"错误：{path} 不是 .ape 文件")
        return [path]
    if os.path.isdir(path):
        files = sorted(
            os.path.join(path, n) for n in os.listdir(path)
            if n.lower().endswith(".ape")
        )
        if not files:
            sys.exit(f"错误：{path} 目录下没有 .ape 文件")
        return files
    sys.exit(f"错误：{path} 不是有效的文件或目录")


def default_outdir(path):
    if os.path.isfile(path):
        return os.path.join(os.path.dirname(path), "flac")
    return os.path.join(path, "flac")


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Monkey's Audio (.ape) 无损转 FLAC（修复不规范 ID3 标签）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("input", help="单个 .ape 文件，或含 .ape 的目录")
    ap.add_argument("output", nargs="?", help="输出目录（默认 <输入>/flac）")
    ap.add_argument("--compression", type=int, default=8, choices=range(0, 9),
                    help="FLAC 压缩等级 0~8（默认 8）")
    ap.add_argument("--no-verify", action="store_true",
                    help="跳过无损性校验")
    ap.add_argument("--ffmpeg", default="ffmpeg",
                    help="ffmpeg 路径（默认取 PATH 中的 ffmpeg）")
    args = ap.parse_args(argv)

    outdir = args.output or default_outdir(args.input)
    os.makedirs(outdir, exist_ok=True)

    files = collect_apes(args.input)
    print(f"共 {len(files)} 个 .ape 文件，输出到 {outdir}\n")

    ok, failed = 0, []
    for src in files:
        dst = os.path.join(outdir, os.path.basename(src)[:-4] + ".flac")
        skip = probe_mac_offset(src)
        success, err = convert_one(
            src, dst, skip, args.compression,
            not args.no_verify, args.ffmpeg,
        )
        if success:
            ok += 1
        else:
            failed.append((os.path.basename(src), err))
        print()

    print(f"===== 完成：{ok} 成功 / {len(failed)} 失败 =====")
    for name, err in failed:
        print(f"  FAILED: {name}\n          {err}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
