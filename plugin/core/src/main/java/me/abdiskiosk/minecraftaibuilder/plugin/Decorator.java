package me.abdiskiosk.minecraftaibuilder.plugin;

import me.abdiskiosk.minecraftaibuilder.common.Box;
import me.abdiskiosk.minecraftaibuilder.common.EditException;
import me.abdiskiosk.minecraftaibuilder.common.Vec3;
import me.abdiskiosk.minecraftaibuilder.plugin.tools.DecorSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sets sign text and container contents and places item frames and armor stands through the Bukkit API, because
 * WorldEdit's NBT format differs between Minecraft versions. WorldEdit's history does not see these changes.
 * Every item is checked against the world before anything changes.
 */
public final class Decorator {

    public record Result(int set, int spawned, int removed) {
    }

    private Decorator() {
    }

    public static Result apply(BuildService service, BuildService.Caller caller, List<DecorSpec.Item> items, Box remove) {
        if (remove != null) service.checkEditable(caller, remove, "remove");
        for (int i = 0; i < items.size(); i++) {
            Vec3 at = items.get(i).at();
            service.checkEditable(caller, Box.of(at, at), "items[" + i + "]");
        }
        return service.sync(() -> {
            World world = Bukkit.getWorld(caller.world());
            if (world == null) throw new EditException("unknown world '" + caller.world() + "'");
            for (int i = 0; i < items.size(); i++) {
                try {
                    check(world, items.get(i));
                } catch (EditException e) {
                    throw new EditException("items[" + i + "]: " + e.getMessage(), e);
                }
            }
            int removed = remove == null ? 0 : remove(world, remove);
            int set = 0;
            List<Entity> spawned = new ArrayList<>();
            int i = 0;
            try {
                // Entities first: a frame that cannot hang is the one failure left, and spawned entities can be taken back.
                for (; i < items.size(); i++) {
                    DecorSpec.Item item = items.get(i);
                    if (item instanceof DecorSpec.Frame f) spawned.add(frame(world, f));
                    else if (item instanceof DecorSpec.Stand s) spawned.add(stand(world, s));
                }
                for (i = 0; i < items.size(); i++) {
                    DecorSpec.Item item = items.get(i);
                    if (item instanceof DecorSpec.SignText t) sign(world, t);
                    else if (item instanceof DecorSpec.Contents c) fill(world, c);
                    else continue;
                    set++;
                }
            } catch (RuntimeException e) {
                spawned.forEach(Entity::remove);
                throw new EditException("items[" + i + "]: " + e.getMessage(), e);
            }
            return new Result(set, spawned.size(), removed);
        });
    }

    /** Removes item frames and armor stands inside {@code box}. */
    public static int remove(World world, Box box) {
        int removed = 0;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof ItemFrame) && !(e instanceof ArmorStand)) continue;
            Location l = e.getLocation();
            Vec3 p = new Vec3(l.getBlockX(), l.getBlockY(), l.getBlockZ());
            if (box.contains(Box.of(p, p))) {
                e.remove();
                removed++;
            }
        }
        return removed;
    }

    private static void check(World world, DecorSpec.Item item) {
        Block block = block(world, item.at());
        if (item instanceof DecorSpec.SignText) {
            if (!(block.getState() instanceof Sign)) throw new EditException("text needs a sign at " + item.at() + "; place one first");
        } else if (item instanceof DecorSpec.Contents c) {
            BlockState state = block.getState();
            if (!(state instanceof InventoryHolder holder)) {
                throw new EditException("contents needs a container (chest, barrel, ...) at " + item.at() + "; place one first");
            }
            int slots = 0;
            for (Map.Entry<String, Integer> e : c.items().entrySet()) {
                int max = Math.max(1, material(e.getKey()).getMaxStackSize());
                slots += (e.getValue() + max - 1) / max;
            }
            int size = holder.getInventory().getSize();
            if (slots > size) throw new EditException("contents need " + slots + " slots but the container at " + item.at() + " has " + size);
        } else if (item instanceof DecorSpec.Frame f) {
            if (!block.isEmpty()) throw new EditException("a frame needs air at " + item.at());
            Block behind = block.getRelative(face(f.facing()).getOppositeFace());
            if (behind.isEmpty() || behind.isLiquid()) throw new EditException(noSupport(f));
            if (!f.item().isEmpty()) material(f.item());
        } else if (item instanceof DecorSpec.Stand s) {
            for (String name : new String[]{s.head(), s.chest(), s.legs(), s.feet(), s.hand()}) {
                if (name != null) material(name);
            }
        }
    }

    private static Entity frame(World world, DecorSpec.Frame f) {
        ItemFrame frame;
        try {
            frame = world.spawn(block(world, f.at()).getLocation(), ItemFrame.class);
        } catch (IllegalArgumentException e) {
            throw new EditException(noSupport(f), e);
        }
        frame.setFacingDirection(face(f.facing()), true);
        if (!f.item().isEmpty()) frame.setItem(new ItemStack(material(f.item()), 1));
        return frame;
    }

    private static Entity stand(World world, DecorSpec.Stand s) {
        Location at = new Location(world, s.at().x() + 0.5, s.at().y(), s.at().z() + 0.5, s.yaw(), 0);
        ArmorStand stand = world.spawn(at, ArmorStand.class);
        EntityEquipment equipment = stand.getEquipment();
        if (s.head() != null) equipment.setHelmet(stack(s.head()));
        if (s.chest() != null) equipment.setChestplate(stack(s.chest()));
        if (s.legs() != null) equipment.setLeggings(stack(s.legs()));
        if (s.feet() != null) equipment.setBoots(stack(s.feet()));
        if (s.hand() != null) equipment.setItemInHand(stack(s.hand()));
        stand.setArms(s.arms());
        stand.setSmall(s.small());
        stand.setBasePlate(s.base());
        return stand;
    }

    private static void sign(World world, DecorSpec.SignText t) {
        Sign sign = (Sign) block(world, t.at()).getState();
        for (int n = 0; n < 4; n++) sign.setLine(n, t.lines().get(n));
        sign.update();
    }

    private static void fill(World world, DecorSpec.Contents c) {
        Inventory inventory = ((InventoryHolder) block(world, c.at()).getState()).getInventory();
        inventory.clear();
        int slot = 0;
        for (Map.Entry<String, Integer> e : c.items().entrySet()) {
            Material m = material(e.getKey());
            int max = Math.max(1, m.getMaxStackSize());
            for (int left = e.getValue(); left > 0; left -= max) inventory.setItem(slot++, new ItemStack(m, Math.min(max, left)));
        }
    }

    private static Block block(World world, Vec3 at) {
        return world.getBlockAt(at.x(), at.y(), at.z());
    }

    private static BlockFace face(String facing) {
        return BlockFace.valueOf(facing.toUpperCase(Locale.ROOT));
    }

    private static String noSupport(DecorSpec.Frame f) {
        return "an item frame facing " + f.facing() + " at " + f.at() + " needs a solid block behind it (on its "
                + face(f.facing()).getOppositeFace().name().toLowerCase(Locale.ROOT) + " side)";
    }

    private static ItemStack stack(String name) {
        return new ItemStack(material(name), 1);
    }

    private static final Method MATCH_MATERIAL;

    static {
        try {
            MATCH_MATERIAL = Material.class.getMethod("matchMaterial", String.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Resolves an item name through reflection on purpose: modern servers rewrite a plugin's direct
     * {@code Material} lookups to legacy names when its plugin.yml has no api-version (this one must not, to load
     * on 1.8), while a reflective call is left alone and so sees this server's real item names.
     */
    static Material material(String name) {
        Material m;
        try {
            m = (Material) MATCH_MATERIAL.invoke(null, name);
        } catch (IllegalAccessException | InvocationTargetException e) {
            m = null;
        }
        if (m == null) throw new EditException("unknown item '" + name + "'; use this server's item names, e.g. bread, iron_helmet");
        return m;
    }
}
