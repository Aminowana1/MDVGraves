package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Chunk;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public record ActiveLogoutBody(
        UUID playerUuid,
        Mob entity,
        BukkitTask expiryTask,
        Chunk chunk
) {
}
