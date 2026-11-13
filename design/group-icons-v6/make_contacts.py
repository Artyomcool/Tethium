from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).parent
IDS = [
    "group-neural",
    "group-games",
    "group-heroes",
    "group-java",
    "group-general",
]


for background, label in [("#25272b", "dark"), ("#eeece8", "light")]:
    contact = Image.new("RGB", (800, 440), background)
    draw = ImageDraw.Draw(contact)
    for index, asset_id in enumerate(IDS):
        image = Image.open(ROOT / "sprites" / f"{asset_id}.png").convert("RGBA")
        image.thumbnail((150, 160), Image.Resampling.LANCZOS)
        column, row = index % 4, index // 4
        x, y = column * 200, row * 220
        contact.paste(image, (x + (200 - image.width) // 2, y + 10 + (160 - image.height) // 2), image)
        draw.text((x + 10, y + 185), asset_id, fill="#999999")
    contact.save(ROOT / f"contact-{label}.png")

    tiny = Image.new("RGB", (600, 90), background)
    tiny_draw = ImageDraw.Draw(tiny)
    for index, asset_id in enumerate(IDS):
        source = Image.open(ROOT / "sprites" / f"{asset_id}.png").convert("RGBA")
        source.thumbnail((20, 20), Image.Resampling.LANCZOS)
        x = index * 120
        tiny.paste(source, (x + 50, 12), source)
        tiny_draw.text((x + 5, 48), asset_id.removeprefix("group-"), fill="#999999")
    tiny.save(ROOT / f"contact-22-{label}.png")
