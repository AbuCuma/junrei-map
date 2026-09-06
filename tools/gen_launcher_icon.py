#!/usr/bin/env python3
"""由 store/icon-source.png 生成启动图标的全部资源。

纯标准库(zlib/struct),不依赖 PIL / ImageMagick —— 任何机器都能重跑,产物可复现。

产物:
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_fg.png    adaptive icon 前景层
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_mono.png  Android 13 主题图标(单色层)
  store/icon-512.png                                            Play 商店用 512×512 全出血图

几何:adaptive icon 画布 108dp,系统遮罩的**安全区**是中央直径 66dp 的圆(61%)。
源图里图钉主体占画布 66%×81%,直接放进去会被圆形遮罩裁掉尖角与上缘,所以按主体高度
缩到画布的 BODY_FRACTION,并以**主体中心**(不是画布中心 —— 星点让整体重心偏下)对齐画布中心。
星点是装饰,允许略出安全区。

用法:python3 tools/gen_launcher_icon.py
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "store" / "icon-source.png"
RES = ROOT / "app" / "src" / "main" / "res"
STORE_ICON = ROOT / "store" / "icon-512.png"

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108
BODY_FRACTION = 0.45  # 图钉主体高 / 前景层画布边长(真机反馈:0.60 偏满,四周留白要多)
STORE_BODY_FRACTION = 0.62  # 商店图无遮罩,可以比前景层满一些
BACKGROUND_RGB = (255, 255, 255)

# 图钉主体(红色轮廓)在源图里的外接框,由 detect_body() 实测;这里作为常量记录以便核对。
BODY_COLOR = lambda r, g, b: r > 180 and g < 90 and b < 90  # noqa: E731


# ---------------------------------------------------------------- PNG I/O

def read_png(path: Path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos, idat, meta = 8, b"", None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            meta = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
    width, height, depth, color_type, _, _, interlace = meta
    assert depth == 8 and interlace == 0, "only 8-bit non-interlaced PNG"
    channels = {2: 3, 6: 4}[color_type]
    raw = zlib.decompress(idat)
    stride = width * channels
    out = bytearray(height * stride)
    prev = bytearray(stride)
    src = 0
    for y in range(height):
        filt = raw[src]
        src += 1
        line = bytearray(raw[src:src + stride])
        src += stride
        if filt == 1:
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 255
        elif filt == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif filt == 3:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif filt == 4:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                b = prev[x]
                c = prev[x - channels] if x >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pred) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    if channels == 3:  # 统一成 RGBA
        rgba = bytearray(width * height * 4)
        for i in range(width * height):
            rgba[i * 4:i * 4 + 3] = out[i * 3:i * 3 + 3]
            rgba[i * 4 + 3] = 255
        out = rgba
    return width, height, bytes(out)


def write_png(path: Path, width: int, height: int, rgba: bytes, opaque: bool = False):
    channels = 3 if opaque else 4
    stride = width * channels
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        if opaque:
            row = rgba[y * width * 4:(y + 1) * width * 4]
            raw += bytes(row[i] for i in range(len(row)) if i % 4 != 3)
        else:
            raw += rgba[y * stride:(y + 1) * stride]

    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2 if opaque else 6, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    )


# ---------------------------------------------------------------- 几何

def detect_body(width: int, height: int, rgba: bytes):
    """图钉主体(红色轮廓)的外接框与中心。"""
    minx = miny = 10 ** 9
    maxx = maxy = -1
    for y in range(height):
        row = y * width * 4
        for x in range(width):
            i = row + x * 4
            if rgba[i + 3] > 16 and BODY_COLOR(rgba[i], rgba[i + 1], rgba[i + 2]):
                minx, maxx = min(minx, x), max(maxx, x)
                miny, maxy = min(miny, y), max(maxy, y)
    return (minx, miny, maxx, maxy), ((minx + maxx) / 2.0, (miny + maxy) / 2.0)


def composite(
    src_w: int, src_h: int, src: bytes,
    canvas: int, scale: float, body_center: tuple[float, float],
    background: tuple[int, int, int] | None,
) -> bytes:
    """把源图按 scale 缩放、以主体中心对齐画布中心合成。面积平均采样(预乘 alpha)。"""
    cx, cy = body_center
    half = canvas / 2.0
    out = bytearray(canvas * canvas * 4)
    inv = 1.0 / scale
    for dy in range(canvas):
        sy0 = cy + (dy - half) * inv
        sy1 = sy0 + inv
        y0, y1 = max(0, int(sy0)), min(src_h, int(sy1) + 1)
        for dx in range(canvas):
            sx0 = cx + (dx - half) * inv
            sx1 = sx0 + inv
            x0, x1 = max(0, int(sx0)), min(src_w, int(sx1) + 1)
            r = g = b = a = 0.0
            weight = 0.0
            for sy in range(y0, y1):
                wy = min(sy + 1, sy1) - max(sy, sy0)
                if wy <= 0:
                    continue
                row = sy * src_w * 4
                for sx in range(x0, x1):
                    wx = min(sx + 1, sx1) - max(sx, sx0)
                    if wx <= 0:
                        continue
                    w = wx * wy
                    i = row + sx * 4
                    alpha = src[i + 3] / 255.0
                    r += src[i] * alpha * w
                    g += src[i + 1] * alpha * w
                    b += src[i + 2] * alpha * w
                    a += alpha * w
                    weight += w
            o = (dy * canvas + dx) * 4
            if weight <= 0 or a <= 0:
                if background is not None:
                    out[o:o + 4] = bytes((*background, 255))
                continue
            cov = a / weight  # 覆盖率
            rr, gg, bb = r / a, g / a, b / a  # 去预乘
            if background is None:
                out[o:o + 4] = bytes((int(rr + 0.5), int(gg + 0.5), int(bb + 0.5), int(cov * 255 + 0.5)))
            else:
                out[o] = int(rr * cov + background[0] * (1 - cov) + 0.5)
                out[o + 1] = int(gg * cov + background[1] * (1 - cov) + 0.5)
                out[o + 2] = int(bb * cov + background[2] * (1 - cov) + 0.5)
                out[o + 3] = 255
    return bytes(out)


def monochrome(canvas: int, fg: bytes) -> bytes:
    """单色层:形状取前景 alpha,颜色由系统着色,所以填黑即可。"""
    out = bytearray(canvas * canvas * 4)
    for i in range(canvas * canvas):
        out[i * 4 + 3] = fg[i * 4 + 3]
    return bytes(out)


def main():
    src_w, src_h, src = read_png(SOURCE)
    body_box, body_center = detect_body(src_w, src_h, src)
    body_h = body_box[3] - body_box[1] + 1
    print(f"source {src_w}x{src_h}, body box {body_box}, center {body_center}")

    for name, factor in DENSITIES.items():
        canvas = int(CANVAS_DP * factor)
        scale = BODY_FRACTION * canvas / body_h
        fg = composite(src_w, src_h, src, canvas, scale, body_center, background=None)
        write_png(RES / f"mipmap-{name}" / "ic_launcher_fg.png", canvas, canvas, fg)
        write_png(RES / f"mipmap-{name}" / "ic_launcher_mono.png", canvas, canvas, monochrome(canvas, fg))
        print(f"  {name}: {canvas}px, scale {scale:.4f}")

    store = 512
    scale = STORE_BODY_FRACTION * store / body_h
    write_png(STORE_ICON, store, store, composite(src_w, src_h, src, store, scale, body_center, BACKGROUND_RGB), opaque=True)
    print(f"  store: {store}px")


if __name__ == "__main__":
    main()
