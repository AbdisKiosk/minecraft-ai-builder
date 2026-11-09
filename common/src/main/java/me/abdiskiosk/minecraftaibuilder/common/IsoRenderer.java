package me.abdiskiosk.minecraftaibuilder.common;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Renders a region to PNG: top-down map on the left, isometric view on the right.
 * Isometric view looks from the +X +Z corner (south-east, from above), so north-west is at the back.
 */
public final class IsoRenderer {

    public enum View { TOP, ISO, BOTH }

    private static final int MAX_WIDTH = 1400;
    private static final int MAX_HEIGHT = 1000;
    private static final Color BACKGROUND = new Color(0x1E2228);
    private static final Color LABEL = new Color(0xB8C0CC);

    private IsoRenderer() {
    }

    public static byte[] renderPng(Box box, String[] states, View view) {
        BufferedImage top = view == View.ISO ? null : renderTop(box, states);
        BufferedImage iso = view == View.TOP ? null : renderIso(box, states);
        int gap = 16;
        int w = Math.max(420, (top == null ? 0 : top.getWidth()) + (iso == null ? 0 : iso.getWidth()) + (top != null && iso != null ? gap : 0));
        int h = Math.max(top == null ? 0 : top.getHeight(), iso == null ? 0 : iso.getHeight()) + 18;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);
        g.setColor(LABEL);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        String label = (top != null ? "top: north up, east right" : "") + (top != null && iso != null ? " | " : "")
                + (iso != null ? "iso: from south-east" : "") + " | " + box;
        g.drawString(label, 2, 12);
        int x = 0;
        if (top != null) {
            g.drawImage(top, 0, 18, null);
            x = top.getWidth() + gap;
        }
        if (iso != null) g.drawImage(iso, x, 18, null);
        g.dispose();
        return toPng(out);
    }

    static BufferedImage renderTop(Box box, String[] states) {
        int cell = Math.max(1, Math.min(16, Math.min(MAX_WIDTH / 2 / box.sizeX(), MAX_HEIGHT / box.sizeZ())));
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

    static BufferedImage renderIso(Box box, String[] states) {
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        // Tile: half-width a, half-height a/2, cube height a.
        double widthUnits = sx + sz;
        double heightUnits = (sx + sz) / 2.0 + sy;
        int a = (int) Math.max(1, Math.min(14, Math.min(MAX_WIDTH / 2.0 / widthUnits, MAX_HEIGHT / heightUnits)));
        int w = (int) Math.ceil(widthUnits * a) + 2;
        int h = (int) Math.ceil(heightUnits * a) + a + 2;
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
        return img;
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
