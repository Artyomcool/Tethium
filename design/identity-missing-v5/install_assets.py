from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).parent / "sliced"
DESTINATION = ROOT / "src/main/resources/dev/tethium/desktop/identity/v3"
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
SIZES = [22, 28, 33, 44, 66, 88]


for asset_id in IDS:
    source = Image.open(SOURCE / f"{asset_id}.png").convert("RGBA")
    source.save(DESTINATION / f"{asset_id}.png")
    for size in SIZES:
        scaled = source.copy()
        scaled.thumbnail((size - 2, size - 2), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (size, size))
        canvas.paste(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2), scaled)
        canvas.save(DESTINATION / f"{asset_id}-{size}.png")


for background, label in [("#25272b", "dark"), ("#eeece8", "light")]:
    contact = Image.new("RGB", (360, 270), background)
    draw = ImageDraw.Draw(contact)
    for index, asset_id in enumerate(IDS):
        image = Image.open(DESTINATION / f"{asset_id}-22.png").convert("RGBA")
        column, row = index % 3, index // 3
        x, y = column * 120, row * 90
        contact.paste(image, (x + 49, y + 17), image)
        draw.text((x + 5, y + 47), asset_id.replace("session-", ""), fill="#999999")
    contact.save(Path(__file__).parent / f"contact-22-{label}.png")
