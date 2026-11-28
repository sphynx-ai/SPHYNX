import email, sys, re
from email import policy
m = email.message_from_binary_file(open(sys.argv[1],'rb'), policy=policy.default)
for part in m.walk():
    loc = part.get('Content-Location','')
    if part.get_content_type()=='text/html' and 'fandom.com/wiki/Emblems' in loc:
        h = part.get_content()
        open(sys.argv[2],'w').write(h)
        print('main html', len(h), loc)
        imgs = re.findall(r'<img[^>]+>', h)
        print('img tags', len(imgs))
        names = re.findall(r'data-image-name="([^"]+)"', h)
        print('image names', len(names), names[:80])
        break
