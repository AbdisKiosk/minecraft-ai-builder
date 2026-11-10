package me.abdiskiosk.minecraftaibuilder.common;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Renders a region to PNG: a top-down map (north up, east right) and an isometric view.
 * Isometric view looks from the +X +Z corner (south-east, from above), so north-west is at the back.
 *
 * <p>An image costs Claude about one token per 750 pixels, so the picture is kept as small as it can be:
 * empty space around the blocks is cropped, and {@link Size} caps the canvas.
 */
public final class IsoRenderer {

    public enum View { TOP, ISO, BOTH }

    /** Largest canvas per size class (a region with more blocks across than pixels still gets one pixel per block). */
    public enum Size {
        SMALL(640, 480), MEDIUM(960, 700), LARGE(1400, 1000);

        final int maxWidth;
        final int maxHeight;

        Size(int maxWidth, int maxHeight) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }
    }

    /** @param box the part of the requested region that was drawn (cropped to visible blocks plus a one-block margin) */
    public record Rendered(byte[] png, Box box, int width, int height) {
    }

    private static final int GAP = 12;
    private static final Color BACKGROUND = new Color(0x1E2228);

    private IsoRenderer() {
    }

    public static byte[] renderPng(Box box, String[] states, View view) {
        return render(box, states, view, Size.MEDIUM).png();
    }

    public static Rendered render(Box box, String[] states, View view, Size size) {
        Box drawn = crop(box, states);
        if (!drawn.equals(box)) {
            states = RegionCodec.crop(box, states, drawn);
            box = drawn;
        }
        // Two views share the width, a single view gets all of it.
        int viewWidth = view == View.BOTH ? (size.maxWidth - GAP) / 2 : size.maxWidth;
        BufferedImage top = view == View.ISO ? null : renderTop(box, states, viewWidth, size.maxHeight);
        BufferedImage iso = view == View.TOP ? null : renderIso(box, states, viewWidth, size.maxHeight);
        BufferedImage out = top == null ? iso : iso == null ? top : combine(top, iso, size);
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

    static BufferedImage renderTop(Box box, String[] states, int maxWidth, int maxHeight) {
        int cell = Math.max(1, Math.min(16, Math.min(maxWidth / box.sizeX(), maxHeight / box.sizeZ())));
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
        if (cell >= 6) {
            g.setColor(new Color(0, 0, 0, 40));
            for (int i = 0; i <= box.sizeX(); i++) g.drawLine(i * cell, 0, i * cell, img.getHeight());
            for (int i = 0; i <= box.sizeZ(); i++) g.drawLine(0, i * cell, img.getWidth(), i * cell);
        }
        g.dispose();
        return img;
    }

    static BufferedImage renderIso(Box box, String[] states, int maxWidth, int maxHeight) {
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        // Tile: half-width a, half-height a/2, cube height a.
        double widthUnits = sx + sz;
        double heightUnits = (sx + sz) / 2.0 + sy + 1;
        int a = (int) Math.max(1, Math.min(14, Math.min((maxWidth - 2) / widthUnits, (maxHeight - 2) / heightUnits)));
        int w = (int) Math.ceil(widthUnits * a) + 2;
        int h = (int) Math.ceil(heightUnits * a) + 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);
        int originX = sz * a;
        int originY = sy * a;

        // Painter's order for a camera at +X +Y +Z: blocks with a larger x+y+z sum are in front.
        for (int d = 0; d <= sx + sy + sz - 3; d++) {
            for (int ly = Math.max(0, d - sx - sz + 2); ly <= Math.min(sy - 1, d); ly++) {
                for (int lx = Math.max(0, d - ly - sz + 1); lx <= Math.min(sx - 1, d - ly); lx++) {
                    int lz = d - ly - lx;
                    int x = box.min().x() + lx, y = box.min().y() + ly, z = box.min().z() + lz;
                    int rgb = BlockColors.rgb(states[RegionCodec.index(box, x, y, z)]);
                    if (rgb < 0 || hidden(box, states, x, y, z)) continue;
                    int px = originX + (lx - lz) * a;
                    int py = originY + (lx + lz) * a / 2 - ly * a;
                    drawCube(g, px, py, a, rgb);
                }
            }
        }
        g.dispose();
        return trim(img);
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

    /** A block is hidden when the three faces the camera sees (+X, +Y, +Z) are all covered by opaque blocks. */
    private static boolean hidden(Box box, String[] states, int x, int y, int z) {
        return opaque(box, states, x + 1, y, z) && opaque(box, states, x, y + 1, z) && opaque(box, states, x, y, z + 1);
    }

    private static boolean opaque(Box box, String[] states, int x, int y, int z) {
        if (x > box.max().x() || y > box.max().y() || z > box.max().z()) return false;
        return !BlockColors.isTransparent(states[RegionCodec.index(box, x, y, z)]);
    }

    private static void drawCube(Graphics2D g, int px, int py, int a, int rgb) {
        int half = a / 2;
        Polygon topFace = new Polygon(new int[]{px, px + a, px, px - a}, new int[]{py - half, py, py + half, py}, 4);
        Polygon left = new Polygon(new int[]{px - a, px, px, px - a}, new int[]{py, py + half, py + half + a, py + a}, 4);
        Polygon right = new Polygon(new int[]{px, px + a, px + a, px}, new int[]{py + half, py, py + a, py + half + a}, 4);
        g.setColor(shade(rgb, 1.0));
        g.fillPolygon(topFace);
        g.setColor(shade(rgb, 0.78));
        g.fillPolygon(left);
        g.setColor(shade(rgb, 0.6));
        g.fillPolygon(right);
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
