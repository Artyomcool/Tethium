"""Slice by alpha valleys; independent horizontal positions for every row.
Keep original RGBA pixels including disconnected details and antialiasing.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import json

p=Path(__file__).parent
out=p/'alpha-sliced-v3'
out.mkdir(exist_ok=True)
im=Image.open(p/'atlas.png').convert('RGBA')
w,h=im.size
a=im.getchannel('A')
px=a.load()
items=json.loads((p/'items.json').read_text())

def cuts(profile):
    n=len(profile); step=n/4; result=[0]
    for i in range(1,4):
        expected=i*step
        lo=max(result[-1]+int(step*.4),int(expected-step*.46))
        hi=min(n-1,int(expected+step*.46))
        minimum=min(profile[lo:hi+1])
        # The entire transparent run matters, not proximity to a grid line.
        runs=[];start=None
        for x in range(lo,hi+2):
            if x<=hi and profile[x]==minimum:
                if start is None:start=x
            elif start is not None:
                runs.append((start,x));start=None
        left,right=max(runs,key=lambda r:(r[1]-r[0],-abs((r[0]+r[1])/2-expected)))
        result.append((left+right)//2)
    return result+[n]

ys=cuts([sum(px[x,y]>8 for x in range(w)) for y in range(h)])
records=[];warnings=[]
overlay=im.copy();draw=ImageDraw.Draw(overlay)
for r in range(4):
    top,bottom=ys[r:r+2]
    xs=cuts([sum(px[x,y]>8 for y in range(top,bottom)) for x in range(w)])
    for c in range(4):
        left,right=xs[c:c+2];item=items[r*4+c]
        region=(left,top,right,bottom)
        crop=im.crop(region)
        box=crop.getchannel('A').getbbox()
        assert box, item['id']
        # Preserve every alpha value, with four transparent padding pixels.
        trimmed=crop.crop(box)
        target=Image.new('RGBA',(trimmed.width+8,trimmed.height+8))
        target.paste(trimmed,(4,4))
        target.save(out/(item['id']+'.png'))
        borders=[sum(px[left,y]>24 for y in range(top,bottom)),sum(px[right-1,y]>24 for y in range(top,bottom)),sum(px[x,top]>24 for x in range(left,right)),sum(px[x,bottom-1]>24 for x in range(left,right))]
        if max(borders)>0:warnings.append([item['id'],borders])
        records.append(dict(id=item['id'],region=region,source_bounds=[left+box[0],top+box[1],left+box[2],top+box[3]],pixel_size=target.size))
        draw.rectangle((left,top,right-1,bottom-1),outline='#ff38af',width=1)
overlay.save(out/'boundaries.png')
(out/'manifest.json').write_text(json.dumps(dict(method='alpha-gap rows and independent per-row alpha-gap columns; all original RGBA retained',rows=ys,items=records,border_warnings=warnings),indent=2))
assert len(records)==16
print(json.dumps(dict(count=len(records),rows=ys,border_warnings=warnings)))
