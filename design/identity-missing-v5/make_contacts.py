from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).parent
IDS = [
    "session-lane-1",
    "session-lane-2",
    "session-lane-3",
    "session-lane-4",
    "session-interpreter",
    "session-reminders",
    "session-night-watch",
    "session-campaign",
    "group-favorites",
]


for background, label in [("#25272b", "dark"), ("#eeece8", "light")]:
    contact = Image.new("RGB", (800, 660), background)
    draw = ImageDraw.Draw(contact)
    for index, asset_id in enumerate(IDS):
        image = Image.open(ROOT / "sliced" / f"{asset_id}.png").convert("RGBA")
        image.thumbnail((150, 160), Image.Resampling.LANCZOS)
        column, row = index % 4, index // 4
        x, y = column * 200, row * 220
        contact.paste(image, (x + (200 - image.width) // 2, y + 10 + (160 - image.height) // 2), image)
        draw.text((x + 10, y + 185), asset_id, fill="#999999")
    contact.save(ROOT / f"contact-{label}.png")
