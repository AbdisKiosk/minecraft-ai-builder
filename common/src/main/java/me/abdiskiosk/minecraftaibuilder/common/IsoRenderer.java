package me.abdiskiosk.minecraftaibuilder.common;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a region to PNG: a top-down map (north up, east right), an isometric view, or an elevation of one face.
 *
 * <p>The isometric camera stands at a {@link Corner}, above the region. Other corners than {@code SE} rotate the
 * region in quarter turns before drawing, never mirror it, so the build keeps its handedness. An elevation shows one
 * face of the region from outside, nearer blocks brighter. Slabs, stairs, fences and other common shapes are drawn
 * from {@link BlockShape} once a block is at least 4 px across; smaller pictures draw every block as a cube.
 *
 * <p>An image costs Claude about one token per 750 pixels, so the picture is kept as small as it can be:
 * empty space around the blocks is cropped, and {@link Size} caps the canvas.
 */
public final class IsoRenderer {

    /** {@code NORTH} etc. are elevations of that face seen from outside: for {@code NORTH} east is on the left. */
    public enum View { TOP, ISO, BOTH, NORTH, SOUTH, EAST, WEST }

    /** Where the isometric camera stands. */
    public enum Corner { SE, SW, NE, NW }

    /**
     * Largest canvas and largest block per size class (a region with more blocks across than pixels still gets one
     * pixel per block). A small build never fills the canvas, so the block cap is what its picture costs:
     * 10 px blocks are half the pixels of 14 px ones and still show every shape.
     */
    public enum Size {
        SMALL(640, 480, 10, 10), MEDIUM(960, 700, 16, 14), LARGE(1400, 1000, 16, 14);

        final int maxWidth;
        final int maxHeight;
        /** Largest block in pixels: the side of a cell in flat views, the tile half-width in the isometric one. */
        final int maxCell;
        final int maxTile;

        Size(int maxWidth, int maxHeight, int maxCell, int maxTile) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.maxCell = maxCell;
            this.maxTile = maxTile;
        }
    }

    /** @param box the part of the requested region that was drawn (cropped to visible blocks plus a one-block margin) */
    public record Rendered(byte[] png, Box box, int width, int height) {
    }

    private static final int GAP = 12;
    /** Smallest block size in pixels at which shapes are drawn instead of cubes. */
    private static final int SHAPES_FROM = 4;
    private static final Color BACKGROUND = new Color(0x1E2228);

    private IsoRenderer() {
    }

    public static byte[] renderPng(Box box, String[] states, View view) {
        return render(box, states, view, Size.MEDIUM).png();
    }

    public static Rendered render(Box box, String[] states, View view, Size size) {
        return render(box, states, view, Corner.SE, size);
    }

    /** @param corner where the isometric camera stands; ignored by {@code TOP} and the elevations */
    public static Rendered render(Box box, String[] states, View view, Corner corner, Size size) {
        Box drawn = crop(box, states);
        if (!drawn.equals(box)) {
            states = RegionCodec.crop(box, states, drawn);
            box = drawn;
        }
        BufferedImage out = switch (view) {
            case TOP -> renderTop(box, states, size.maxWidth, size.maxHeight, size.maxCell);
            case ISO -> renderIso(box, states, corner, size.maxWidth, size.maxHeight, size.maxTile);
            // Two views share the width.
            case BOTH -> combine(renderTop(box, states, (size.maxWidth - GAP) / 2, size.maxHeight, size.maxCell),
                    renderIso(box, states, corner, (size.maxWidth - GAP) / 2, size.maxHeight, size.maxTile), size);
            default -> renderElevation(box, states, view, size.maxWidth, size.maxHeight, size.maxCell);
        };
        return new Rendered(toPng(out), box, out.getWidth(), out.getHeight());
    }

    /** Side by side or stacked, whichever canvas is smaller. */
    private static BufferedImage combine(BufferedImage top, BufferedImage iso, Size size) {
        int sideW = top.getWidth() + GAP + iso.getWidth(), sideH = Math.max(top.getHeight(), iso.getHeight());
        int stackW = Math.max(top.getWidth(), iso.getWidth()), stackH = top.getHeight() + GAP + iso.getHeight();
        boolean stack = (long) stackW * stackH < (long) sideW * sideH && stackH <= size.maxWidth;
        BufferedImage out = new BufferedImage(stack ? stackW : sideW, stack ? stackH : sideH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(top, 0, 0, null);
        g.drawImage(iso, stack ? 0 : top.getWidth() + GAP, stack ? top.getHeight() + GAP : 0, null);
        g.dispose();
        return out;
    }

    /** The box around every block that would be drawn, grown by one block and kept inside {@code box}. */
    static Box crop(Box box, String[] states) {
        Vec3 lo = null, hi = null;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (BlockColors.rgb(states[RegionCodec.index(box, x, y, z)]) < 0) continue;
                    Vec3 p = new Vec3(x, y, z);
                    lo = lo == null ? p : Vec3.min(lo, p);
                    hi = hi == null ? p : Vec3.max(hi, p);
                }
            }
        }
        if (lo == null) return box;
        return Box.of(Vec3.max(box.min(), lo.add(-1, -1, -1)), Vec3.min(box.max(), hi.add(1, 1, 1)));
    }

    static BufferedImage renderTop(Box box, String[] states, int maxWidth, int maxHeight, int maxCell) {
        int cell = Math.max(1, Math.min(maxCell, Math.min(maxWidth / box.sizeX(), maxHeight / box.sizeZ())));
        BufferedImage img = new BufferedImage(box.sizeX() * cell, box.sizeZ() * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int z = box.min().z(); z <= box.max().z(); z++) {
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                for (int y = box.max().y(); y >= box.min().y(); y--) {
                    int rgb = BlockColors.rgb(states[RegionCodec.index(box, x, y, z)]);
                    if (rgb < 0) continue;
                    // Lower surfaces are darker so height differences read clearly.
                    double depth = box.sizeY() <= 1 ? 0 : (box.max().y() - y) / (double) (box.sizeY() - 1);
                    g.setColor(shade(rgb, 1.0 - 0.55 * depth));
                    g.fillRect((x - box.min().x()) * cell, (z - box.min().z()) * cell, cell, cell);
                    break;
                }
            }
        }
        grid(g, cell, box.sizeX(), box.sizeZ());
        g.dispose();
        return img;
    }

    private static void grid(Graphics2D g, int cell, int columns, int rows) {
        if (cell < 6) return;
        g.setColor(new Color(0, 0, 0, 40));
        for (int i = 0; i <= columns; i++) g.drawLine(i * cell, 0, i * cell, rows * cell);
        for (int i = 0; i <= rows; i++) g.drawLine(0, i * cell, columns * cell, i * cell);
    }

    /**
     * One face of the region seen from outside: each cell shows the nearest block along the view axis, darker with
     * depth. A block that does not fill its cell (a slab) shows what is behind it.
     */
    static BufferedImage renderElevation(Box box, String[] states, View view, int maxWidth, int maxHeight, int maxCell) {
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        boolean alongX = view == View.EAST || view == View.WEST;
        int columns = alongX ? sz : sx, depths = alongX ? sx : sz;
        int cell = Math.max(1, Math.min(maxCell, Math.min(maxWidth / columns, maxHeight / sy)));
        BufferedImage img = new BufferedImage(columns * cell, sy * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        boolean shapes = cell >= SHAPES_FROM;
        int[] stack = new int[depths];
        for (int ly = 0; ly < sy; ly++) {
            int py = (sy - 1 - ly) * cell;
            for (int col = 0; col < columns; col++) {
                int px = col * cell, found = 0;
                // Nearest first, until a block fills the cell; then paint them back to front.
                for (int d = 0; d < depths; d++) {
                    String state = states[elevationIndex(box, view, col, ly, d)];
                    if (BlockColors.rgb(state) < 0) continue;
                    stack[found++] = d;
                    if (!shapes || BlockShape.isFullCube(state)) break;
                }
                for (int i = found - 1; i >= 0; i--) {
                    int d = stack[i];
                    String state = states[elevationIndex(box, view, col, ly, d)];
                    double depth = depths <= 1 ? 0 : d / (double) (depths - 1);
                    g.setColor(shade(BlockColors.rgb(state), 1.0 - 0.55 * depth));
                    if (!shapes) {
                        g.fillRect(px, py, cell, cell);
                        continue;
                    }
                    for (double[] b : BlockShape.boxes(state)) {
                        // Extent along the image's left-to-right axis.
                        double h0 = switch (view) {
                            case NORTH -> 1 - b[3];
                            case EAST -> 1 - b[5];
                            case WEST -> b[2];
                            default -> b[0];
                        };
                        double h1 = switch (view) {
                            case NORTH -> 1 - b[0];
                            case EAST -> 1 - b[2];
                            case WEST -> b[5];
                            default -> b[3];
                        };
                        int x0 = px + (int) Math.round(h0 * cell), x1 = px + (int) Math.round(h1 * cell);
                        int y0 = py + (int) Math.round((1 - b[4]) * cell), y1 = py + (int) Math.round((1 - b[1]) * cell);
                        g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
                    }
                }
            }
        }
        grid(g, cell, columns, sy);
        g.dispose();
        return img;
    }

    /** Index of the block in image column {@code col} at height {@code ly}, {@code d} blocks behind the viewed face. */
    private static int elevationIndex(Box box, View view, int col, int ly, int d) {
        Vec3 min = box.min(), max = box.max();
        int y = min.y() + ly;
        return switch (view) {
            case NORTH -> RegionCodec.index(box, max.x() - col, y, min.z() + d);
            case SOUTH -> RegionCodec.index(box, min.x() + col, y, max.z() - d);
            case EAST -> RegionCodec.index(box, max.x() - d, y, max.z() - col);
            case WEST -> RegionCodec.index(box, min.x() + d, y, min.z() + col);
            default -> throw new IllegalArgumentException(view + " is not an elevation");
        };
    }

    static BufferedImage renderIso(Box box, String[] states, Corner corner, int maxWidth, int maxHeight, int maxTile) {
        int sy = box.sizeY();
        boolean turned = corner == Corner.SW || corner == Corner.NE;
        // View space: u, y, v with the camera at +u +y +v, a quarter-turn rotation of world x, y, z.
        int su = turned ? box.sizeZ() : box.sizeX(), sv = turned ? box.sizeX() : box.sizeZ();
        // Tile: half-width a, half-height a/2, cube height a.
        double widthUnits = su + sv;
        double heightUnits = (su + sv) / 2.0 + sy + 1;
        int a = (int) Math.max(1, Math.min(maxTile, Math.min((maxWidth - 2) / widthUnits, (maxHeight - 2) / heightUnits)));
        int w = (int) Math.ceil(widthUnits * a) + 2;
        int h = (int) Math.ceil(heightUnits * a) + 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);
        int originX = sv * a;
        int originY = sy * a;
        boolean shapes = a >= SHAPES_FROM;

        // Painter's order for a camera at +u +y +v: blocks with a larger u+y+v sum are in front.
        for (int d = 0; d <= su + sy + sv - 3; d++) {
            for (int ly = Math.max(0, d - su - sv + 2); ly <= Math.min(sy - 1, d); ly++) {
                for (int lu = Math.max(0, d - ly - sv + 1); lu <= Math.min(su - 1, d - ly); lu++) {
                    int lv = d - ly - lu;
                    String state = states[viewIndex(box, corner, lu, ly, lv)];
                    int rgb = BlockColors.rgb(state);
                    if (rgb < 0 || hidden(box, states, corner, su, sv, lu, ly, lv)) continue;
                    int px = originX + (lu - lv) * a;
                    int py = originY + (lu + lv) * a / 2 - ly * a;
                    if (!shapes || BlockShape.isFullCube(state)) {
                        drawBox(g, px, py, a, rgb, 0, 0, 0, 1, 1, 1);
                        continue;
                    }
                    List<double[]> parts = new ArrayList<>();
                    for (double[] b : BlockShape.boxes(state)) parts.add(rotate(b, corner));
                    parts.sort(IsoRenderer::paintOrder);
                    for (double[] b : parts) drawBox(g, px, py, a, rgb, b[0], b[1], b[2], b[3], b[4], b[5]);
                }
            }
        }
        g.dispose();
        return trim(img);
    }

    /** Index of the block at view-space cell (lu, ly, lv). */
    private static int viewIndex(Box box, Corner corner, int lu, int ly, int lv) {
        int sx = box.sizeX(), sz = box.sizeZ();
        int lx, lz;
        switch (corner) {
            case SW -> { lx = sx - 1 - lv; lz = lu; }
            case NW -> { lx = sx - 1 - lu; lz = sz - 1 - lv; }
            case NE -> { lx = lv; lz = sz - 1 - lu; }
            default -> { lx = lu; lz = lv; }
        }
        return RegionCodec.index(box, box.min().x() + lx, box.min().y() + ly, box.min().z() + lz);
    }

    /** A sub-box of the unit cube turned about the cell centre into view space, as {@link #viewIndex} turns cells. */
    private static double[] rotate(double[] b, Corner corner) {
        double u0, u1, v0, v1;
        switch (corner) {
            case SW -> { u0 = b[2]; u1 = b[5]; v0 = 1 - b[3]; v1 = 1 - b[0]; }
            case NW -> { u0 = 1 - b[3]; u1 = 1 - b[0]; v0 = 1 - b[5]; v1 = 1 - b[2]; }
            case NE -> { u0 = 1 - b[5]; u1 = 1 - b[2]; v0 = b[0]; v1 = b[3]; }
            default -> { u0 = b[0]; u1 = b[3]; v0 = b[2]; v1 = b[5]; }
        }
        return new double[]{u0, b[1], v0, u1, b[4], v1};
    }

    /** Back to front for boxes that do not overlap: lower first, then further from the camera. */
    private static int paintOrder(double[] p, double[] q) {
        if (p[4] <= q[1]) return -1;
        if (q[4] <= p[1]) return 1;
        return Double.compare(p[0] + p[3] + p[2] + p[5], q[0] + q[3] + q[2] + q[5]);
    }

    /** Cuts away the empty background around the drawing (the corners of the diamond, air above low builds). */
    private static BufferedImage trim(BufferedImage img) {
        int background = BACKGROUND.getRGB();
        int x0 = img.getWidth(), y0 = img.getHeight(), x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) == background) continue;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return img.getSubimage(0, 0, 1, 1); // nothing visible
        int margin = 2;
        x0 = Math.max(0, x0 - margin);
        y0 = Math.max(0, y0 - margin);
        x1 = Math.min(img.getWidth() - 1, x1 + margin);
        y1 = Math.min(img.getHeight() - 1, y1 + margin);
        return img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /** A block is hidden when the three faces the camera sees (+u, +y, +v) are all covered by opaque blocks. */
    private static boolean hidden(Box box, String[] states, Corner corner, int su, int sv, int lu, int ly, int lv) {
        return opaque(box, states, corner, su, sv, lu + 1, ly, lv) && opaque(box, states, corner, su, sv, lu, ly + 1, lv)
                && opaque(box, states, corner, su, sv, lu, ly, lv + 1);
    }

    private static boolean opaque(Box box, String[] states, Corner corner, int su, int sv, int lu, int ly, int lv) {
        if (lu >= su || ly >= box.sizeY() || lv >= sv) return false;
        String state = states[viewIndex(box, corner, lu, ly, lv)];
        return !BlockColors.isTransparent(state) && BlockShape.isFullCube(state);
    }

    /**
     * Draws the faces the camera sees (top, +v on the left, +u on the right) of the box u0..u1, y0..y1, v0..v1
     * inside the cell whose top face has its back corner at (px, py - a/2).
     */
    private static void drawBox(Graphics2D g, int px, int py, int a, int rgb,
                                double u0, double y0, double v0, double u1, double y1, double v1) {
        int[] xs = new int[4], ys = new int[4];
        project(px, py, a, new double[][]{{u0, y1, v0}, {u1, y1, v0}, {u1, y1, v1}, {u0, y1, v1}}, xs, ys);
        g.setColor(shade(rgb, 1.0));
        g.fillPolygon(xs, ys, 4);
        project(px, py, a, new double[][]{{u0, y1, v1}, {u1, y1, v1}, {u1, y0, v1}, {u0, y0, v1}}, xs, ys);
        g.setColor(shade(rgb, 0.78));
        g.fillPolygon(xs, ys, 4);
        project(px, py, a, new double[][]{{u1, y1, v1}, {u1, y1, v0}, {u1, y0, v0}, {u1, y0, v1}}, xs, ys);
        g.setColor(shade(rgb, 0.6));
        g.fillPolygon(xs, ys, 4);
    }

    private static void project(int px, int py, int a, double[][] points, int[] xs, int[] ys) {
        int half = a / 2;
        for (int i = 0; i < points.length; i++) {
            double u = points[i][0], y = points[i][1], v = points[i][2];
            xs[i] = px + (int) Math.round((u - v) * a);
            ys[i] = py - half + (int) Math.round((u + v) * half + (1 - y) * a);
        }
    }

    private static Color shade(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int gr = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return new Color(r, gr, b);
    }

    private static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
