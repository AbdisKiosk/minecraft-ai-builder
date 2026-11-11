package me.abdiskiosk.minecraftaibuilder.bridge.we6;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.world.World;
import me.abdiskiosk.minecraftaibuilder.common.DryRunStore;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * WorldEdit 6 version of the dry-run world: block writes go to a {@link DryRunStore}, block reads come from it,
 * other changes are swallowed and plain reads go to the real world. Blocks are copied, since WE6's are mutable.
 */
final class DryWorld implements InvocationHandler {

    private static final List<String> MUTATORS = List.of("set", "clear", "drop", "simulate", "regenerate", "generate",
            "fix", "notify", "apply", "play", "queue", "use", "create", "remove", "kill", "commit", "save", "unload");

    private final World real;
    private final DryRunStore<BaseBlock> store;

    private DryWorld(World real, DryRunStore<BaseBlock> store) {
        this.real = real;
        this.store = store;
    }

    static World wrap(World real, DryRunStore<BaseBlock> store) {
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
        Vector pos = n > 0 && args[0] instanceof Vector v ? v : null;
        if (pos != null) {
            switch (name) {
                case "getBlock", "getLazyBlock" -> {
                    BaseBlock b = recorded(pos);
                    return b != null ? new BaseBlock(b.getId(), b.getData()) : forward(method, args);
                }
                case "getBlockType" -> {
                    BaseBlock b = recorded(pos);
                    return b != null ? b.getId() : forward(method, args);
                }
                case "getBlockData" -> {
                    BaseBlock b = recorded(pos);
                    return b != null ? b.getData() : forward(method, args);
                }
                case "setBlock" -> {
                    if (n >= 2 && args[1] instanceof BaseBlock b) return write(pos, b.getId(), b.getData());
                }
                case "setBlockType" -> {
                    return write(pos, (Integer) args[1], 0);
                }
                case "setBlockData" -> {
                    BaseBlock cur = current(pos);
                    write(pos, cur.getId(), (Integer) args[1]);
                    return null;
                }
                case "setTypeIdAndData" -> {
                    return write(pos, (Integer) args[1], (Integer) args[2]);
                }
                default -> {
                }
            }
        }
        for (String prefix : MUTATORS) {
            if (name.startsWith(prefix)) return nothing(method.getReturnType());
        }
        return forward(method, args);
    }

    private BaseBlock recorded(Vector p) {
        return store.read(p.getBlockX(), p.getBlockY(), p.getBlockZ(), () -> null);
    }

    private BaseBlock current(Vector p) {
        BaseBlock b = recorded(p);
        return b != null ? b : real.getBlock(p);
    }

    private boolean write(Vector p, int id, int data) {
        store.write(p.getBlockX(), p.getBlockY(), p.getBlockZ(), new BaseBlock(id, data), () -> real.getBlock(p));
        return true;
    }

    private Object forward(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
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
