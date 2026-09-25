package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BodyPatchTest {
    private void set(Object object, String field, Object value) throws Exception {
        var f = object.getClass().getDeclaredField(field);
        f.setAccessible(true); f.set(object, value);
    }

    @Test void suffocationDefaultsToCancelledOnlyForBodies() throws Exception {
        MDVGravesPlugin plugin = mock(MDVGravesPlugin.class);
        var config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        var manager = mock(LogoutBodyManager.class, CALLS_REAL_METHODS);
        UUID body = UUID.randomUUID();
        set(manager, "plugin", plugin);
        set(manager, "ownerByEntity", Map.of(body, UUID.randomUUID()));
        Entity entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(body);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.SUFFOCATION);
        manager.onBodySuffocationDamage(event);
        verify(event).setCancelled(true);
        clearInvocations(event);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        manager.onBodySuffocationDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
        when(entity.getUniqueId()).thenReturn(body);
        config.set("logout-body.entity.suffocation-damage", true);
        manager.onBodySuffocationDamage(event);
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test void combatAndOtherEnvironmentalDamageAreUnchanged() throws Exception {
        var manager = mock(LogoutBodyManager.class, CALLS_REAL_METHODS);
        for (var cause : List.of(EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                EntityDamageEvent.DamageCause.FALL, EntityDamageEvent.DamageCause.DROWNING,
                EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getCause()).thenReturn(cause);
            manager.onBodySuffocationDamage(event);
            verify(event, never()).setCancelled(anyBoolean());
        }
    }

    @Test void markedBookIsRetainedAndExcludedFromLootButOrdinaryBooksAreNot() {
        MDVGravesPlugin plugin = mock(MDVGravesPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            var policy = new ProtectedItemPolicy(plugin);
            UUID owner = UUID.randomUUID();
            ItemStack book = mock(ItemStack.class);
            // No running Paper registry in this isolated test; model a non-air item.
            when(book.getType()).thenReturn(mock(Material.class));
            when(book.getAmount()).thenReturn(1);
            when(book.clone()).thenReturn(book);
            when(book.hasItemMeta()).thenReturn(true);
            ItemMeta meta = mock(ItemMeta.class);
            when(book.getItemMeta()).thenReturn(meta);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(meta.getPersistentDataContainer()).thenReturn(pdc);
            NamespacedKey key = NamespacedKey.fromString("mdvsocial:social_menu_item");
            when(pdc.get(key, PersistentDataType.BYTE)).thenReturn((byte) 1);
            var snapshot = new PlayerInventorySnapshot(new ItemStack[]{book}, new ItemStack[0], book, 0);
            assertTrue(policy.isProtected(book, owner));
            assertTrue(snapshot.itemsExcluding(i -> policy.isProtected(i, owner)).isEmpty());
            assertEquals(2, snapshot.filtered(i -> policy.isProtected(i, owner)).allItems().size());
            when(pdc.get(key, PersistentDataType.BYTE)).thenReturn(null);
            assertFalse(policy.isProtected(book, owner));
            assertEquals(2, snapshot.itemsExcluding(i -> policy.isProtected(i, owner)).size());
            when(pdc.get(key, PersistentDataType.BYTE)).thenReturn((byte) 0);
            assertFalse(policy.isProtected(book, owner));
        }
    }
}
