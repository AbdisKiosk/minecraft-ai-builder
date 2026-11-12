package me.abdiskiosk.minecraftaibuilder.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approximate block shapes for the renderer: a few axis-aligned boxes per block, enough to tell a slab or a stair
 * from a full block. Boxes are {@code [x0,y0,z0,x1,y1,z1]} inside the unit cube (x east, y up, z south).
 */
public final class BlockShape {

    private static final double[] FULL = {0, 0, 0, 1, 1, 1};
    private static final double POST = 0.375, THIN = 0.1875, FLAT = 0.125;
    private static final Map<String, List<double[]>> CACHE = new ConcurrentHashMap<>();

    private BlockShape() {
    }

    /** The sub-boxes of a block state such as {@code oak_stairs[facing=east,half=top]}. The arrays are shared: do not modify them. */
    public static List<double[]> boxes(String state) {
        return CACHE.computeIfAbsent(state, BlockShape::compute);
    }

    public static boolean isFullCube(String state) {
        List<double[]> boxes = boxes(state);
        return boxes.size() == 1 && boxes.get(0) == FULL;
    }

    private static List<double[]> compute(String state) {
        String type = RegionCodec.stripProperties(state);
        Map<String, String> p;
        try {
            p = BlockState.parse(state).props();
        } catch (EditException e) {
            return List.of(FULL);
        }
        if (type.endsWith("_slab")) {
            return switch (p.getOrDefault("type", "bottom")) {
                case "top" -> List.of(new double[]{0, 0.5, 0, 1, 1, 1});
                case "double" -> List.of(FULL);
                default -> List.of(new double[]{0, 0, 0, 1, 0.5, 1});
            };
        }
        if (type.endsWith("_stairs")) {
            boolean top = p.getOrDefault("half", "bottom").equals("top");
            double[] slab = top ? new double[]{0, 0.5, 0, 1, 1, 1} : new double[]{0, 0, 0, 1, 0.5, 1};
            double[] step = side(p.getOrDefault("facing", "north"), 0.5);
            step[1] = top ? 0 : 0.5;
            step[4] = top ? 0.5 : 1;
            return List.of(slab, step);
        }
        if (type.contains("fence") || type.endsWith("_wall") || type.contains("pane") || type.endsWith("bars")
                || type.equals("chain") || type.endsWith("_chain") || type.endsWith("_rod")) {
            return List.of(new double[]{POST, 0, POST, 1 - POST, 1, 1 - POST});
        }
        if (type.equals("snow")) {
            double layers = Math.max(1, Math.min(8, parseInt(p.get("layers"), 1)));
            return List.of(new double[]{0, 0, 0, 1, layers / 8, 1});
        }
        if (type.endsWith("carpet") || type.endsWith("pressure_plate") || type.endsWith("rail")) {
            return List.of(new double[]{0, 0, 0, 1, FLAT, 1});
        }
        // Minecraft puts an open trapdoor and a closed door against the side opposite their facing (the side the player stood on).
        if (type.endsWith("trapdoor")) {
            if (p.getOrDefault("open", "false").equals("true")) return List.of(side(opposite(p.getOrDefault("facing", "north")), THIN));
            return List.of(p.getOrDefault("half", "bottom").equals("top")
                    ? new double[]{0, 1 - THIN, 0, 1, 1, 1} : new double[]{0, 0, 0, 1, THIN, 1});
        }
        if (type.endsWith("_door")) {
            return List.of(side(opposite(p.getOrDefault("facing", "north")), THIN));
        }
        if (type.endsWith("torch") || (type.endsWith("lantern") && !type.equals("sea_lantern") && !type.startsWith("jack_o"))) {
            return List.of(new double[]{0.375, 0, 0.375, 0.625, 0.625, 0.625});
        }
        return List.of(FULL);
    }

    /** A full-height box of the given depth against one side of the cube. */
    private static double[] side(String facing, double depth) {
        return switch (facing) {
            case "south" -> new double[]{0, 0, 1 - depth, 1, 1, 1};
            case "east" -> new double[]{1 - depth, 0, 0, 1, 1, 1};
            case "west" -> new double[]{0, 0, 0, depth, 1, 1};
            default -> new double[]{0, 0, 0, 1, 1, depth};
        };
    }

    private static String opposite(String facing) {
        return switch (facing) {
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            default -> "south";
        };
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
