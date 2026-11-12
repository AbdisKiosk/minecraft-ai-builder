package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Stair roofs over a wall footprint; a port of {@code Vox.roof_gable} and {@code Vox.roof_hip} of the Python SDK. */
public final class Roofs {

    private static final int MAX_OVERHANG = 8;

    private Roofs() {
    }

    /**
     * @param walls    footprint of the walls; only x and z extents matter, and walls.min().y() is the eaves course
     * @param stairs   a stair block name such as "spruce_stairs" (no properties)
     * @param alongX   true = ridge runs along x, false = along z
     * @param overhang blocks the roof sticks out past the walls on all sides, >= 0
     * @param ridge    pattern for the ridge row of an odd span; null = the slab of the stairs
     * @param gable    pattern that fills the two end walls under the roof; null = leave them open
     */
    public static List<Op> gable(Box walls, String stairs, boolean alongX, int overhang, String ridge, String gable) {
        String ridgePattern = check(stairs, overhang, ridge);
        int y = walls.min().y();
        int u0 = alongX ? walls.min().x() : walls.min().z(), u1 = alongX ? walls.max().x() : walls.max().z();
        int w0 = alongX ? walls.min().z() : walls.min().x(), w1 = alongX ? walls.max().z() : walls.max().x();
        String lo = alongX ? "south" : "east", hi = alongX ? "north" : "west";
        int a = w0 - overhang, b = w1 + overhang;

        List<Op.BlockAt> blocks = new ArrayList<>();
        List<Op> out = new ArrayList<>();
        for (int u = u0 - overhang; u <= u1 + overhang; u++) {
            for (int i = 0; i <= (b - a) / 2; i++) {
                if (a + i != b - i) {
                    blocks.add(stair(alongX, u, y + i, a + i, stairs, lo));
                    blocks.add(stair(alongX, u, y + i, b - i, stairs, hi));
                }
            }
        }
        if ((b - a) % 2 == 0) {
            int h = y + (b - a) / 2, mid = a + (b - a) / 2;
            out.add(new Op.Fill(cells(alongX, u0 - overhang, u1 + overhang, h, mid, mid), ridgePattern));
        }
        if (gable != null) {
            for (int u : new TreeSet<>(List.of(u0, u1))) {
                for (int k = 0; y + k < y + Math.max(0, (b - a + 1) / 2); k++) {
                    int from = Math.max(w0, a + k + 1), to = Math.min(w1, b - k - 1);
                    if (from <= to) out.add(new Op.Fill(cells(alongX, u, u, y + k, from, to), gable));
                }
            }
        }
        return result(blocks, out);
    }

    /** Four slopes rising inward one block per ring, closed by a ridge. Same parameters as gable. */
    public static List<Op> hip(Box walls, String stairs, int overhang, String ridge) {
        String ridgePattern = check(stairs, overhang, ridge);
        int a = walls.min().x() - overhang, b = walls.max().x() + overhang;
        int c = walls.min().z() - overhang, d = walls.max().z() + overhang;
        int y = walls.min().y();
        List<Op.BlockAt> blocks = new ArrayList<>();
        List<Op> out = new ArrayList<>();
        while (a <= b && c <= d) {
            if (a == b || c == d) {
                out.add(new Op.Fill(Box.of(new Vec3(a, y, c), new Vec3(b, y, d)), ridgePattern));
                break;
            }
            for (int x = a; x <= b; x++) {
                blocks.add(new Op.BlockAt(new Vec3(x, y, c), stairs + "[facing=south]"));
                blocks.add(new Op.BlockAt(new Vec3(x, y, d), stairs + "[facing=north]"));
            }
            for (int z = c + 1; z < d; z++) {
                blocks.add(new Op.BlockAt(new Vec3(a, y, z), stairs + "[facing=east]"));
                blocks.add(new Op.BlockAt(new Vec3(b, y, z), stairs + "[facing=west]"));
            }
            a++;
            b--;
            c++;
            d--;
            y++;
        }
        return result(blocks, out);
    }

    private static String check(String stairs, int overhang, String ridge) {
        if (stairs == null || !stairs.endsWith("stairs")) {
            throw new EditException("stairs must be a stair block name ending in \"stairs\", got " + stairs);
        }
        if (overhang < 0 || overhang > MAX_OVERHANG) {
            throw new EditException("overhang must be 0.." + MAX_OVERHANG + ", got " + overhang);
        }
        return ridge != null ? ridge : stairs.substring(0, stairs.length() - "stairs".length()) + "slab";
    }

    private static Op.BlockAt stair(boolean alongX, int u, int y, int w, String stairs, String facing) {
        return new Op.BlockAt(alongX ? new Vec3(u, y, w) : new Vec3(w, y, u), stairs + "[facing=" + facing + "]");
    }

    /** Box over u0..u1 along the ridge axis and w0..w1 across it. */
    private static Box cells(boolean alongX, int u0, int u1, int y, int w0, int w1) {
        return alongX ? Box.of(new Vec3(u0, y, w0), new Vec3(u1, y, w1)) : Box.of(new Vec3(w0, y, u0), new Vec3(w1, y, u1));
    }

    private static List<Op> result(List<Op.BlockAt> blocks, List<Op> fills) {
        List<Op> out = new ArrayList<>();
        if (!blocks.isEmpty()) out.add(new Op.Blocks(blocks));
        out.addAll(fills);
        return out;
    }
}
