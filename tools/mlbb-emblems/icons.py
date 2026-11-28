import re, sys, json, urllib.request, os
h = open(sys.argv[1]).read(); out = sys.argv[2]
rows = []
for t in re.findall(r'<img[^>]+>', h):
    src = re.search(r'data-src="(https://static\.wikia\.nocookie\.net/mobile-legends/images/[^"]+)"', t)
    alt = re.search(r'alt="([^"]*)"', t)
    if not src or not alt: continue
    url = re.sub(r'/scale-to-width-down/\d+', '', src.group(1).replace('&amp;', '&'))
    file = url.split('/images/')[1].split('/')[2]
    rows.append({"name": alt.group(1), "wikiFile": file, "url": url})
for r in rows:
    req = urllib.request.Request(r["url"], headers={"User-Agent": "Sphynx-asset-fetch"})
    b = urllib.request.urlopen(req, timeout=30).read()
    r["bytes"] = len(b); r["png"] = b[:8] == b'\x89PNG\r\n\x1a\n'
    open(os.path.join(out, r["wikiFile"]), "wb").write(b)
json.dump(rows, open(os.path.join(out, "_fetched.json"), "w"), indent=1)
print(len(rows), sum(1 for r in rows if r["png"]))
