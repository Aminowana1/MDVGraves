package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Level;

public final class LibsDisguisesBridge {
    private final MDVGravesPlugin plugin;
    private boolean warned;

    public LibsDisguisesBridge(MDVGravesPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        Plugin libs = Bukkit.getPluginManager().getPlugin("LibsDisguises");
        return libs != null && libs.isEnabled();
    }

    public boolean disguiseAsPlayer(Entity body, Player player, String fallbackSkinName, boolean showName) {
        if (!isAvailable())
            return false;
        try {
            Class<?> disguiseClass = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
            Class<?> playerDisguiseClass = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
            Class<?> apiClass = Class.forName("me.libraryaddict.disguise.DisguiseAPI");

            Object disguise = createPlayerDisguise(playerDisguiseClass, player, fallbackSkinName);
            invokeIfPresent(disguise, "setNameVisible", new Class<?>[]{boolean.class}, showName);
            invokeIfPresent(disguise, "setDisplayedInTab", new Class<?>[]{boolean.class}, false);
            invokeIfPresent(disguise, "setDynamicName", new Class<?>[]{boolean.class}, false);
            // Hace que la hitbox siga al disfraz de jugador cuando la versión de
            // LibsDisguises lo soporta, en lugar de conservar la altura del HUSK.
            invokeIfPresent(disguise, "setModifyBoundingBox", new Class<?>[]{boolean.class}, true);

            Method disguiseToAll = apiClass.getMethod("disguiseToAll", Entity.class, disguiseClass);
            disguiseToAll.invoke(null, body, disguise);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!warned) {
                warned = true;
                plugin.getLogger().log(Level.WARNING,
                        "No se pudo aplicar PlayerDisguise al cuerpo. El cuerpo seguirá funcionando como entidad base.", ex);
            }
            return false;
        }
    }

    public void removeDisguise(Entity body) {
        if (body == null || !isAvailable())
            return;
        try {
            Class<?> apiClass = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            Method undisguise = apiClass.getMethod("undisguiseToAll", Entity.class);
            undisguise.invoke(null, body);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private Object createPlayerDisguise(Class<?> playerDisguiseClass, Player player, String fallbackSkinName)
            throws ReflectiveOperationException {
        try {
            Constructor<?> live = playerDisguiseClass.getConstructor(Player.class);
            return live.newInstance(player);
        } catch (NoSuchMethodException ignored) {
        }

        String skin = fallbackSkinName == null || fallbackSkinName.isBlank() ? player.getName() : fallbackSkinName;
        try {
            Constructor<?> namedSkin = playerDisguiseClass.getConstructor(String.class, String.class);
            return namedSkin.newInstance(player.getName(), skin);
        } catch (NoSuchMethodException ignored) {
        }

        Constructor<?> named = playerDisguiseClass.getConstructor(String.class);
        Object disguise = named.newInstance(player.getName());
        invokeIfPresent(disguise, "setSkin", new Class<?>[]{String.class}, skin);
        return disguise;
    }

    private void invokeIfPresent(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method found = target.getClass().getMethod(method, types);
            found.invoke(target, args);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
