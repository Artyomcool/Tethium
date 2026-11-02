from pathlib import Path
from PIL import Image,ImageDraw
import json,base64
p=Path(__file__).parent
items=json.loads((p/'items.json').read_text())
for theme,bg,fg in [('dark','#202630','#e6edf7'),('light','#edf1f6','#233044')]:
    sheet=Image.new('RGB',(1536,1536),bg);d=ImageDraw.Draw(sheet)
    for i,item in enumerate(items):
        im=Image.open(p/'png'/(item['id']+'.png')).convert('RGBA');im.thumbnail((70,70),Image.Resampling.LANCZOS)
        x=i%16*96;y=i//16*96;sheet.paste(im,(x+(96-im.width)//2,y+5),im)
        d.text((x+28,y+78),f'{i//16+1:02}-{i%16+1:02}',fill=fg)
    sheet.save(p/f'contact-{theme}.png')
cards=[]
for i,item in enumerate(items):
    src='data:image/png;base64,'+base64.b64encode((p/'png'/(item['id']+'.png')).read_bytes()).decode()
    cards.append(f'<button title="{item["description"]}" onclick="show(this)"><img src="{src}"><small>{i//16+1:02}–{i%16+1:02}</small><span>{item["id"]}</span></button>')
html='''<!doctype html><meta charset="utf-8"><title>Tethium — цветные UI-иконки</title><style>body{font:14px system-ui;margin:24px;background:#202630;color:#e6edf7}h1{font-size:24px}.grid{display:grid;grid-template-columns:repeat(16,minmax(70px,1fr));gap:5px;min-width:1200px}button{background:#303947;color:inherit;border:1px solid #59677b;border-radius:7px;padding:7px;cursor:pointer}button img{width:52px;height:52px;object-fit:contain}small,span{display:block}span{font-size:9px;overflow-wrap:anywhere}small{margin:4px}body.light{background:#edf1f6;color:#233044}.light button{background:white}dialog{background:#303947;color:white;border:1px solid #8090aa;border-radius:12px;width:420px}dialog img{object-fit:contain;margin:16px}dialog::backdrop{background:#0009}nav{margin:16px 0}</style><h1>Tethium · цветные UI-иконки · 16 × 16</h1><p>256 символов в едином стиле. Первые две строки — основные места текущего интерфейса; далее тематические группы и запас.</p><nav><button onclick="document.body.classList.toggle('light')">Светлый / тёмный фон</button></nav><div class="grid">CARDS</div><dialog id="d"><button onclick="d.close()">Закрыть</button><h2 id="name"></h2><div id="sizes"></div><p>Размеры: 16 · 24 · 32 · 64 px. PNG с прозрачностью и исходным цветом.</p></dialog><script>function show(b){document.getElementById('name').textContent=b.querySelector('span').textContent;document.getElementById('sizes').innerHTML=[16,24,32,64].map(s=>'<img src="'+b.querySelector('img').src+'" width="'+s+'" height="'+s+'">').join('');document.getElementById('d').showModal()}</script>'''.replace('CARDS',''.join(cards))
(p/'index.html').write_text(html,encoding='utf-8')
print('256 PNGs; two contact sheets; self-contained HTML')
