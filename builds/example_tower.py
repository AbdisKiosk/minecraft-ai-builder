"""Round watchtower 8 blocks east of the player. Run: python3 builds/example_tower.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sdk" / "python"))
from aibuilder import Builder, box, circle  # noqa: E402

b = Builder.connect()
ctx = b.context()
px, py, pz = ctx["caller"]["pos"]
light = "glowstone" if ctx["server"]["legacy_blocks"] else "lantern"  # 1.8 has no lanterns
cx, cy, cz = px + 8, py, pz
radius, height = 5, 18
wall = "70%stone_bricks,15%cracked_stone_bricks,15%mossy_stone_bricks"

b.cylinder([cx, cy - 1, cz], radius + 1, 1, "cobblestone")                     # plinth
b.cylinder([cx, cy, cz], radius, height, wall, hollow=True)                     # shell
for floor in range(0, height, 6):
    b.cylinder([cx, cy + floor, cz], radius - 1, 1, "spruce_planks")            # floors
b.cylinder([cx, cy + height, cz], radius + 1, 1, "stone_bricks")                # parapet base
for i, (x, z) in enumerate(circle(cx, cz, radius + 1, steps=32)):
    if i % 2 == 0:
        b.block(x, cy + height + 1, z, "stone_bricks")                          # merlons
for floor in range(3, height, 6):
    for dx, dz in [(radius, 0), (-radius, 0), (0, radius), (0, -radius)]:
        b.fill(box([cx + dx, cy + floor, cz + dz], [cx + dx, cy + floor + 1, cz + dz]), "glass_pane")
b.fill(box([cx + radius, cy, cz], [cx + radius, cy + 1, cz]), "air")             # door
b.block(cx + radius - 1, cy + 2, cz, light)

print(b.flush())
area = box([cx - radius - 2, cy - 1, cz - radius - 2], [cx + radius + 2, cy + height + 2, cz + radius + 2])
b.render(area, save_to=Path(__file__).with_suffix(".png"))
print("rendered", Path(__file__).with_suffix(".png"))
