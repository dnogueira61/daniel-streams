import json

with open('app/src/main/assets/channels.json', 'r', encoding='utf-8') as f:
    channels = json.load(f)

# Official NOS / Portugal TV channels priority list
# We map keywords and desired clean names, category and order
nos_order = [
    # 1. Generalistas
    ("RTP 1", "Generalistas", ["rtp 1", "rtp1"]),
    ("RTP 2", "Generalistas", ["rtp 2", "rtp2"]),
    ("SIC", "Generalistas", ["sic", "sic tv"]),
    ("TVI", "Generalistas", ["tvi", "tvi tv"]),
    ("SIC Notícias", "Generalistas", ["sic notícias", "sic noticias"]),
    ("RTP 3", "Generalistas", ["rtp 3", "rtp3", "rtp informação"]),
    ("CNN Portugal", "Generalistas", ["cnn portugal"]),
    ("Porto Canal", "Generalistas", ["porto canal"]),
    ("CMTV", "Generalistas", ["cmtv", "cm tv"]),
    ("Canal 11", "Generalistas", ["canal 11", "canal11"]),
    ("V+ TVI", "Generalistas", ["v+ tvi", "v+"]),
    ("ARTV", "Generalistas", ["artv", "canal parlamento"]),
    ("RTP Memória", "Generalistas", ["rtp memória", "rtp memoria"]),
    ("RTP Açores", "Generalistas", ["rtp açores", "rtp acores"]),
    ("RTP Madeira", "Generalistas", ["rtp madeira"]),
    ("RTP África", "Generalistas", ["rtp áfrica", "rtp africa"]),
    
    # 2. Desporto
    ("Sport TV+", "Desporto", ["sport tv+", "sport tv plus"]),
    ("Sport TV 1", "Desporto", ["sport tv 1", "sport tv1"]),
    ("Sport TV 2", "Desporto", ["sport tv 2", "sport tv2"]),
    ("Sport TV 3", "Desporto", ["sport tv 3", "sport tv3"]),
    ("Sport TV 4", "Desporto", ["sport tv 4", "sport tv4"]),
    ("Sport TV 5", "Desporto", ["sport tv 5", "sport tv5"]),
    ("Sport TV 6", "Desporto", ["sport tv 6", "sport tv6"]),
    ("Sport TV 7", "Desporto", ["sport tv 7", "sport tv7"]),
    ("DAZN 1 Portugal (Eleven 1)", "Desporto", ["dazn 1", "eleven sports 1", "eleven 1"]),
    ("DAZN 2 Portugal (Eleven 2)", "Desporto", ["dazn 2", "eleven sports 2", "eleven 2"]),
    ("DAZN 3 Portugal (Eleven 3)", "Desporto", ["dazn 3", "eleven sports 3", "eleven 3"]),
    ("DAZN 4 Portugal (Eleven 4)", "Desporto", ["dazn 4", "eleven sports 4", "eleven 4"]),
    ("DAZN 5 Portugal (Eleven 5)", "Desporto", ["dazn 5", "eleven sports 5", "eleven 5"]),
    ("DAZN 6 Portugal", "Desporto", ["dazn 6", "eleven sports 6", "eleven 6"]),
    ("Benfica TV", "Desporto", ["benfica tv", "btv"]),
    ("Sporting TV", "Desporto", ["sporting tv"]),
    ("A Bola TV", "Desporto", ["a bola tv", "bola tv"]),
    ("Eurosport 1 PT", "Desporto", ["eurosport 1", "eurosport1"]),
    ("Eurosport 2 PT", "Desporto", ["eurosport 2", "eurosport2"]),
    ("Fight Network", "Desporto", ["fight network"]),
    ("Motorvision", "Desporto", ["motorvision"]),
    
    # 3. Filmes & Séries
    ("TVCine Top", "Filmes & Séries", ["tvcine top"]),
    ("TVCine Edition", "Filmes & Séries", ["tvcine edition"]),
    ("TVCine Emotion", "Filmes & Séries", ["tvcine emotion"]),
    ("TVCine Action", "Filmes & Séries", ["tvcine action"]),
    ("Canal Hollywood", "Filmes & Séries", ["canal hollywood", "hollywood"]),
    ("Cinemundo", "Filmes & Séries", ["cinemundo"]),
    ("NOS Studios", "Filmes & Séries", ["nos studios"]),
    ("Star Channel", "Filmes & Séries", ["star channel", "fox portugal"]),
    ("Star Movies", "Filmes & Séries", ["star movies", "fox movies"]),
    ("Star Comedy", "Filmes & Séries", ["star comedy", "fox comedy"]),
    ("Star Crime", "Filmes & Séries", ["star crime", "fox crime"]),
    ("Star Life", "Filmes & Séries", ["star life", "fox life"]),
    ("AXN", "Filmes & Séries", ["axn (pt)", "axn portugal", "axn hd"]),
    ("AXN Movies", "Filmes & Séries", ["axn movies"]),
    ("AXN White", "Filmes & Séries", ["axn white"]),
    ("AMC", "Filmes & Séries", ["amc (pt)", "amc portugal"]),
    ("AMC Break", "Filmes & Séries", ["amc break"]),
    ("AMC Crime", "Filmes & Séries", ["amc crime"]),
    ("Syfy", "Filmes & Séries", ["syfy"]),
    
    # 4. Entretenimento & Documentários
    ("SIC Mulher", "Entretenimento", ["sic mulher"]),
    ("SIC Radical", "Entretenimento", ["sic radical"]),
    ("SIC Caras", "Entretenimento", ["sic caras"]),
    ("SIC Novelas", "Entretenimento", ["sic novelas"]),
    ("TVI Reality", "Entretenimento", ["tvi reality"]),
    ("24 Kitchen", "Entretenimento", ["24 kitchen", "24kitchen"]),
    ("Casa e Cozinha", "Entretenimento", ["casa e cozinha", "casa & cozinha"]),
    ("TLC", "Entretenimento", ["tlc"]),
    ("E! Entertainment", "Entretenimento", ["e! entertainment", "e!"]),
    ("Canal Q", "Entretenimento", ["canal q"]),
    ("Discovery Channel", "Entretenimento", ["discovery"]),
    ("National Geographic", "Entretenimento", ["national geographic", "nat geo"]),
    ("Nat Geo Wild", "Entretenimento", ["nat geo wild"]),
    ("Odisseia", "Entretenimento", ["odisseia"]),
    ("História", "Entretenimento", ["história", "historia"]),
    
    # 5. Infantis
    ("Canal Panda", "Infantis", ["canal panda", "panda"]),
    ("Panda Kids", "Infantis", ["panda kids"]),
    ("Cartoon Network", "Infantis", ["cartoon network"]),
    ("Disney Channel", "Infantis", ["disney channel"]),
    ("Disney Junior", "Infantis", ["disney junior", "disney jr"]),
    ("Nickelodeon", "Infantis", ["nickelodeon"]),
    ("Nick Jr.", "Infantis", ["nick jr", "nickjr"]),
    ("Baby TV", "Infantis", ["baby tv"]),
    
    # 6. Música
    ("MTV Portugal", "Música", ["mtv portugal", "mtv pt"]),
    ("Trace Urban", "Música", ["trace urban"]),
    ("MCM Pop", "Música", ["mcm pop"]),
    ("MCM Top", "Música", ["mcm top"]),
    ("Mezzo", "Música", ["mezzo"]),
    ("Stingray iConcerts", "Música", ["iconcerts", "stingray"]),
    ("Afro Music Channel", "Música", ["afro music"]),
    
    # 7. Religiosos & Outros
    ("Canção Nova", "Geral", ["canção nova", "cancao nova"]),
    ("Kuriakos TV", "Geral", ["kuriakos tv", "kuriakos"]),
    ("Record TV", "Geral", ["record"]),
    ("Globo", "Geral", ["globo"])
]

pt_channels = [c for c in channels if c.get('isPt')]
non_pt_channels = [c for c in channels if not c.get('isPt')]

matched_pt = []
used_pt_ids = set()

# Match PT channels by NOS order
for clean_name, cat, aliases in nos_order:
    # Find matching channel in pt_channels
    candidates = []
    for c in pt_channels:
        if c['id'] in used_pt_ids:
            continue
        c_lower = c['name'].lower()
        for alias in aliases:
            if alias in c_lower:
                candidates.append(c)
                break
    
    if candidates:
        # Merge if there are multiple (e.g. DaddyLive Eleven Sports + NTV DAZN)
        best = candidates[0]
        # Check if we have both DaddyLive and NTV
        daddy = next((c for c in candidates if c['id'].isdigit()), None)
        ntv = next((c for c in candidates if c['id'].startswith('ntv-')), None)
        timst = next((c for c in candidates if c['id'].startswith('timst-')), None)
        
        merged_id = daddy['id'] if daddy else (ntv['id'] if ntv else best['id'])
        backup = ntv.get('backupStreamUrl') if ntv else (timst.get('backupStreamUrl') if timst else best.get('backupStreamUrl'))
        
        entry = {
            "id": merged_id,
            "name": clean_name,
            "country": "PT",
            "category": cat,
            "isPt": True,
            "backupStreamUrl": backup if backup else best.get('backupStreamUrl'),
            "logoUrl": best.get('logoUrl')
        }
        matched_pt.append(entry)
        for c in candidates:
            used_pt_ids.add(c['id'])

# Add any remaining PT channels that were not in the primary list
for c in pt_channels:
    if c['id'] not in used_pt_ids:
        # Clean name
        clean = c['name'].replace('(PT)', '').replace('(pt)', '').strip()
        c['name'] = clean
        c['country'] = "PT"
        c['isPt'] = True
        matched_pt.append(c)

print(f'Total organized PT channels: {len(matched_pt)}')
all_channels = matched_pt + non_pt_channels

with open('app/src/main/assets/channels.json', 'w', encoding='utf-8') as f:
    json.dump(all_channels, f, ensure_ascii=False, indent=2)

print('Updated app/src/main/assets/channels.json successfully!')
