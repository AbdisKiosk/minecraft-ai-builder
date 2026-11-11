package me.abdiskiosk.minecraftaibuilder.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Approximate map colours by block name keyword. Good enough for Claude to judge shape and palette. */
public final class BlockColors {

    // Checked in insertion order, so specific keywords must come before generic ones.
    private static final Map<String, Integer> KEYWORDS = new LinkedHashMap<>();
    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    static {
        put("water", 0x3F76E4); put("lava", 0xD96415); put("ice", 0x9CC3F7); put("snow", 0xF5F8F8);
        put("grass_block", 0x7CA34C); put("grass", 0x6E9A3E); put("fern", 0x5E8C34); put("leaves", 0x4C7A2C);
        put("moss", 0x5A7A2A); put("vine", 0x48772A); put("cactus", 0x5C8A2C);
        put("dark_oak", 0x4A3220); put("spruce", 0x6B4F2E); put("birch", 0xC8B77A); put("jungle", 0xA0734D);
        put("acacia", 0xA85A32); put("mangrove", 0x763331); put("cherry", 0xE3B4AE); put("bamboo", 0xC2AE4E);
        put("crimson", 0x7E3A56); put("warped", 0x2B6863); put("oak", 0xA2834F);
        put("log", 0x6B5434); put("wood", 0x6B5434); put("planks", 0xA2834F);
        put("deepslate", 0x505055); put("blackstone", 0x2E2A2F); put("basalt", 0x4A4A4E);
        put("andesite", 0x888889); put("diorite", 0xBDBDBD); put("granite", 0x956755); put("tuff", 0x6C6D66);
        put("calcite", 0xDFE0DC); put("mossy", 0x6E7A5A); put("cracked", 0x767676);
        put("red_sandstone", 0xB5621F); put("sandstone", 0xD8CB9B); put("red_sand", 0xBE6621); put("sand", 0xDBCFA3);
        put("gravel", 0x837E7C); put("clay", 0xA0A6B3); put("mud_brick", 0x8C6A4F); put("mud", 0x3C393D);
        put("dirt", 0x86603B); put("podzol", 0x5C3F18); put("farmland", 0x6E4A2A); put("path", 0x947A41);
        put("nether_brick", 0x2C1519); put("netherrack", 0x6F3534); put("end_stone", 0xDCDE9E);
        put("purpur", 0xA97DA9); put("prismarine", 0x5DA59A); put("quartz", 0xEAE4DB); put("terracotta", 0x985E43);
        put("cobblestone", 0x7F7F7F); put("stone_brick", 0x7A7A7A); put("smooth_stone", 0x9E9E9E);
        put("bricks", 0x96503F); put("brick", 0x96503F); put("copper", 0xC0705A); put("gold", 0xF6D03D);
        put("iron", 0xDCDCDC); put("diamond", 0x62E5DA); put("emerald", 0x41D96F); put("obsidian", 0x14121D);
        put("glass_pane", 0xC0E0E8); put("glass", 0xC0E0E8); put("glowstone", 0xF8D47A); put("lantern", 0xE9A43C);
        put("torch", 0xF6C44A); put("hay", 0xC4A123); put("bookshelf", 0x775D3B); put("wool", 0xE9ECEC);
        put("stone", 0x7D7D7D); put("ore", 0x7D7D7D); put("bedrock", 0x555555);
        // Dye colours last so "red_sandstone" etc. match above first.
        put("white", 0xE9ECEC); put("light_gray", 0x8E8E86); put("gray", 0x3E4447); put("black", 0x1D1D21);
        put("brown", 0x724728); put("red", 0xA12722); put("orange", 0xF07613); put("yellow", 0xF8C527);
        put("lime", 0x70B919); put("green", 0x546D1B); put("cyan", 0x158991); put("light_blue", 0x3AAFD9);
        put("blue", 0x35399D); put("purple", 0x792AAC); put("magenta", 0xBD44B3); put("pink", 0xED8DAC);
    }

    // light_gray and light_blue before gray and blue, which they end with.
    private static final String[] DYES = {"light_gray", "light_blue", "white", "gray", "black", "brown", "red", "orange",
            "yellow", "lime", "green", "cyan", "blue", "purple", "magenta", "pink"};
    private static final Set<String> DYED = Set.of("wool", "carpet", "concrete", "concrete_powder", "terracotta",
            "glazed_terracotta", "stained_glass", "stained_glass_pane", "stained_clay", "bed", "banner", "wall_banner",
            "candle", "shulker_box");

    private static void put(String keyword, int rgb) {
        KEYWORDS.put(keyword, rgb);
    }

    private BlockColors() {
    }

    /** RGB colour, or -1 for air (transparent). */
    public static int rgb(String state) {
        String type = RegionCodec.stripProperties(state);
        if (RegionCodec.isAir(type)) return -1;
        return CACHE.computeIfAbsent(type, BlockColors::lookup);
    }

    private static int lookup(String type) {
        // A dyed block has its dye's colour, not its material's: red_wool is red although "wool" alone is white.
        // Only for dyeable materials, so red_sandstone, blue_ice and white_tulip keep their own colours.
        for (String dye : DYES) {
            if (type.startsWith(dye + "_") && DYED.contains(type.substring(dye.length() + 1))) return KEYWORDS.get(dye);
        }
        for (Map.Entry<String, Integer> e : KEYWORDS.entrySet()) {
            if (type.contains(e.getKey())) return e.getValue();
        }
        return 0xB0A080;
    }

    /** True for blocks the renderer treats as see-through for occlusion. */
    public static boolean isTransparent(String state) {
        return !BlockTraits.opaque(state);
    }
}
