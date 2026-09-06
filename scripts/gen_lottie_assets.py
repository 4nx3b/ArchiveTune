#!/usr/bin/env python3
"""
Generates the ArchiveTune Lottie JSON assets into
app/src/main/res/raw/. Hand-crafted, minimal, high-quality animations:

  - lottie_like.json            one-shot heart pop + ring burst (600 ms)
  - lottie_download_complete.json one-shot check + ring (660 ms)
  - lottie_empty_state.json     looping floating music note (1.6 s)

All fills/strokes use WHITE (1,1,1,1) with shape groups named "Color" so
call sites can recolor them at runtime via Lottie dynamic properties
(LottieProperty.Color with KeyPath("**", "Color")) to match the app theme.
Keeping the baked color white also renders acceptably on dark artwork.

Run from the repo root:  python3 scripts/gen_lottie_assets.py
"""

import json
import math
import os

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res", "raw",
)

WHITE_FILL = [1.0, 1.0, 1.0, 1.0]


def smooth_path(verts, sharp=()):
    """Builds a closed Lottie bezier shape dict from vertex list.
    Handles are 1/3 of the segment delta; indices in `sharp` get zero-length
    handles (hard corners)."""
    n = len(verts)
    i_handles, o_handles = [], []
    for j in range(n):
        prev = verts[(j - 1) % n]
        nxt = verts[(j + 1) % n]
        if j in sharp:
            i_handles.append([0, 0])
            o_handles.append([0, 0])
        else:
            # in-handle: 1/3 back toward prev; out-handle: 1/3 toward next
            i_handles.append([(prev[0] - verts[j][0]) / 3.0, (prev[1] - verts[j][1]) / 3.0])
            o_handles.append([(nxt[0] - verts[j][0]) / 3.0, (nxt[1] - verts[j][1]) / 3.0])
    return {"c": True, "v": verts, "i": i_handles, "o": o_handles}


def fill_group(path_dict, color=WHITE_FILL, name="Color"):
    return {
        "ty": "gr",
        "nm": name,
        "it": [
            {
                "ty": "sh",
                "nm": name,
                "ks": {"a": 0, "k": path_dict},
                "hd": False,
            },
            {"ty": "fl", "nm": "Fill", "c": {"a": 0, "k": color}, "o": {"a": 0, "k": 100}, "r": 1, "hd": False},
            {"ty": "tr", "p": {"a": 0, "k": [0, 0]}, "a": {"a": 0, "k": [0, 0]}, "s": {"a": 0, "k": [100, 100]},
             "r": {"a": 0, "k": 0}, "o": {"a": 0, "k": 100}, "sk": {"a": 0, "k": 0}, "sa": {"a": 0, "k": 0}},
        ],
    }


def stroke_group(path_dict, width, color=WHITE_FILL, name="Color", dash=None):
    items = [
        {
            "ty": "sh",
            "nm": name,
            "ks": {"a": 0, "k": path_dict},
            "hd": False,
        },
    ]
    if dash is not None:
        items.append({
            "ty": "tm",
            "nm": "Trim",
            "s": {"a": 0, "k": 0.0},
            "e": {"a": 0, "k": 100.0},
            "o": {"a": 0, "k": 0},
            "m": 1,
            "ix": 1,
            "hd": False,
        })
    items.append({
        "ty": "st", "nm": "Stroke", "c": {"a": 0, "k": color}, "o": {"a": 0, "k": 100},
        "w": {"a": 0, "k": width}, "lc": 2, "lj": 2, "bm": 0, "hd": False,
    })
    items.append({
        "ty": "tr", "p": {"a": 0, "k": [0, 0]}, "a": {"a": 0, "k": [0, 0]}, "s": {"a": 0, "k": [100, 100]},
        "r": {"a": 0, "k": 0}, "o": {"a": 0, "k": 100}, "sk": {"a": 0, "k": 0}, "sa": {"a": 0, "k": 0},
    })
    return {"ty": "gr", "nm": name, "it": items}


def shape_layer(name, shapes, ks_overrides=None, in_frame=0, out_frame=60):
    ks = {
        "o": {"a": 0, "k": 100},
        "r": {"a": 0, "k": 0},
        "p": {"a": 0, "k": [0, 0, 0]},
        "a": {"a": 0, "k": [0, 0, 0]},
        "s": {"a": 0, "k": [100, 100, 100]},
    }
    if ks_overrides:
        ks.update(ks_overrides)
    return {
        "ddd": 0, "ind": 1, "ty": 4, "nm": name, "sr": 1,
        "ks": ks, "ao": 0,
        "shapes": shapes,
        "ip": in_frame, "op": out_frame, "st": 0, "bm": 0,
    }


def anim_scale_keyframes(frames):
    """frames: list of (t, [sx, sy]) — ease in-out between each."""
    kfs = []
    n = len(frames)
    for idx, (t, s) in enumerate(frames):
        kf = {"t": t, "s": [s[0], s[1], 100]}
        if idx < n - 1:
            kf["i"] = {"x": [0.25, 0.25, 0.25], "y": [1, 1, 1]}
            kf["o"] = {"x": [0.35, 0.35, 0.35], "y": [0, 0, 0]}
        else:
            kf["i"] = {"x": [0.833, 0.833, 0.833], "y": [0.833, 0.833, 0.833]}
            kf["o"] = {"x": [0.167, 0.167, 0.167], "y": [0.167, 0.167, 0.167]}
        kfs.append(kf)
    return {"a": 1, "k": kfs}


def anim_value_keyframes(frames, dimensional=1):
    kfs = []
    n = len(frames)
    for idx, (t, v) in enumerate(frames):
        kf = {"t": t, "s": v if isinstance(v, list) else [v]}
        if idx < n - 1:
            kf["i"] = {"x": [0.25] * dimensional, "y": [1] * dimensional}
            kf["o"] = {"x": [0.35] * dimensional, "y": [0] * dimensional}
        kfs.append(kf)
    return {"a": 1, "k": kfs}


def anim_position_keyframes(frames):
    kfs = []
    n = len(frames)
    for idx, (t, p) in enumerate(frames):
        kf = {"t": t, "s": [p[0], p[1], 0], "to": [0, 0, 0], "ti": [0, 0, 0]}
        if idx < n - 1:
            kf["i"] = {"x": 0.42, "y": 1}
            kf["o"] = {"x": 0.58, "y": 0}
        kfs.append(kf)
    return {"a": 1, "k": kfs}


def base_json(name, fps, duration_frames, layers, width=240, height=240):
    return {
        "v": "5.7.4",
        "fr": fps,
        "ip": 0,
        "op": duration_frames,
        "w": width,
        "h": height,
        "nm": name,
        "ddd": 0,
        "assets": [],
        "layers": layers,
    }


# ---------------------------------------------------------------------------
# 1) lottie_like.json — heart pop + expanding ring burst (one-shot, 600ms)
# ---------------------------------------------------------------------------
def heart_verts():
    # Classic symmetric heart, ~100 units tall, centered on (0, ~6).
    return [
        [0, -26],      # top notch (sharp)
        [-16, -46],    # left lobe top
        [-38, -34],    # left lobe far
        [-40, -8],     # left side
        [0, 46],       # bottom tip (sharp)
        [40, -8],      # right side
        [38, -34],     # right lobe far
        [16, -46],     # right lobe top
    ]


def circle_verts(n=36, radius=90):
    verts = []
    for j in range(n):
        ang = 2 * math.pi * j / n
        verts.append([radius * math.cos(ang), radius * math.sin(ang)])
    return verts


def build_like():
    heart = smooth_path(heart_verts(), sharp={0, 4})
    ring = smooth_path(circle_verts(48, 86))

    heart_layer = shape_layer(
        "Heart",
        [fill_group(heart)],
        ks_overrides={
            "s": anim_scale_keyframes([
                (0, [0, 0]),
                (12, [118, 118]),
                (22, [94, 94]),
                (30, [104, 104]),
                (38, [100, 100]),
            ]),
            "p": {"a": 0, "k": [120, 126, 0]},
        },
        out_frame=36,
    )
    # Set unique layer indices + in/out frames
    heart_layer["ind"] = 1

    ring_layer = shape_layer(
        "Ring",
        [stroke_group(ring, 10, name="Color")],
        ks_overrides={
            "s": anim_scale_keyframes([
                (4, [40, 40]),
                (32, [104, 104]),
            ]),
            "o": anim_value_keyframes([
                (4, [0]),
                (10, [55]),
                (32, [0]),
            ]),
            "p": {"a": 0, "k": [120, 120, 0]},
        },
        in_frame=4,
        out_frame=36,
    )
    ring_layer["ind"] = 2

    doc = base_json("like", 60, 36, [heart_layer, ring_layer])
    return json.dumps(doc, separators=(",", ":"))


# ---------------------------------------------------------------------------
# 2) lottie_download_complete.json — ring + drawn check (one-shot, 660ms)
# ---------------------------------------------------------------------------
def build_download_complete():
    ring = smooth_path(circle_verts(48, 84))
    check = {
        "c": False,
        "v": [[-30, 2], [-8, 26], [34, -28]],
        "i": [[0, 0], [0, 0], [0, 0]],
        "o": [[0, 0], [0, 0], [0, 0]],
    }

    ring_group = stroke_group(ring, 12)
    # animate ring trim: sweep draw-on
    ring_group["it"][1] = {
        "ty": "tm", "nm": "Trim", "s": {"a": 0, "k": 0.0}, "e": {
            "a": 1, "k": [
                {"t": 0, "s": [0.0], "i": {"x": [0.3], "y": [1]}, "o": {"x": [0.5], "y": [0]}},
                {"t": 26, "s": [100.0], "i": {"x": [0.833], "y": [0.833]}, "o": {"x": [0.167], "y": [0.167]}},
            ],
        }, "o": {"a": 0, "k": -90}, "m": 1, "hd": False,
    }
    # stroke width scales down as ring completes
    ring_group["it"][2]["w"] = {
        "a": 1, "k": [
            {"t": 0, "s": [16], "i": {"x": [0.3], "y": [1]}, "o": {"x": [0.5], "y": [0]}},
            {"t": 40, "s": [9], "i": {"x": [0.833], "y": [0.833]}, "o": {"x": [0.167], "y": [0.167]}},
        ],
    }

    ring_layer = shape_layer(
        "Ring", [ring_group],
        ks_overrides={
            "s": anim_scale_keyframes([(0, [55, 55]), (18, [100, 100])]),
            "p": {"a": 0, "k": [120, 120, 0]},
        },
        out_frame=40,
    )
    ring_layer["ind"] = 1

    check_group = stroke_group(check, 14)
    check_group["it"].insert(1, {
        "ty": "tm", "nm": "CheckTrim", "s": {"a": 0, "k": 0.0}, "e": {
            "a": 1, "k": [
                {"t": 14, "s": [0.0], "i": {"x": [0.2], "y": [1]}, "o": {"x": [0.4], "y": [0]}},
                {"t": 32, "s": [100.0], "i": {"x": [0.833], "y": [0.833]}, "o": {"x": [0.167], "y": [0.167]}},
            ],
        }, "o": {"a": 0, "k": 0}, "m": 1, "hd": False,
    })
    check_layer = shape_layer(
        "Check", [check_group],
        ks_overrides={
            "s": anim_scale_keyframes([(14, [70, 70]), (32, [100, 100])]),
            "o": anim_value_keyframes([(14, [0]), (20, [100])]),
            "p": {"a": 0, "k": [120, 118, 0]},
        },
        in_frame=14,
        out_frame=40,
    )
    check_layer["ind"] = 2

    return json.dumps(base_json("download_complete", 60, 40, [ring_layer, check_layer]), separators=(",", ":"))


# ---------------------------------------------------------------------------
# 3) lottie_empty_state.json — floating music note (loop, 1.6s)
# ---------------------------------------------------------------------------
def build_empty_state():
    # Eighth note: ellipse head + stem + flag — drawn as filled shapes.
    head = smooth_path(circle_verts(24, 26))
    stem = {
        "c": True,
        "v": [[16, -10], [24, -10], [24, -84], [16, -84]],
        "i": [[0, 0], [0, 0], [0, 0], [0, 0]],
        "o": [[0, 0], [0, 0], [0, 0], [0, 0]],
    }
    flag = smooth_path(
        [
            [24, -84],
            [58, -70],
            [46, -38],
            [58, -46],
            [40, -22],
            [24, -52],
        ],
        sharp={0, 5},
    )

    note_layer = shape_layer(
        "Note",
        [fill_group(head), fill_group(stem), fill_group(flag)],
        ks_overrides={
            "p": anim_position_keyframes([
                (0, [110, 150]),
                (48, [110, 138]),
                (96, [110, 150]),
            ]),
            "o": anim_value_keyframes([(0, [60]), (48, [100]), (96, [60])]),
            "s": anim_scale_keyframes([
                (0, [96, 96]),
                (48, [104, 104]),
                (96, [96, 96]),
            ]),
        },
        out_frame=96,
    )
    note_layer["ind"] = 1

    # A second, smaller, dimmer note drifting the opposite way for depth.
    small_head = smooth_path(circle_verts(18, 17))
    small_stem = {
        "c": True,
        "v": [[11, -6], [17, -6], [17, -55], [11, -55]],
        "i": [[0, 0], [0, 0], [0, 0], [0, 0]],
        "o": [[0, 0], [0, 0], [0, 0], [0, 0]],
    }
    small_layer = shape_layer(
        "Note2",
        [fill_group(small_head), fill_group(small_stem)],
        ks_overrides={
            "p": anim_position_keyframes([
                (0, [178, 96]),
                (48, [178, 88]),
                (96, [178, 96]),
            ]),
            "o": anim_value_keyframes([(0, [35]), (48, [60]), (96, [35])]),
        },
        out_frame=96,
    )
    small_layer["ind"] = 2

    return json.dumps(base_json("empty_state", 60, 96, [small_layer, note_layer]), separators=(",", ":"))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    assets = {
        "lottie_like.json": build_like(),
        "lottie_download_complete.json": build_download_complete(),
        "lottie_empty_state.json": build_empty_state(),
    }
    for fname, data in assets.items():
        path = os.path.join(OUT_DIR, fname)
        with open(path, "w") as f:
            f.write(data)
        print(f"wrote {path} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
