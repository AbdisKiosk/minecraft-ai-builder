package me.abdiskiosk.minecraftaibuilder.common;

import java.util.Map;
import java.util.Set;

/**
 * Physical traits of blocks, by name, for {@link RegionLint}. Inputs are states as a read returns them
 * ({@code oak_stairs[facing=east]}, no namespace, default properties left out). Approximate on purpose:
 * unknown blocks count as full cubes, which is right for most modded and new building blocks.
 */
public final class BlockTraits {

    private static final Set<String> PLANTS = Set.of(
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "torchflower", "pitcher_plant", "grass", "short_grass",
            "tall_grass", "fern", "large_fern", "dead_bush", "sweet_berry_bush", "sugar_cane", "wheat", "carrots", "potatoes",
            "beetroots", "crimson_roots", "warped_roots", "nether_sprouts", "nether_wart", "brown_mushroom", "red_mushroom",
            "crimson_fungus", "warped_fungus", "pink_petals", "wildflowers", "leaf_litter", "bush", "firefly_bush",
            "short_dry_grass", "tall_dry_grass", "open_eyeblossom", "closed_eyeblossom", "cactus_flower", "azalea",
            "flowering_azalea", "small_dripleaf", "bamboo_sapling", "torchflower_crop", "pitcher_crop", "melon_stem",
            "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem");

    /** Not full cubes and not plants: thin, small or oddly shaped blocks. */
    private static final Set<String> SHAPED = Set.of(
            "lever", "ladder", "vine", "glow_lichen", "sculk_vein", "flower_pot", "snow", "water", "lava", "bubble_column",
            "fire", "soul_fire", "cobweb", "lily_pad", "seagrass", "tall_seagrass", "kelp", "sea_pickle", "scaffolding",
            "cake", "campfire", "soul_campfire", "anvil", "chipped_anvil", "damaged_anvil", "chest", "trapped_chest",
            "ender_chest", "enchanting_table", "brewing_stand", "hopper", "lectern", "grindstone", "bell", "stonecutter",
            "daylight_detector", "end_portal_frame", "conduit", "redstone_wire", "repeater", "comparator", "tripwire",
            "tripwire_hook", "turtle_egg", "frogspawn", "sniffer_egg", "pointed_dripstone", "dragon_egg", "nether_portal",
            "end_portal", "end_gateway", "piston_head", "moving_piston", "structure_void", "light", "decorated_pot",
            "spore_blossom", "hanging_roots", "big_dripleaf", "big_dripleaf_stem", "sculk_sensor",
            "calibrated_sculk_sensor", "sculk_shrieker", "composter", "bamboo", "cactus", "chorus_plant", "chorus_flower",
            "lantern", "soul_lantern");

    private static final String[] SHAPED_SUFFIXES = {
            "_stairs", "fence", "fence_gate", "_wall", "pane", "bars", "door", "torch", "carpet", "sign", "banner", "button",
            "pressure_plate", "rail", "_sapling", "_tulip", "_bed", "candle", "candle_cake", "_rod", "chain", "_head", "_skull",
            "_coral", "_coral_fan", "_bud", "_cluster", "_vines", "_plant", "cauldron", "copper_lantern"};

    /** Full cubes that let light through. */
    private static final Set<String> TRANSLUCENT = Set.of(
            "ice", "frosted_ice", "slime_block", "honey_block", "beacon", "barrier", "spawner", "trial_spawner", "vault",
            "mangrove_roots");

    private BlockTraits() {
    }

    /** A full block, by type alone. Slabs count as partial here; use {@link #fullCubeState} when the state is known. */
    public static boolean fullCube(String type) {
        if (RegionCodec.isAir(type)) return false;
        if (type.endsWith("_slab") || PLANTS.contains(type) || SHAPED.contains(type) || type.startsWith("potted_")) return false;
        for (String suffix : SHAPED_SUFFIXES) {
            if (type.endsWith(suffix)) return false;
        }
        return true;
    }

    /** Like {@link #fullCube(String)}, but a slab with {@code type=double} is full. */
    public static boolean fullCubeState(String state) {
        String type = RegionCodec.stripProperties(state);
        if (type != null && type.endsWith("_slab")) return "double".equals(props(state).get("type"));
        return fullCube(type);
    }

    /** Can carry or hold another block: full cubes, glass and leaves included. */
    public static boolean solid(String state) {
        return fullCubeState(state);
    }

    /** Stops light: full cubes except glass, leaves, ice and similar. */
    public static boolean opaque(String state) {
        if (!solid(state)) return false;
        String type = RegionCodec.stripProperties(state);
        if (type.contains("glass")) return type.equals("tinted_glass");
        return !type.contains("leaves") && !type.endsWith("_grate") && !TRANSLUCENT.contains(type);
    }

    /** Falls when there is nothing below. */
    public static boolean gravity(String type) {
        return type != null && (type.equals("sand") || type.equals("red_sand") || type.equals("gravel")
                || type.equals("suspicious_sand") || type.equals("suspicious_gravel") || type.endsWith("_concrete_powder")
                || type.endsWith("anvil"));
    }

    /** Breaks without a block directly below. */
    public static boolean needsFloor(String state) {
        String type = RegionCodec.stripProperties(state);
        if (RegionCodec.isAir(type)) return false;
        if (type.endsWith("torch")) return !type.endsWith("wall_torch");
        if (lantern(type)) return !"true".equals(props(state).get("hanging"));
        if (type.endsWith("sign") || type.endsWith("banner")) return !type.contains("wall_") && !type.contains("hanging");
        return PLANTS.contains(type) || type.endsWith("_sapling") || type.endsWith("_tulip") || type.endsWith("carpet")
                || type.endsWith("rail") || type.endsWith("_door") || type.endsWith("pressure_plate")
                || type.equals("flower_pot") || type.startsWith("potted_") || type.endsWith("candle")
                || type.equals("redstone_wire") || type.equals("repeater") || type.equals("comparator") || type.equals("snow");
    }

    /** Hangs on the block behind it: the side opposite {@code facing}. */
    public static boolean wallAttached(String state) {
        String type = RegionCodec.stripProperties(state);
        return type != null && (type.endsWith("wall_torch") || type.endsWith("wall_banner") || type.equals("ladder")
                || (type.endsWith("wall_sign") && !type.contains("hanging")));
    }

    /** Unit step of {@code facing} (north = -z, south = +z, east = +x, west = -x); north when it is left out. */
    public static Vec3 facing(String state) {
        return switch (props(state).getOrDefault("facing", "north")) {
            case "south" -> new Vec3(0, 0, 1);
            case "east" -> new Vec3(1, 0, 0);
            case "west" -> new Vec3(-1, 0, 0);
            case "up" -> new Vec3(0, 1, 0);
            case "down" -> new Vec3(0, -1, 0);
            default -> new Vec3(0, 0, -1);
        };
    }

    /** Emitted block light, 0..15. */
    public static int light(String state) {
        String type = RegionCodec.stripProperties(state);
        if (RegionCodec.isAir(type)) return 0;
        boolean soul = type.startsWith("soul_");
        switch (type) {
            case "glowstone", "sea_lantern", "shroomlight", "jack_o_lantern", "lava", "fire", "beacon", "conduit",
                 "lava_cauldron" -> {
                return 15;
            }
            case "end_rod" -> {
                return 14;
            }
            case "glow_lichen", "redstone_torch", "redstone_wall_torch" -> {
                return type.startsWith("redstone") && "false".equals(props(state).get("lit")) ? 0 : 7;
            }
            case "magma_block" -> {
                return 3;
            }
            case "soul_fire" -> {
                return 10;
            }
            case "redstone_lamp" -> {
                return "true".equals(props(state).get("lit")) ? 15 : 0;
            }
            case "furnace", "blast_furnace", "smoker" -> {
                return "true".equals(props(state).get("lit")) ? 13 : 0;
            }
            case "campfire", "soul_campfire" -> {
                return "false".equals(props(state).get("lit")) ? 0 : soul ? 10 : 15;
            }
            default -> {
            }
        }
        if (type.endsWith("torch")) return soul ? 10 : 14;
        if (lantern(type)) return soul ? 10 : 15;
        if (type.endsWith("froglight")) return 15;
        return 0;
    }

    private static boolean lantern(String type) {
        return type.endsWith("lantern") && !type.equals("sea_lantern") && !type.equals("jack_o_lantern");
    }

    private static Map<String, String> props(String state) {
        return state == null || state.indexOf('[') < 0 ? Map.of() : BlockState.parse(state).props();
    }
}
