#!/usr/bin/env python3
"""抠图ランタイム配信フォルダの manifest.json を生成する。

使い方:
    python3 tools/build_cutout_manifest.py <dist_dir> [--base-url https://host/path/]

<dist_dir> に置くファイル（名前は CutoutManifest.kt の契約と一致させる）:
    libonnxruntime.so            onnxruntime-android-qnn-<ver>.aar の jni/arm64-v8a/ から
    libQnnSystem.so, libQnnHtp.so, libQnnHtpV<arch>Skel.so, libQnnHtpV<arch>Stub.so
                                 qnn-runtime-<ver>.aar の jni/arm64-v8a/ から
    isnet_w8a16_ctx_<arch>.onnx  HTP 用 context binary（QNN SDK 版・ORT 版・HTP 世代に紐づく）
    isnet_w8a8.onnx              CPU 档

アプリ側の ORT Java（AAR）版と libonnxruntime.so の版は **必ず一致**させること。
"""
import hashlib
import json
import os
import sys


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    dist = sys.argv[1]
    base = ""
    if "--base-url" in sys.argv:
        base = sys.argv[sys.argv.index("--base-url") + 1]
        if not base.endswith("/"):
            base += "/"
    artifacts = {}
    for name in sorted(os.listdir(dist)):
        if name == "manifest.json" or name.startswith("."):
            continue
        if not (name.endswith(".so") or name.endswith(".onnx")):
            continue
        path = os.path.join(dist, name)
        artifacts[name] = {"url": base + name, "sha256": sha256(path), "size": os.path.getsize(path)}
        print(f"{name:32s} {artifacts[name]['size']:>12,d}  {artifacts[name]['sha256'][:16]}…")
    manifest = {"version": 1, "artifacts": artifacts}
    with open(os.path.join(dist, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"wrote {os.path.join(dist, 'manifest.json')} ({len(artifacts)} artifacts)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
