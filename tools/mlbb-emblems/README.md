# MLBB emblem data from the Wiki (Phase 13 D1)

The roadmap allows emblem data from **one source only**: the Mobile Legends Wiki Emblems page (https://mobile-legends.fandom.com/wiki/Emblems). These scripts rebuild `app/src/main/assets/mlbb/emblems/` from a capture of that page.

1. Save the page as MHTML. Keep the capture in a folder of its own: it is untrusted data.
2. Run `python3 -I html.py page.mht main.html`. It writes the page's HTML and prints the image names it finds.
3. Run `python3 -I totext.py main.html main.html.txt`. It writes the article body as plain lines.
4. Run `python3 -I icons.py main.html icons/`. It fetches each icon from the Wiki's image server at the address the capture names, at full size. They arrive as WebP.
5. Run `python3 -I build.py main.html.txt icons/_fetched.json icons/ app/src/main/assets/mlbb/emblems`.
6. Look at every icon next to its name before committing. `MlbbEmblemsTest` checks the mapping and the files.

Update `source.captured` and `source.iconsFetched` in `emblems.json` to the new dates.
