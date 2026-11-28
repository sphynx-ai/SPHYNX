# Phase 13 D1: the article body of the Wiki Emblems page (main.html from html.py) as plain lines for build.py.
import re, sys, html
h = open(sys.argv[1]).read()
body = h[h.find('mw-parser-output'):]
body = body[:body.find('printfooter')] if 'printfooter' in body else body
t = re.sub(r'<(script|style)[^>]*>.*?</\1>', '', body, flags=re.S)
t = re.sub(r'<h([2-4])[^>]*>(.*?)</h\1>', lambda m: '\n\n### ' + re.sub('<[^>]+>', '', m.group(2)) + '\n', t, flags=re.S)
t = re.sub(r'<(br|/p|/tr|/li|/div)[^>]*>', '\n', t); t = re.sub(r'<td[^>]*>', ' | ', t); t = re.sub(r'<[^>]+>', '', t)
t = html.unescape(t); t = re.sub(r'\n\s*\n+', '\n', t)
open(sys.argv[2], 'w').write(t)
