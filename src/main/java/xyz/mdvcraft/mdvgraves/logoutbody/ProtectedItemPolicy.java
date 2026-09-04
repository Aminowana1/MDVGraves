package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ProtectedItemPolicy {
    private final MDVGravesPlugin plugin;
    private Method nbtGet;
    private Method nbtGetString;
    private Method nbtGetBoolean;
    private Method nbtHasTag;
    private boolean mythicBridgeReady;
    private boolean warningLogged;
    private boolean mmoItemsKeepsSoulbound;
    private Set<NamespacedKey> extraPersistentKeys = Set.of();

    public ProtectedItemPolicy(MDVGravesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        setupMythicLibBridge();
        this.mmoItemsKeepsSoulbound = resolveMmoItemsKeepSoulbound();
        Set<NamespacedKey> parsed = new HashSet<>();
        for (String raw : plugin.getConfig().getStringList("logout-body.protected-items.persistent-data-keys")) {
            if (raw == null || raw.isBlank())
                continue;
            NamespacedKey key = NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT));
            if (key != null)
                parsed.add(key);
            else
                plugin.getLogger().warning("PDC key inválida en logout-body.protected-items: " + raw);
        }
        this.extraPersistentKeys = Set.copyOf(parsed);
    }

    public boolean keepsWholeInventory(Player player) {
        Boolean gamerule = player.getWorld().getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY);
        if (Boolean.TRUE.equals(gamerule))
            return true;
        return plugin.getConfig().getBoolean("utilities.keep-inventory.enabled", true)
                && player.hasPermission("mdvgraves.keepinventory");
    }

    public boolean isProtected(ItemStack item, UUID ownerUuid) {
        if (item == null || item.getType().isAir())
            return false;

        if (isMmoItemsDisableDeathDrop(item))
            return true;

        if (mmoItemsKeepsSoulbound && isMmoItemsSoulbound(item, ownerUuid))
            return true;

        if (!extraPersistentKeys.isEmpty() && item.hasItemMeta()) {
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            for (NamespacedKey key : extraPersistentKeys) {
                if (pdc.getKeys().contains(key))
                    return true;
            }
        }
        return false;
    }


    private boolean isMmoItemsDisableDeathDrop(ItemStack item) {
        if (!mythicBridgeReady)
            return false;
        try {
            Object nbt = nbtGet.invoke(null, item);
            boolean hasTag = Boolean.TRUE.equals(nbtHasTag.invoke(nbt, "MMOITEMS_DISABLE_DEATH_DROP"));
            return hasTag && Boolean.TRUE.equals(nbtGetBoolean.invoke(nbt, "MMOITEMS_DISABLE_DEATH_DROP"));
        } catch (ReflectiveOperationException ex) {
            if (!warningLogged) {
                warningLogged = true;
                plugin.getLogger().log(Level.WARNING,
                        "No se pudo comprobar MMOITEMS_DISABLE_DEATH_DROP para los cuerpos desconectados.", ex);
            }
            return false;
        }
    }

    private boolean isMmoItemsSoulbound(ItemStack item, UUID ownerUuid) {
        if (!mythicBridgeReady)
            return false;
        try {
            Object nbt = nbtGet.invoke(null, item);
            boolean hasTag = Boolean.TRUE.equals(nbtHasTag.invoke(nbt, "MMOITEMS_SOULBOUND"));
            if (!hasTag)
                return false;
            Object raw = nbtGetString.invoke(nbt, "MMOITEMS_SOULBOUND");
            return raw != null && raw.toString().contains(ownerUuid.toString());
        } catch (ReflectiveOperationException ex) {
            if (!warningLogged) {
                warningLogged = true;
                plugin.getLogger().log(Level.WARNING,
                        "No se pudo comprobar MMOITEMS_SOULBOUND para los cuerpos desconectados.", ex);
            }
            return false;
        }
    }

    private void setupMythicLibBridge() {
        mythicBridgeReady = false;
        warningLogged = false;
        nbtGet = null;
        nbtGetString = null;
        nbtGetBoolean = null;
        nbtHasTag = null;
        if (Bukkit.getPluginManager().getPlugin("MythicLib") == null)
            return;
        try {
            Class<?> nbtItemClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            nbtGet = nbtItemClass.getMethod("get", ItemStack.class);
            nbtGetString = nbtItemClass.getMethod("getString", String.class);
            nbtGetBoolean = nbtItemClass.getMethod("getBoolean", String.class);
            nbtHasTag = nbtItemClass.getMethod("hasTag", String.class);
            mythicBridgeReady = true;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "No se pudo inicializar el puente MythicLib para ítems protegidos.", ex);
        }
    }

    private boolean resolveMmoItemsKeepSoulbound() {
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null)
            return false;
        try {
            Plugin raw = Bukkit.getPluginManager().getPlugin("MMOItems");
            if (raw instanceof JavaPlugin javaPlugin) {
                FileConfiguration config = javaPlugin.getConfig();
                return config.getBoolean("soulbound.keep-on-death", true);
            }
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING,
                    "No se pudo leer soulbound.keep-on-death de MMOItems; se asumirá true.", ex);
        }
        return true;
    }
}
