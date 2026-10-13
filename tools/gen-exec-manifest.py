#!/usr/bin/env python3
"""从 Termux deb 缓存提取"可执行文件清单"。

背景：Windows 文件系统没有 Unix 权限位，extract_deb.py 解包时 exec 位丢失
（Git Bash / bsdtar 重新打包时按扩展名猜测权限，无扩展名的 ELF 一律给 644），
导致设备端 git-remote-http 等辅助程序无法执行、git clone 直接失败。

本脚本直接从原始 deb（tar 头里完整保留了 mode）提取可执行文件列表，随 APK
作为 asset 分发，由 RuntimeInstaller 在解包后按清单恢复权限。

用法：
    python tools/gen-exec-manifest.py <deb 缓存目录> <输出清单路径>
输出：相对 toolchain/usr 的路径列表，每行一个（LF）。
"""
import io
import sys
import tarfile
from pathlib import Path

TERMUX_USR = "data/data/com.termux/files/usr/"


def parse_ar(path):
    """解析 ar 归档，返回 [(name, data), ...]。"""
    members = []
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            return members
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode("utf-8", "replace").strip()
            try:
                size = int(hdr[48:58].decode("utf-8", "replace").strip())
            except ValueError:
                break
            data = f.read(size)
            if size % 2:
                f.read(1)
            members.append((name, data))
    return members


def manifest_for_deb(deb_path):
    """返回该 deb 中可执行文件的相对路径集合；压缩格式不支持时返回 None。"""
    for name, data in parse_ar(deb_path):
        if not name.startswith("data.tar"):
            continue
        try:
            tf = tarfile.open(fileobj=io.BytesIO(data))
        except Exception:
            return None
        out = set()
        with tf:
            for m in tf.getmembers():
                if not m.isfile() or not (m.mode & 0o111):
                    continue
                n = m.name.lstrip("./").lstrip("/")
                if n.startswith(TERMUX_USR):
                    out.add(n[len(TERMUX_USR):])
        return out
    return set()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    cache = Path(sys.argv[1])
    out_file = Path(sys.argv[2])

    all_exec = set()
    bad = []
    debs = sorted(cache.glob("*.deb"))
    for deb in debs:
        got = manifest_for_deb(deb)
        if got is None:
            bad.append(deb.name)
            continue
        all_exec |= got

    out_file.parent.mkdir(parents=True, exist_ok=True)
    with out_file.open("w", encoding="utf-8", newline="\n") as f:
        for p in sorted(all_exec):
            f.write(p + "\n")

    print("扫描 %d 个 deb，提取可执行文件 %d 个 → %s" % (len(debs), len(all_exec), out_file))
    if bad:
        print("警告：%d 个包无法解析（压缩格式不支持），需人工确认：" % len(bad))
        for n in bad[:10]:
            print("  -", n)


if __name__ == "__main__":
    main()
