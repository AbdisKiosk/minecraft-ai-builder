package me.abdiskiosk.minecraftaibuilder.common.map;

import me.abdiskiosk.minecraftaibuilder.common.BlockColors;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * A square of top-down map columns: the surface block's colour and height, and how deep the water above it is.
 * {@link #argb()} shades it like a relief map (lit from the north-west, water darker with depth); columns that were
 * never filled stay transparent.
 */
public final class MapRaster {

    /** What a block looks like from above: nothing, water, or a solid colour. Packed as kind << 24 | rgb. */
    public static final int AIR = 0;
    static final int WATER = 1 << 24;
    static final int SOLID = 2 << 24;
    /** The deepest water a column follows down before calling it deep. */
    static final int MAX_DEPTH = 48;
    private static final int WATER_RGB = 0x3F76E4;
    private static final Set<String> INVISIBLE = Set.of("air", "cave_air", "void_air", "barrier", "structure_void", "light");
    private static final Set<String> WATERY = Set.of("water", "flowing_water", "bubble_column", "kelp", "kelp_plant",
            "seagrass", "tall_seagrass");

    private final int size;
    /** Surface colour (the floor's under water); -1 where nothing was set, or where water is deeper than we follow. */
    private final int[] color;
    private final int[] height;
    private final byte[] depth;
    private final boolean[] set;

    public MapRaster(int size) {
        this.size = size;
        this.color = new int[size * size];
        this.height = new int[size * size];
        this.depth = new byte[size * size];
        this.set = new boolean[size * size];
        Arrays.fill(color, -1);
    }

    public int size() {
        return size;
    }

    /**
     * @param rgb   the surface block's colour, or -1 under water deeper than {@link #MAX_DEPTH}
     * @param y     the surface's height (the water's top when there is water)
     * @param water how many water blocks lie on top of the surface block
     */
    public void set(int x, int z, int rgb, int y, int water) {
        int i = z * size + x;
        color[i] = rgb;
        height[i] = y;
        depth[i] = (byte) Math.min(water, 127);
        set[i] = true;
    }

    /** Forgets a column, so it is transparent until set again. */
    public void clear(int x, int z) {
        int i = z * size + x;
        set[i] = false;
        color[i] = -1;
        depth[i] = 0;
    }

    public boolean isSet(int x, int z) {
        return set[z * size + x];
    }

    public int height(int x, int z) {
        return height[z * size + x];
    }

    /** How a block shows on the map: {@link #AIR}, or WATER / SOLID with its colour. */
    public static int packed(String name) {
        String n = name;
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        int bracket = n.indexOf('[');
        if (bracket >= 0) n = n.substring(0, bracket);
        n = n.toLowerCase(Locale.ROOT);
        if (INVISIBLE.contains(n)) return AIR;
        if (WATERY.contains(n)) return WATER | WATER_RGB;
        int rgb = BlockColors.rgb(n);
        return rgb < 0 ? AIR : SOLID | (rgb & 0xFFFFFF);
    }

    /** The shaded image, ARGB row by row (z down, x across); unset columns are 0, fully transparent. */
    public int[] argb() {
        int[] out = new int[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (!set[i]) continue;
                int d = depth[i];
                int rgb;
                if (d > 0) {
                    // Like vanilla maps: the floor shows through shallow water, deep water is dark blue.
                    double w = color[i] < 0 ? 1 : Math.min(0.92, 0.42 + d * 0.05);
                    rgb = mix(WATER_RGB, color[i] < 0 ? WATER_RGB : color[i], w);
                    rgb = scale(rgb, 1 - Math.min(d, 30) * 0.009);
                } else {
                    rgb = scale(color[i], 1 + Math.max(-3, Math.min(3, slope(x, z))) * 0.075);
                }
                out[i] = 0xFF000000 | rgb;
            }
        }
        return out;
    }

    /**
     * How much higher this column is than the one to its north-west: the step from the west plus the step from the
     * north. Where that neighbour is missing (the raster's edge, or no data) the step to the east or south stands in,
     * so tiles join without a seam.
     */
    private int slope(int x, int z) {
        int h = height[z * size + x];
        return step(h, x - 1, z, x + 1, z) + step(h, x, z - 1, x, z + 1);
    }

    private int step(int h, int bx, int bz, int ax, int az) {
        if (dry(bx, bz)) return h - height[bz * size + bx];
        if (dry(ax, az)) return height[az * size + ax] - h;
        return 0;
    }

    private boolean dry(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) return false;
        int i = z * size + x;
        return set[i] && depth[i] == 0;
    }

    private static int mix(int a, int b, double wa) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * wa + ((b >> 16) & 0xFF) * (1 - wa));
        int g = (int) Math.round(((a >> 8) & 0xFF) * wa + ((b >> 8) & 0xFF) * (1 - wa));
        int bl = (int) Math.round((a & 0xFF) * wa + (b & 0xFF) * (1 - wa));
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int rgb, double f) {
        int r = Math.min(255, (int) Math.round(((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) Math.round((rgb & 0xFF) * f));
        return r << 16 | g << 8 | b;
    }

    // ---- images ----

    public static byte[] png(int[] argb, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, size, size, argb, 0, size);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** The ARGB pixels of a {@code size}x{@code size} PNG, or null when it is not one. */
    public static int[] decode(byte[] png, int size) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || img.getWidth() != size || img.getHeight() != size) return null;
            return img.getRGB(0, 0, size, size, null, 0, size);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Halves four {@code size}x{@code size} images into one of the same size: {@code quads} are the north-west,
     * north-east, south-west and south-east quarters, null where there is nothing. Each output pixel averages the
     * opaque pixels of its 2x2 block.
     */
    public static int[] downsample(int[][] quads, int size) {
        int[] out = new int[size * size];
        int half = size / 2;
        for (int q = 0; q < 4; q++) {
            int[] src = quads[q];
            if (src == null) continue;
            int ox = (q & 1) * half, oz = (q >> 1) * half;
            for (int z = 0; z < half; z++) {
                for (int x = 0; x < half; x++) {
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int k = 0; k < 4; k++) {
                        int p = src[(z * 2 + (k >> 1)) * size + x * 2 + (k & 1)];
                        if ((p >>> 24) < 128) continue;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                    if (n > 0) out[(oz + z) * size + ox + x] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
                }
            }
        }
        return out;
    }
}
