from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json, shutil

p=Path(__file__).parent
source=Path(r'C:/Users/Raider/.codex/generated_images/01a19c28-d383-73c0-9a27-6e9c847c2d72/exec-8a34c519-add4-409e-b49f-ac27a75987c3.png')
shutil.copyfile(source,p/'atlas-extra-16.png')
out=p/'icons';out.mkdir(exist_ok=True)

def cuts(profile,count):
    n=len(profile); step=n/count; result=[0]
    for i in range(1,count):
        expected=i*step;lo=max(result[-1]+int(step*.4),int(expected-step*.40));hi=min(n-1,int(expected+step*.40))
        minimum=min(profile[lo:hi+1]);runs=[];start=None
        for x in range(lo,hi+2):
            if x<=hi and profile[x]==minimum:
                if start is None:start=x
            elif start is not None:runs.append((start,x));start=None
        left,right=max(runs,key=lambda r:(r[1]-r[0],-abs((r[0]+r[1])/2-expected)))
        result.append((left+right)//2)
    return result+[n]

def slice_sheet(name,rows,cols):
    im=Image.open(p/name).convert('RGBA');a=im.getchannel('A');assert a.getextrema()[0]==0
    w,h=im.size;px=a.load();ys=cuts([sum(px[x,y]>8 for x in range(w)) for y in range(h)],rows);result=[]
    if name=='atlas-240.png':
        guesses=[88,173,256,341,421,504,587,669,744,818,888,960,1035,1108,1180]
        ys=[0]+[min(range(v-10,v+11),key=lambda y:sum(px[x,y]>8 for x in range(w))) for v in guesses]+[h]
    for r in range(rows):
        top,bottom=ys[r:r+2];xs=cuts([sum(px[x,y]>8 for y in range(top,bottom)) for x in range(w)],cols)
        for c in range(cols):
            left,right=xs[c:c+2]
            cell_top,cell_bottom=top,bottom
            crop=im.crop((left,cell_top,right,cell_bottom))
            # A few atlas rows drift: discard only tiny detached border slivers from the neighbouring row.
            alpha=crop.getchannel('A');profile=[sum(v>8 for v in alpha.crop((0,y,crop.width,y+1)).getdata()) for y in range(crop.height)]
            bands=[];start=None
            for y,value in enumerate(profile+[0]):
                if value and start is None:start=y
                if not value and start is not None:bands.append((start,y,sum(profile[start:y])));start=None
            total=sum(profile);lo=0;hi=crop.height
            if len(bands)>1:
                a0,b0,m0=bands[0]
                if b0-a0<=4 and m0<total*.04 and bands[1][0]-b0>=3:lo=b0+1
                a1,b1,m1=bands[-1]
                if b1-a1<=4 and m1<total*.04 and a1-bands[-2][1]>=3:hi=a1-1
            crop=crop.crop((0,lo,crop.width,hi));tile_top=cell_top+lo;tile_bottom=cell_top+hi
            box=crop.getchannel('A').getbbox();assert box
            trimmed=crop.crop(box);pad=Image.new('RGBA',(trimmed.width+8,trimmed.height+8));pad.paste(trimmed,(4,4))
            result.append((pad,dict(source=name,region=[left,tile_top,right,tile_bottom],bounds=[left+box[0],tile_top+box[1],left+box[2],tile_top+box[3]],pixels=pad.size)))
    return result

main=slice_sheet('atlas-240.png',16,15);extra=slice_sheet('atlas-extra-16.png',4,4)
styles=['Чёрно-белые','Геометрия','Синий / слоновая кость','Серебро','Стекло','Эмаль','Оригами','Пиксели','Персонажи','Контур','Хром','Тушь','Неон','Гравюра','Пастель','Голография']
subjects=['Медуза','Лиса','Осьминог','Сова','Скат','Буква T','Планета','Проекты','Бесконечность','Созвездие','Фонарь','Компас','Кристалл','Портал','Воздушный змей']
extras=['Дерево','Феникс','Развилка','Лента Мёбиуса','Росток','Ключи','Двери','Маяк','Аксолотль','Горы','Нити','Шлем','Журавль','Река','Книги','Наутилус']
records=[];images=[]
for r in range(16):
    for c in range(16):
        image,record=main[r*15+c] if c<15 else extra[r]
        n=r*16+c+1;record.update(id=f'icon-{n:03}',number=n,row=r+1,column=c+1,title=(styles[r]+' · '+subjects[c]) if c<15 else extras[r])
        image.save(out/f'icon-{n:03}.png');records.append(record);images.append(image)
(p/'manifest.json').write_text(json.dumps(dict(count=256,method='Per-row alpha gap slicing; original RGBA retained. 240 initial concepts plus 16 supplemental concepts.',items=records),ensure_ascii=False,indent=2),encoding='utf-8')
font=ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',12)
for theme,bg,fg in [('light','#eeeef2','#252630'),('dark','#23252b','#eeeeef')]:
    board=Image.new('RGB',(16*100,16*108),bg);draw=ImageDraw.Draw(board)
    for i,im in enumerate(images):
        image=im.copy();image.thumbnail((76,76),Image.Resampling.LANCZOS);x=(i%16)*100;y=(i//16)*108
        board.paste(image,(x+(100-image.width)//2,y+4+(76-image.height)//2),image);draw.text((x+38,y+84),f'{i+1:03}',font=font,fill=fg)
    board.save(p/f'contact-{theme}.png')
cells=''.join(f'<button class="tile" data-n="{r["number"]}" title="{r["title"]}"><img src="icons/{r["id"]}.png" alt="{r["title"]}"><span>{r["number"]:03}</span></button>' for r in records)
html='''<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Tethium · 256 иконок</title>
<style>*{box-sizing:border-box}body{margin:0;background:#23252b;color:#f1f1f5;font:14px system-ui}header{padding:20px 24px;display:flex;gap:20px;align-items:center;position:sticky;top:0;background:inherit;z-index:2;border-bottom:1px solid #7775}h1{font-size:19px;margin:0 0 5px}p{margin:0;color:#a9acb6}button{font:inherit;cursor:pointer;color:inherit}header button,.modal button{background:#7772;border:1px solid #8885;border-radius:9px;padding:9px 14px}main{padding:16px;overflow:auto}.grid{display:grid;grid-template-columns:repeat(16,minmax(64px,1fr));gap:6px;min-width:1100px}.tile{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;aspect-ratio:1/1.12;background:#ffffff03;border:1px solid #8882;border-radius:10px;min-width:0;padding:8px}.tile:hover{background:#aaa2;border-color:#adbcff}.tile img{width:100%;height:58px;object-fit:contain}.tile span{font-size:11px;opacity:.65}.tile.chosen{border:2px solid #9cacff}.light{background:#eeeef2;color:#23252b}.light p{color:#656774}.modal{position:fixed;inset:0;background:#101116cc;display:none;align-items:center;justify-content:center;z-index:4}.modal.open{display:flex}.card{background:#2a2d34;padding:28px;border:1px solid #7775;border-radius:18px;text-align:center;width:min(480px,90vw);color:#eee}.light .card{background:#eeeef2;color:#23252b}.card img{width:240px;height:240px;object-fit:contain}.sizes{display:flex;gap:20px;align-items:center;justify-content:center;margin:20px}.sizes img{width:32px;height:32px}.sizes img+img{width:48px;height:48px}#caption{margin:12px 0 20px}#counter{margin-left:auto;white-space:nowrap}</style>
<header><div><h1>Tethium · 256 вариантов</h1><p>16 × 16 · клик — увеличить · назови номер понравившегося варианта</p></div><span id="counter">Выбран: —</span><button id="theme">Светлый фон</button></header><main><div class="grid">CELLS</div></main>
<div class="modal" id="modal"><div class="card"><img id="large"><div class="sizes"><img id="small"><img id="medium"></div><div id="caption"></div><button id="choose">Выбрать</button> <button id="close">Закрыть · Esc</button></div></div>
<script>const tiles=[...document.querySelectorAll('.tile')],modal=document.getElementById('modal');let current=0;function open(i){current=(i+256)%256;let t=tiles[current];for(let id of ['large','small','medium'])document.getElementById(id).src=t.querySelector('img').src;document.getElementById('caption').textContent=t.dataset.n.padStart(3,'0')+' · '+t.title;modal.classList.add('open')}tiles.forEach((t,i)=>t.onclick=()=>open(i));document.getElementById('close').onclick=()=>modal.classList.remove('open');modal.onclick=e=>{if(e.target===modal)modal.classList.remove('open')};document.getElementById('choose').onclick=()=>{tiles.forEach(t=>t.classList.remove('chosen'));tiles[current].classList.add('chosen');document.getElementById('counter').textContent='Выбран: '+String(current+1).padStart(3,'0');modal.classList.remove('open')};document.getElementById('theme').onclick=e=>{document.body.classList.toggle('light');e.target.textContent=document.body.classList.contains('light')?'Тёмный фон':'Светлый фон'};document.addEventListener('keydown',e=>{if(e.key==='Escape')modal.classList.remove('open');if(modal.classList.contains('open')&&['ArrowLeft','ArrowRight'].includes(e.key))open(current+(e.key==='ArrowRight'?1:-1))});</script></html>'''.replace('CELLS',cells)
(p/'index.html').write_text(html,encoding='utf-8')
print(json.dumps({'count':len(records),'html':str(p/'index.html')}))
