"""Round watchtower 8 blocks east of the player. Run: python3 builds/example_tower.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sdk" / "python"))  # not needed when PYTHONPATH has the SDK
from aibuilder import Builder, circle  # noqa: E402

b = Builder.connect()
ctx = b.context()
px, py, pz = ctx["caller"]["pos"]
light = "glowstone" if ctx["server"]["legacy_blocks"] else "lantern"  # 1.8 has no lanterns
cx, cy, cz = px + 8, py, pz
radius, height = 5, 18
wall = "70%stone_bricks,15%cracked_stone_bricks,15%mossy_stone_bricks"

b.at([cx, cy, cz])                                                 # coordinates below count from the tower's centre
b.cylinder([0, -1, 0], radius + 1, 1, "cobblestone")               # plinth
b.cylinder([0, 0, 0], radius, height, wall, hollow=True)           # shell
for floor in range(0, height, 6):
    b.cylinder([0, floor, 0], radius - 1, 1, "spruce_planks")      # floors
b.cylinder([0, height, 0], radius + 1, 1, "stone_bricks")          # parapet base
for i, (x, z) in enumerate(circle(0, 0, radius + 1, steps=32)):
    if i % 2 == 0:
        b.block(x, height + 1, z, "stone_bricks")                  # merlons
for floor in range(3, height, 6):
    for dx, dz in [(radius, 0), (-radius, 0), (0, radius), (0, -radius)]:
        b.fill([dx, floor, dz, dx, floor + 1, dz], "glass_pane")
b.fill([radius, 0, 0, radius, 1, 0], "air")                        # door
b.block(radius - 1, 2, 0, light)

print(b.flush())
area = [cx - radius - 2, cy - 1, cz - radius - 2, cx + radius + 2, cy + height + 2, cz + radius + 2]
b.render(area, save_to=Path(__file__).with_suffix(".png"), size="small")
print("rendered", Path(__file__).with_suffix(".png"))
