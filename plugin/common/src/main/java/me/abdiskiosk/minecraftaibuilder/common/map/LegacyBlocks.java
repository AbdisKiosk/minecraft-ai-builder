package me.abdiskiosk.minecraftaibuilder.common.map;

/**
 * Modern names for the numeric block ids of 1.8-1.12, close enough to colour a map. Chunks of a 1.13+ world that no
 * one visited since an upgrade are still saved this way, so modern servers need it too.
 */
public final class LegacyBlocks {

    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] STONES = {"stone", "granite", "polished_granite", "diorite", "polished_diorite",
            "andesite", "polished_andesite"};
    private static final String[] SLABS = {"smooth_stone", "sandstone", "oak_planks", "cobblestone", "bricks",
            "stone_bricks", "nether_bricks", "quartz_block"};
    private static final String[] NAMES = new String[256];

    static {
        String all = "0 air,1 stone,2 grass_block,3 dirt,4 cobblestone,5 planks,6 sapling,7 bedrock,8 water,9 water,"
                + "10 lava,11 lava,12 sand,13 gravel,14 gold_ore,15 iron_ore,16 coal_ore,17 log,18 leaves,19 sponge,"
                + "20 glass,21 lapis_ore,22 lapis_block,23 dispenser,24 sandstone,25 note_block,26 red_bed,27 powered_rail,"
                + "28 detector_rail,29 sticky_piston,30 cobweb,31 short_grass,32 dead_bush,33 piston,34 piston_head,35 wool,"
                + "36 moving_piston,37 dandelion,38 poppy,39 brown_mushroom,40 red_mushroom,41 gold_block,42 iron_block,"
                + "43 double_slab,44 slab,45 bricks,46 tnt,47 bookshelf,48 mossy_cobblestone,49 obsidian,50 torch,51 fire,"
                + "52 spawner,53 oak_stairs,54 chest,55 redstone_wire,56 diamond_ore,57 diamond_block,58 crafting_table,"
                + "59 wheat,60 farmland,61 furnace,62 furnace,63 oak_sign,64 oak_door,65 ladder,66 rail,67 cobblestone_stairs,"
                + "68 oak_wall_sign,69 lever,70 stone_pressure_plate,71 iron_door,72 oak_pressure_plate,73 redstone_ore,"
                + "74 redstone_ore,75 redstone_torch,76 redstone_torch,77 stone_button,78 snow,79 ice,80 snow_block,81 cactus,"
                + "82 clay,83 sugar_cane,84 jukebox,85 oak_fence,86 pumpkin,87 netherrack,88 soul_sand,89 glowstone,"
                + "90 nether_portal,91 jack_o_lantern,92 cake,93 repeater,94 repeater,95 stained_glass,96 oak_trapdoor,"
                + "97 stone,98 stone_bricks,99 brown_mushroom_block,100 red_mushroom_block,101 iron_bars,102 glass_pane,"
                + "103 melon,104 pumpkin_stem,105 melon_stem,106 vine,107 oak_fence_gate,108 brick_stairs,"
                + "109 stone_brick_stairs,110 mycelium,111 lily_pad,112 nether_bricks,113 nether_brick_fence,"
                + "114 nether_brick_stairs,115 nether_wart,116 enchanting_table,117 brewing_stand,118 cauldron,"
                + "119 end_portal,120 end_portal_frame,121 end_stone,122 dragon_egg,123 redstone_lamp,124 redstone_lamp,"
                + "125 double_wood_slab,126 wood_slab,127 cocoa,128 sandstone_stairs,129 emerald_ore,130 ender_chest,"
                + "131 tripwire_hook,132 tripwire,133 emerald_block,134 spruce_stairs,135 birch_stairs,136 jungle_stairs,"
                + "137 command_block,138 beacon,139 cobblestone_wall,140 flower_pot,141 carrots,142 potatoes,143 oak_button,"
                + "144 skeleton_skull,145 anvil,146 trapped_chest,147 light_weighted_pressure_plate,"
                + "148 heavy_weighted_pressure_plate,149 comparator,150 comparator,151 daylight_detector,152 redstone_block,"
                + "153 nether_quartz_ore,154 hopper,155 quartz_block,156 quartz_stairs,157 activator_rail,158 dropper,"
                + "159 terracotta_dyed,160 stained_glass_pane,161 leaves2,162 log2,163 acacia_stairs,164 dark_oak_stairs,"
                + "165 slime_block,166 barrier,167 iron_trapdoor,168 prismarine,169 sea_lantern,170 hay_block,171 carpet,"
                + "172 terracotta,173 coal_block,174 packed_ice,175 tall_grass,176 white_banner,177 white_wall_banner,"
                + "178 daylight_detector,179 red_sandstone,180 red_sandstone_stairs,181 red_sandstone,182 red_sandstone_slab,"
                + "183 spruce_fence_gate,184 birch_fence_gate,185 jungle_fence_gate,186 dark_oak_fence_gate,"
                + "187 acacia_fence_gate,188 spruce_fence,189 birch_fence,190 jungle_fence,191 dark_oak_fence,"
                + "192 acacia_fence,193 spruce_door,194 birch_door,195 jungle_door,196 acacia_door,197 dark_oak_door,"
                + "198 end_rod,199 chorus_plant,200 chorus_flower,201 purpur_block,202 purpur_pillar,203 purpur_stairs,"
                + "204 purpur_block,205 purpur_slab,206 end_stone_bricks,207 beetroots,208 dirt_path,209 end_gateway,"
                + "210 repeating_command_block,211 chain_command_block,212 frosted_ice,213 magma_block,"
                + "214 nether_wart_block,215 red_nether_bricks,216 bone_block,217 structure_void,218 observer,"
                + "251 concrete,252 concrete_powder,255 structure_block";
        for (String pair : all.split(",")) {
            int sp = pair.indexOf(' ');
            NAMES[Integer.parseInt(pair.substring(0, sp))] = pair.substring(sp + 1);
        }
        for (int i = 0; i < 16; i++) {
            NAMES[219 + i] = DYES[i] + "_shulker_box";
            NAMES[235 + i] = DYES[i] + "_glazed_terracotta";
        }
    }

    private LegacyBlocks() {
    }

    /** The modern name of legacy block {@code id} with data value {@code data}; "stone" for ids it does not know. */
    public static String name(int id, int data) {
        String n = id >= 0 && id < NAMES.length ? NAMES[id] : null;
        if (n == null) return id == 0 ? "air" : "stone";
        return switch (n) {
            case "stone" -> id == 1 ? STONES[Math.min(data, STONES.length - 1)] : n;
            case "dirt" -> data == 2 ? "podzol" : n;
            case "sand" -> data == 1 ? "red_sand" : n;
            case "planks", "double_wood_slab", "wood_slab" -> WOODS[Math.min(data & 7, 5)] + "_planks";
            case "sapling" -> WOODS[Math.min(data & 7, 5)] + "_sapling";
            case "log" -> WOODS[data & 3] + "_log";
            case "leaves" -> WOODS[data & 3] + "_leaves";
            case "log2" -> WOODS[4 + (data & 1)] + "_log";
            case "leaves2" -> WOODS[4 + (data & 1)] + "_leaves";
            case "double_slab", "slab" -> SLABS[data & 7];
            case "short_grass" -> data == 0 ? "dead_bush" : data == 2 ? "fern" : n;
            case "wool", "carpet", "stained_glass", "stained_glass_pane", "concrete", "concrete_powder" ->
                    DYES[data & 15] + "_" + n;
            case "terracotta_dyed" -> DYES[data & 15] + "_terracotta";
            case "tall_grass" -> data == 0 ? "sunflower" : data == 3 ? "large_fern" : data == 1 ? "lilac" : n;
            default -> n;
        };
    }
}
