import email, sys, collections
from email import policy
m = email.message_from_binary_file(open(sys.argv[1],'rb'), policy=policy.default)
c = collections.Counter()
for part in m.walk():
    ct = part.get_content_type(); loc = part.get('Content-Location','')
    c[ct]+=1
    if ct.startswith('image/'): print(ct, len(part.get_payload(decode=True) or b''), loc[:160])
print(c)
