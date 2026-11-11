package me.abdiskiosk.minecraftaibuilder.bridge.we7;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import me.abdiskiosk.minecraftaibuilder.common.DryRunStore;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A {@link World} that records block writes in a {@link DryRunStore} instead of placing them, and answers block reads
 * from it. An edit session built on it runs every op as usual and changes nothing. Anything else that would change
 * the world (container contents, side effects, entities, biomes, trees, item drops) is swallowed; plain reads go
 * to the real world.
 */
final class DryWorld implements InvocationHandler {

    /** Method name prefixes that change the world; never forwarded. */
    private static final List<String> MUTATORS = List.of("set", "clear", "drop", "simulate", "regenerate", "generate",
            "fix", "notify", "apply", "play", "queue", "use", "create", "remove", "kill", "commit", "save", "unload");

    private final World real;
    private final DryRunStore<BlockState> store;

    private DryWorld(World real, DryRunStore<BlockState> store) {
        this.real = real;
        this.store = store;
    }

    static World wrap(World real, DryRunStore<BlockState> store) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, new DryWorld(real, store));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int n = args == null ? 0 : args.length;
        switch (name) {
            case "equals" -> {
                return n == 1 && (args[0] == proxy || real.equals(args[0]));
            }
            case "hashCode" -> {
                return real.hashCode();
            }
            case "toString" -> {
                return "dry run of " + real;
            }
            default -> {
            }
        }
        BlockVector3 pos = n > 0 ? position(args) : null;
        if (pos != null && (name.equals("getBlock") || name.equals("getFullBlock") || name.equals("getLazyBlock"))) {
            BlockState recorded = store.read(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ(), () -> null);
            if (recorded == null) return forward(method, args);
            return name.equals("getFullBlock") ? recorded.toBaseBlock() : recorded;
        }
        if (pos != null && name.equals("setBlock")) {
            BlockStateHolder<?> block = block(args);
            if (block != null) {
                store.write(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ(), block.toImmutableState(), () -> real.getBlock(pos));
                return true;
            }
        }
        for (String prefix : MUTATORS) {
            if (name.startsWith(prefix)) return nothing(method.getReturnType());
        }
        return forward(method, args);
    }

    private Object forward(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The position a block method is about: a BlockVector3, or three ints. */
    private static BlockVector3 position(Object[] args) {
        if (args[0] instanceof BlockVector3 v) return v;
        if (args.length >= 3 && args[0] instanceof Integer x && args[1] instanceof Integer y && args[2] instanceof Integer z) {
            return BlockVector3.at(x, y, z);
        }
        return null;
    }

    private static BlockStateHolder<?> block(Object[] args) {
        for (Object a : args) {
            if (a instanceof BlockStateHolder<?> b) return b;
        }
        return null;
    }

    private static Object nothing(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0f;
        if (Set.class.isAssignableFrom(type)) return Set.of();
        if (Collection.class.isAssignableFrom(type)) return List.of();
        return null;
    }
}
