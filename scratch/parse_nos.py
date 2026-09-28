import re

with open(r'C:\Users\Daniel\.gemini\antigravity\brain\ec660990-b32f-417b-a3c6-07bc2d36c21f\.system_generated\steps\3025\content.md', 'r', encoding='utf-8') as f:
    text = f.read()

found = set()
for m in re.finditer(r'(RTP\s*\d|SIC|TVI|Sport\s*TV|DAZN|Eleven|TVCine|Hollywood|Cinemundo|Canal\s*11|Porto\s*Canal|Benfica\s*TV|A\s*Bola\s*TV|Eurosport|AMC|AXN|Star\s*Channel|Star\s*Movies|NOS\s*Studios)[^<\n,"]*', text, re.IGNORECASE):
    item = m.group(0).strip()
    if len(item) < 40 and not any(x in item.lower() for x in ['script', 'function', 'class', 'style', 'var ']):
        found.add(item)

for ch in sorted(found):
    print(ch)
