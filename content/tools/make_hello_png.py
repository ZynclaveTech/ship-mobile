"""Generates hello.png (600x300): a simple geometric scene, no dependencies.

Usage: python3 make_hello_png.py OUT.png   (Python standard library only)
"""
import struct
import sys
import zlib

W, H = 600, 300


def pixel(x, y):
    t = y / H  # vertical blue gradient
    r, g, b = int(59 + 120 * t), int(108 + 100 * t), int(246 - 10 * t)
    # sun
    if (x - 460) ** 2 + (y - 90) ** 2 < 45 ** 2:
        return 255, 214, 102
    # rolling hills
    if y > 200 + 25 * ((x - 150) / 150) ** 2 - 25 and x < 300:
        return 40, 92, 200
    if y > 215 + 20 * ((x - 450) / 150) ** 2 - 20 and x >= 300:
        return 28, 70, 160
    # triangle
    if 180 < y < 250 and abs(x - 300) < (y - 180) * 0.9:
        return 255, 255, 255
    return r, g, b


def png(path):
    raw = b"".join(b"\x00" + bytes(c for x in range(W) for c in pixel(x, y)) for y in range(H))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(data)


if __name__ == "__main__":
    png(sys.argv[1])
