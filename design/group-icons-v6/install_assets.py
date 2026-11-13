from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).parent / "sprites"
DESTINATION = ROOT / "src/main/resources/dev/tethium/desktop/identity/v3"
IDS = [
    "group-neural",
    "group-games",
    "group-heroes",
    "group-java",
    "group-general",
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
