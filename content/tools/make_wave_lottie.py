"""Generates wave.json: three dots bouncing in a wave, shapes only (no text).

Usage: python3 make_wave_lottie.py OUT.json   (Python standard library only)
"""
import json
import sys

FR, OP = 30, 75
W, H = 240, 200
EASE = {"i": {"x": [0.5], "y": [1]}, "o": {"x": [0.5], "y": [0]}}


def static(v):
    return {"a": 0, "k": v}


def bounce(x, base, top, delay):
    """Rest until `delay`, hop up and back down over 30 frames, rest to the end."""
    frames = [(0, base), (delay, base), (delay + 15, top), (delay + 30, base), (OP, base)]
    if delay == 0:
        frames.pop(1)
    out = []
    for n, (t, y) in enumerate(frames):
        k = {"t": t, "s": [x, y, 0]}
        if n < len(frames) - 1:
            k.update(EASE)
        out.append(k)
    return {"a": 1, "k": out}


def dot(ind, x, color, delay):
    return {
        "ddd": 0, "ind": ind, "ty": 4, "nm": f"dot{ind}", "sr": 1, "ao": 0,
        "ks": {"o": static(100), "r": static(0), "a": static([0, 0, 0]),
               "s": static([100, 100, 100]), "p": bounce(x, 130, 70, delay)},
        "shapes": [{"ty": "gr", "it": [
            {"ty": "el", "p": static([0, 0]), "s": static([44, 44])},
            {"ty": "fl", "c": static(color), "o": static(100), "r": 1},
            {"ty": "tr", "p": static([0, 0]), "a": static([0, 0]), "s": static([100, 100]),
             "r": static(0), "o": static(100), "sk": static(0), "sa": static(0)},
        ]}],
        "ip": 0, "op": OP, "st": 0, "bm": 0,
    }


def build():
    colors = [[0.23, 0.42, 0.96, 1], [0.38, 0.55, 0.98, 1], [0.6, 0.72, 1, 1]]
    layers = [dot(i + 1, 50 + i * 70, colors[i], i * 5) for i in range(3)]
    return {"v": "5.7.0", "fr": FR, "ip": 0, "op": OP, "w": W, "h": H,
            "nm": "wave", "ddd": 0, "assets": [], "layers": layers}


if __name__ == "__main__":
    with open(sys.argv[1], "w") as f:
        json.dump(build(), f, separators=(",", ":"))
