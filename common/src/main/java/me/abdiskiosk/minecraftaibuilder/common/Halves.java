package me.abdiskiosk.minecraftaibuilder.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Doors, beds and tall plants are two blocks. WorldEdit places exactly the block it is given, so a single
 * {@code oak_door[facing=south]} would stand as a lone lower half. This adds the missing half, so a caller names
 * such a block once.
 */
public final class Halves {

    private static final Set<String> TALL_PLANTS = Set.of(
            "sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern", "pitcher_plant");

    private Halves() {
    }

    /**
     * @return {@code blocks} with the other half added after each door, bed or tall plant whose partner position
     *         is not in the list; the same list when nothing is missing
     */
    public static List<Op.BlockAt> complete(List<Op.BlockAt> blocks) {
        Set<Vec3> taken = null;
        List<Op.BlockAt> out = null;
        for (int i = 0; i < blocks.size(); i++) {
            Op.BlockAt partner = partner(blocks.get(i));
            if (partner != null) {
                if (taken == null) {
                    taken = new HashSet<>();
                    for (Op.BlockAt b : blocks) taken.add(b.pos());
                }
                if (taken.add(partner.pos())) {
                    if (out == null) out = new ArrayList<>(blocks.subList(0, i));
                    out.add(blocks.get(i));
                    out.add(partner);
                    continue;
                }
            }
            if (out != null) out.add(blocks.get(i));
        }
        return out == null ? blocks : out;
    }

    /** The other half of a two-block block, or null when {@code block} is not one. */
    static Op.BlockAt partner(Op.BlockAt block) {
        String state = block.state();
        String type = RegionCodec.stripProperties(state);
        boolean door = type.endsWith("_door"), bed = type.endsWith("_bed");
        if (!door && !bed && !TALL_PLANTS.contains(type)) return null;
        BlockState parsed;
        try {
            parsed = BlockState.parse(state);
        } catch (EditException e) {
            return null; // reported with a clear message when the block is checked
        }
        Map<String, String> props = new LinkedHashMap<>(parsed.props());
        if (bed) {
            boolean foot = !"head".equals(props.get("part"));
            Vec3 f = BlockTraits.facing(state);
            props.put("part", foot ? "head" : "foot");
            Vec3 pos = foot ? block.pos().add(f.x(), 0, f.z()) : block.pos().add(-f.x(), 0, -f.z());
            return new Op.BlockAt(pos, new BlockState(parsed.type(), props).toString());
        }
        boolean lower = !"upper".equals(props.get("half"));
        props.put("half", lower ? "upper" : "lower");
        return new Op.BlockAt(block.pos().add(0, lower ? 1 : -1, 0), new BlockState(parsed.type(), props).toString());
    }
}
