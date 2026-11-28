import json, re, sys, os, shutil
txt_path, fetched_path, icons_dir, out_dir = sys.argv[1:5]
t = open(txt_path).read()
fetched = {r["name"]: r for r in json.load(open(fetched_path))}
lines = [l.rstrip() for l in t.split("\n")]
sets = []; cur = None; section = None; pending = None
NAME_ALIAS = {"Inspire": "Inspire (Talent)", "Weapon Master": "Weapon Master (Talent)"}
for raw in lines:
    l = raw.strip()
    m = re.match(r"### (.+) Set$", l)
    if m:
        cur = {"name": m.group(1), "attributes": [], "talents": []}; sets.append(cur); section = None; continue
    if l.startswith("### "): cur = None; continue
    if cur is None or not l: continue
    if l == cur["name"]: continue
    if l.endswith("Attributes"): section = "attr"; continue
    if l.endswith("Standard Talents"): section = "standard"; cur.setdefault("_tier", 0); cur["_tier"] += 1; continue
    if l.endswith("Core Talents") or l.endswith("Core Talent"): section = "core"; continue
    if section == "attr":
        cur["attributes"].append(l); continue
    if section in ("standard", "core"):
        nm = NAME_ALIAS.get(l, l)
        if nm in fetched and pending is None or (nm in fetched and pending and pending.get("description")):
            pending = {"name": l, "type": section, "tier": cur.get("_tier", 1) if section == "standard" else None, "description": ""}
            cur["talents"].append(pending); continue
        if pending is not None:
            pending["description"] = (pending["description"] + " " + l).strip()
for s in sets: s.pop("_tier", None)
os.makedirs(out_dir, exist_ok=True)
def icon_for(name):
    r = fetched[NAME_ALIAS.get(name, name)]
    out = re.sub(r"%28|%29", "", r["wikiFile"]).replace(".png", ".webp").replace("(", "").replace(")", "").lower()
    shutil.copy(os.path.join(icons_dir, r["wikiFile"]), os.path.join(out_dir, out))
    return out, r["wikiFile"], r["url"]
used = set()
for s in sets:
    s["icon"], s["wikiFile"], _ = icon_for(s["name"]); used.add(s["name"])
    for tl in s["talents"]:
        tl["icon"], tl["wikiFile"], _ = icon_for(tl["name"]); used.add(NAME_ALIAS.get(tl["name"], tl["name"]))
missing = [n for n in fetched if n not in used]
notes = []
desc = {tl["name"]: tl["description"] for s in sets for tl in s["talents"]}
if desc.get("Temporal Reign") and desc.get("Temporal Reign") == desc.get("War Cry"):
    notes.append("The Wiki gives Temporal Reign the same description as War Cry; kept as the Wiki wrote it.")
doc = {"schema": 1, "game": "mlbb",
  "source": {"page": "https://mobile-legends.fandom.com/wiki/Emblems", "site": "Mobile Legends: Bang Bang Wiki (Fandom)",
     "license": "CC BY-SA (Fandom community content)", "captured": "2026-11-10",
     "capture": "Emblems _ Mobile Legends_ Bang Bang Wiki _ Fandom.mht (supplied by the owner)",
     "iconsFetched": "2026-11-28", "iconServer": "https://static.wikia.nocookie.net/mobile-legends/images/"},
  "recommendations": {"perHero": None, "perRole": None,
     "note": "The Wiki Emblems page gives no recommended set or talents per hero or per role. Set names name a role (Custom Tank Emblem ...); that is a Wiki fact, not a recommendation."},
  "notes": notes, "sets": sets}
json.dump(doc, open(os.path.join(out_dir, "emblems.json"), "w"), indent=1, ensure_ascii=False)
print(len(sets), sum(len(s["talents"]) for s in sets), "missing:", missing)
for s in sets: print(s["name"], len(s["attributes"]), [(x["name"], x["type"], x["tier"]) for x in s["talents"]])
