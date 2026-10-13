#!/usr/bin/env python3
"""解包 .deb（ar 归档）中的 data.tar.* 到目标目录（Termux prefix 布局）。"""
import io
import os
import sys
import tarfile


def parse_ar(path):
    members = []
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise ValueError("not an ar archive: %s" % path)
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode("utf-8", "replace").strip()
            size = int(hdr[48:58].decode("utf-8", "replace").strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            members.append((name, data))
    return members


def main():
    outdir = os.path.abspath(sys.argv[1])
    deb = sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    for name, data in parse_ar(deb):
        if name.startswith("data.tar"):
            tf = tarfile.open(fileobj=io.BytesIO(data))
            for m in tf.getmembers():
                target = os.path.abspath(os.path.join(outdir, m.name.lstrip("/")))
                if not target.startswith(outdir):
                    continue
                try:
                    tf.extract(m, outdir, filter="data")
                except Exception:
                    pass
            print("    extracted: %s (%s members)" % (os.path.basename(deb), len(tf.getmembers())))
            return
    raise RuntimeError("no data.tar in %s" % deb)


if __name__ == "__main__":
    main()
