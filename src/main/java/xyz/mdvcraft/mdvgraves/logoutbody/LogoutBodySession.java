package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public record LogoutBodySession(
        UUID playerUuid,
        String playerName,
        LogoutBodyState state,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        double health,
        double maxHealth,
        double absorption,
        int totalExperience,
        int level,
        float exp,
        PlayerInventorySnapshot inventory,
        PlayerInventorySnapshot protectedInventory,
        boolean keepAllInventory,
        boolean ownerProtected,
        String graveTexture,
        UUID graveId,
        long createdAt,
        long expiresAt
) {
    public Location location() {
        World resolved = Bukkit.getWorld(world);
        return resolved == null ? null : new Location(resolved, x, y, z, yaw, pitch);
    }

    public LogoutBodySession withState(LogoutBodyState newState) {
        return new LogoutBodySession(playerUuid, playerName, newState, world, x, y, z, yaw, pitch,
                health, maxHealth, absorption, totalExperience, level, exp, inventory, protectedInventory,
                keepAllInventory, ownerProtected, graveTexture, graveId, createdAt, expiresAt);
    }

    public LogoutBodySession withStateAndGrave(LogoutBodyState newState, UUID newGraveId) {
        return new LogoutBodySession(playerUuid, playerName, newState, world, x, y, z, yaw, pitch,
                health, maxHealth, absorption, totalExperience, level, exp, inventory, protectedInventory,
                keepAllInventory, ownerProtected, graveTexture, newGraveId, createdAt, expiresAt);
    }

    public LogoutBodySession withReconnectData(Location location, double bodyHealth, double bodyMaxHealth,
                                               double bodyAbsorption) {
        return new LogoutBodySession(playerUuid, playerName, LogoutBodyState.RESTORE_PENDING,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), bodyHealth, bodyMaxHealth, bodyAbsorption,
                totalExperience, level, exp, inventory, protectedInventory, keepAllInventory,
                ownerProtected, graveTexture, graveId, createdAt, expiresAt);
    }
}
