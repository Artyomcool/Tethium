"""Package verified batch-imagegen crops and build light/dark QA sheets."""
import json
import shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
root = Path(__file__).resolve().parents[1]
source = root / 'design/ui-v2/icons-v1/slices'
destination = root / 'src/main/resources/dev/tethium/desktop/icons/v1'
destination.mkdir(parents=True, exist_ok=True)
manifest = json.loads((source / 'manifest.json').read_text())
font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 16)
sheet = Image.new('RGB', (960, 688)); draw = ImageDraw.Draw(sheet)
for index, item in enumerate(manifest['items']):
    icon = Image.open(source / (item['id'] + '.png')).convert('RGBA')
    shutil.copy2(source / (item['id'] + '.png'), destination / (item['id'] + '.png'))
    for dark in (False, True):
        x = (index % 4) * 120 + (480 if dark else 0); y = (index // 4) * 172
        draw.rectangle((x, y, x + 119, y + 171), fill='#222326' if dark else '#f5f4f0')
        tint = Image.new('RGBA', icon.size, '#eeeef2' if dark else '#222326'); tint.putalpha(icon.getchannel('A'))
        large = tint.copy(); large.thumbnail((64, 64), Image.Resampling.LANCZOS)
        sheet.paste(large, (x + (120-large.width)//2, y+16), large)
        small = tint.copy(); small.thumbnail((18, 18), Image.Resampling.LANCZOS)
        sheet.paste(small, (x + (120-small.width)//2, y+100), small)
        draw.text((x+60,y+134), item['id'], font=font, anchor='mt', fill='#eeeef2' if dark else '#222326')
sheet.save(root / 'design/ui-v2/icons-v1/contact.png')
print(f"Packaged {len(manifest['items'])} icons; source {manifest['actual_size']}")
