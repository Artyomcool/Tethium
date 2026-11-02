from pathlib import Path
from PIL import Image
root=Path(__file__).resolve().parents[2]
im=Image.open(Path(__file__).parent/'atlas.png').convert('RGBA')
# Locate the transparent gutters around the selected top-left subject.
a=im.getchannel('A');w,h=im.size
x=min(range(int(w*.43),int(w*.57)),key=lambda x:sum(a.getpixel((x,y)) for y in range(h//2)))
y=min(range(int(h*.46),int(h*.55)),key=lambda y:sum(a.getpixel((x,y)) for x in range(x)))
tile=im.crop((0,0,x,y))
box=tile.getchannel('A').point(lambda v:255 if v>16 else 0).getbbox()
box=(max(0,box[0]-2),max(0,box[1]-2),min(tile.width,box[2]+2),min(tile.height,box[3]+2))
tile=tile.crop(box)
tile.thumbnail((500,500),Image.Resampling.LANCZOS)
master=Image.new('RGBA',(512,512));master.paste(tile,((512-tile.width)//2,(512-tile.height)//2))
out=root/'src/main/resources/dev/tethium/desktop/branding';out.mkdir(parents=True,exist_ok=True)
master.save(out/'tethium.png')
master.save(out/'tethium.ico',sizes=[(s,s) for s in (16,24,32,48,64,128,256)])
for s in (16,24,32,48,64,128,256):master.resize((s,s),Image.Resampling.LANCZOS).save(out/f'tethium-{s}.png')
print('Prepared 01A PNG and multi-resolution ICO')
