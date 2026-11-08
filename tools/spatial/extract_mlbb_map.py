#!/usr/bin/env python3
"""Offline: build the MLBB landmark/region file from the Imperial 3D reference model.

Runs on a computer, never in the app. Reads the GLB from the reference-assets branch
(reference-library/moba-games/mobile-legends/maps/mlbb-map-imperial-3d.glb), takes the
battlefield objects' positions from the model and maps them into the app's normalised minimap
space (0..1 between Layout mapTL and mapBR; your base bottom-left) with a similarity transform
(rotation + uniform scale + translation, no mirror) fitted on the 20 turret/base positions
measured on the owner's minimap screenshot MLBB-HUD-005.

Owner decisions (2026-11-08): only the small 2D data enters the app; private research use only.
The model's mesh names indicate geometry extracted from the game, so its CC BY 4.0 declaration may
not cover the game's rights: before any public release this file must be rebuilt from the owner's
own minimap screenshots (MINIMAP_POINTS below is already that measurement for the 20 structures).

Only layers verified against the minimap are exported (bases, turrets, objective pits, buff camps,
small camps that match a minimap camp icon). Not exported: monster identities from the model (the
pit monsters are swapped in the file), and the river/road/floor layers (misplaced at the origin).

Usage: python3 extract_mlbb_map.py MODEL.glb OUT.json
"""
import json, struct, sys
import numpy as np

# minimap measurements on MLBB-HUD-005 (2712x1220), in screen pixels; ordered from the base outwards
MINIMAP_POINTS = {
    "ally_base": (158, 342), "enemy_base": (462, 40),
    "ally_mid": [(195, 312), (227, 270), (267, 228)], "enemy_mid": [(426, 73), (394, 115), (354, 157)],
    "ally_exp": [(138, 287), (136, 186), (134, 106)], "ally_gold": [(220, 368), (292, 373), (402, 371)],
    "enemy_exp": [(401, 16), (329, 12), (219, 15)], "enemy_gold": [(483, 98), (485, 200), (487, 279)],
}
SCREEN = (2712, 1220)
MAP_TL, MAP_BR = (0.04, 0.0), (0.19, 0.322)        # app defaults (core/Layout.kt mapTL / mapBR)
# small minimap camp icons (green dots / cyan diamonds) measured on MLBB-HUD-005, screen pixels
MINIMAP_CAMPS = [(266, 66), (308, 100), (182, 164), (442, 217), (314, 281), (358, 311),   # green dots
                 (290, 74), (201, 170), (421, 210), (331, 305)]                          # cyan diamonds

# model group name -> (id, kind, side, label); model blue = the owner's side in the fitted frame
MODEL = {
    "bluehomeTowernew_049_add (merge)": ("ally_base", "BASE", "ALLY", "your base"),
    "redhomeTowernew_049_add (merge)": ("enemy_base", "BASE", "ENEMY", "enemy base"),
}
for side, mside in (("BLUE", "ally"), ("RED", "enemy")):
    for lane, applane in (("MID", "mid"), ("TOP", "gold"), ("BOT", "exp")):   # model TOP = app gold lane
        for k in (1, 2, 3):
            MODEL[f"{side}_TOWER_{lane}0{k}"] = (f"{mside}_{applane}_turret_{k}", "TURRET", mside.upper(),
                                                f"{'your' if mside == 'ally' else 'enemy'} {applane} turret {k}")
PITS = {"mon_newtortoise_049_add (merge)": "lord_pit", "mon_newfallenrider_049_add (merge)": "turtle_pit"}  # pit position only
BUFFS = {"mon_purplebuff_049_add (merge)": ("ally_purple_buff", "ALLY"), "REDmon_purplebuff_049_add (merge).001": ("enemy_purple_buff", "ENEMY"),
         "mon_orangebuff_049_add (merge)": ("ally_orange_buff", "ALLY"), "RED_mon_orangebuff_049_add (merge)": ("enemy_orange_buff", "ENEMY")}
CAMPS = ["mon_Vampire_049_add (merge)", "REDmon_Vampire_049_add (merge).001", "mon_baoxiangguai_049_add (merge)",
         "REDmon_baoxiangguai_049_add (merge).001", "mon_jiachong_049_add (merge)", "REDmon_jiachong_049_add (merge).001",
         "mon_xiyiren_049_add (merge)", "REDmon_xiyiren_049_add (merge).001"]


def load_groups(path):
    b = open(path, "rb").read()
    assert b[:4] == b"glTF"
    jl = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + jl])
    N, A = j["nodes"], j["accessors"]

    def local(n):
        if "matrix" in n:
            return np.array(n["matrix"]).reshape(4, 4).T
        T = np.eye(4); R = np.eye(4); S = np.eye(4)
        if "translation" in n: T[:3, 3] = n["translation"]
        if "rotation" in n:
            x, y, z, w = n["rotation"]
            R[:3, :3] = [[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]]
        if "scale" in n: S[0, 0], S[1, 1], S[2, 2] = n["scale"]
        return T @ R @ S
    W = {}
    def walk(i, M):
        W[i] = M @ local(N[i])
        for c in N[i].get("children", []): walk(c, W[i])
    for r in j["scenes"][j.get("scene", 0)]["nodes"]: walk(r, np.eye(4))
    def sub(i):
        o = [i]
        for c in N[i].get("children", []): o += sub(c)
        return o
    out = {}
    root = next(i for i, n in enumerate(N) if n.get("name") == "RootNode")
    for g in N[root].get("children", []):
        pts = []
        for k in sub(g):
            n = N[k]
            if "mesh" not in n: continue
            if "skin" in n:
                pts += [W[jn][:3, 3] for jn in j["skins"][n["skin"]]["joints"]]
            else:
                for p in j["meshes"][n["mesh"]]["primitives"]:
                    a = A[p["attributes"]["POSITION"]]; mn, mx = a["min"], a["max"]
                    c = np.array([[x, y, z, 1] for x in (mn[0], mx[0]) for y in (mn[1], mx[1]) for z in (mn[2], mx[2])])
                    pts += list((c @ W[k].T)[:, :3])
        if pts:
            P = np.array(pts); c = (P.min(0) + P.max(0)) / 2
            out[N[g].get("name")] = (float(c[0]), float(c[2]))      # Y is up: the battlefield is the X-Z plane
    return out


def to_norm(p):
    x = (p[0] / SCREEN[0] - MAP_TL[0]) / (MAP_BR[0] - MAP_TL[0])
    y = (p[1] / SCREEN[1] - MAP_TL[1]) / (MAP_BR[1] - MAP_TL[1])
    return (x, y)


def main(glb, out_path):
    g = load_groups(glb)
    ids, X, Y = [], [], []
    mp = MINIMAP_POINTS
    targets = {"ally_base": mp["ally_base"], "enemy_base": mp["enemy_base"]}
    for side in ("ally", "enemy"):
        for lane in ("mid", "exp", "gold"):
            for k, pt in enumerate(mp[f"{side}_{lane}"], 1): targets[f"{side}_{lane}_turret_{k}"] = pt
    for name, (lid, *_rest) in MODEL.items():
        ids.append(lid); X.append(g[name]); Y.append(to_norm(targets[lid]))
    X, Y = np.array(X), np.array(Y)
    mx, my = X.mean(0), Y.mean(0)
    U, S, Vt = np.linalg.svd((X - mx).T @ (Y - my)); R = U @ Vt
    assert np.linalg.det(R) > 0, "fit needs a mirror: lane assignment wrong"
    s = S.sum() / ((X - mx) ** 2).sum()
    f = lambda p: s * (np.array(p) - mx) @ R + my
    fit_res = np.linalg.norm(f(X) - Y, axis=1)

    lm = []
    def add(lid, kind, side, label, p, check=None):
        q = f(p); e = {"id": lid, "kind": kind, "side": side, "label": label, "x": round(float(q[0]), 4), "y": round(float(q[1]), 4)}
        if check is not None: e["check"] = check
        lm.append(e)
    for name, (lid, kind, side, label) in MODEL.items():
        add(lid, kind, side, label, g[name], {"minimap_error": round(float(fit_res[ids.index(lid)]), 4), "method": "fit point"})
    for name, lid in PITS.items():
        add(lid, "MAJOR_OBJECTIVE", "NEUTRAL", lid.replace("_", " "), g[name])
    for name, (lid, side) in BUFFS.items():
        add(lid, "BUFF_CAMP", side, lid.replace("_", " "), g[name])
    camps = [to_norm(c) for c in MINIMAP_CAMPS]
    n = 0
    for name in CAMPS:
        q = f(g[name]); d = min(float(np.hypot(q[0]-c[0], q[1]-c[1])) for c in camps)
        if d <= 0.05:   # keep only camps that match a minimap camp icon
            n += 1
            side = "ENEMY" if name.startswith("RED") else "ALLY"
            add(f"{side.lower()}_camp_{n}", "CAMP", side, "jungle camp", g[name], {"minimap_error": round(d, 4), "method": "nearest minimap camp icon"})

    doc = {
        "game": "mlbb", "map": "5v5 classic battlefield", "format_version": 1,
        "coordinates": "normalised minimap: x 0..1 left to right, y 0..1 top to bottom between Layout mapTL and mapBR; your base bottom-left",
        "distribution": "PRIVATE RESEARCH USE ONLY - do not include in a public release (see NOTICE.md)",
        "source": {"model": "MLBB MAP IMPERIAL FREE DOWNLOAD by aldrinnacaya45, Sketchfab, declared CC BY 4.0 (MLBB-MAP-005, sha256 4032187c...a8e2bc)",
                   "fit": "similarity transform on 20 structures measured on minimap screenshot MLBB-HUD-005",
                   "fit_rms": round(float(np.sqrt((fit_res ** 2).mean())), 4), "fit_max": round(float(fit_res.max()), 4)},
        "landmarks": lm,
    }
    json.dump(doc, open(out_path, "w"), indent=1)
    print(f"{len(lm)} landmarks; fit RMS {doc['source']['fit_rms']} max {doc['source']['fit_max']} (normalised units)")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
