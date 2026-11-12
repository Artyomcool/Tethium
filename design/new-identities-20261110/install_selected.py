from pathlib import Path
from PIL import Image, ImageDraw
import json, shutil

root=Path(__file__).resolve().parents[2]
dest=root/'src/main/resources/dev/tethium/desktop/identity/v3'

out=Path(__file__).parent/'selected'
out.mkdir(exist_ok=True)

def cuts(profile,side):
    n=len(profile); step=n/side; result=[0]
    for i in range(1,side):
        expected=i*step
        lo=max(result[-1]+int(step*.4),int(expected-step*.4))
        hi=min(n-1,int(expected+step*.4))
        minimum=min(profile[lo:hi+1]); runs=[]; start=None
        for x in range(lo,hi+2):
            if x<=hi and profile[x]==minimum:
                if start is None:start=x
            elif start is not None:
                runs.append((start,x));start=None
        left,right=max(runs,key=lambda r:(r[1]-r[0],-abs((r[0]+r[1])/2-expected)))
        result.append((left+right)//2)
    return result+[n]

records=[]; thumbs=[]
for folder,side,selections in [('new-identities-20261110',4,[(0,0,'project-ahchecklist'),(0,2,'project-batchimagegenerator'),(1,1,'project-jbytevm'),(1,2,'project-jxenon'),(2,0,'project-nessplitter'),(2,3,'session-development'),(3,0,'session-shapes')])]:
    im=Image.open(root/'design'/folder/'atlas.png').convert('RGBA')
    a=im.getchannel('A');px=a.load();w,h=im.size
    ys=cuts([sum(px[x,y]>8 for x in range(w)) for y in range(h)],side)
    for row,col,name in selections:
        top,bottom=ys[row:row+2]
        xs=cuts([sum(px[x,y]>8 for y in range(top,bottom)) for x in range(w)],side)
        left,right=xs[col:col+2]
        borders=[sum(px[left,y]>24 for y in range(top,bottom)),sum(px[right-1,y]>24 for y in range(top,bottom)),sum(px[x,top]>24 for x in range(left,right)),sum(px[x,bottom-1]>24 for x in range(left,right))]
        assert max(borders)==0,(name,borders)
        crop=im.crop((left,top,right,bottom));box=crop.getchannel('A').point(lambda v:255 if v>8 else 0).getbbox();assert box
        box=(max(0,box[0]-2),max(0,box[1]-2),min(crop.width,box[2]+2),min(crop.height,box[3]+2))
        crop=crop.crop(box);crop.save(out/(name+'.png'));crop.save(dest/(name+'.png'))
        for size in [22,28,33,44,66,88]:
            scaled=crop.copy();scaled.thumbnail((size-2,size-2),Image.Resampling.LANCZOS)
            canvas=Image.new('RGBA',(size,size));canvas.paste(scaled,((size-scaled.width)//2,(size-scaled.height)//2));canvas.save(dest/(name+'-'+str(size)+'.png'))
        records.append(dict(id=name,source=folder,row=row+1,column=col+1,region=[left,top,right,bottom],bounds=box,borders=borders))
        thumbs.append((name,crop))
for bg,label in [('#25272b','dark'),('#eeece8','light')]:
    contact=Image.new('RGB',(800,440),bg);d=ImageDraw.Draw(contact)
    for i,(name,crop) in enumerate(thumbs):
        x=(i%4)*200;y=(i//4)*220
        crop=crop.copy();crop.thumbnail((150,160),Image.Resampling.LANCZOS)
        contact.paste(crop,(x+(200-crop.width)//2,y+10+(160-crop.height)//2),crop)
        d.text((x+10,y+185),name,fill='#999999')
    contact.save(out/('contact-'+label+'.png'))
(out/'manifest.json').write_text(json.dumps(records,indent=2))
print(json.dumps(records,indent=2))
