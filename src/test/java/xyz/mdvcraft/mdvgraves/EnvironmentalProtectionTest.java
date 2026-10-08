package xyz.mdvcraft.mdvgraves;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnvironmentalProtectionTest {
    private MDVGravesPlugin plugin;
    private YamlConfiguration config;
    private World world;
    private Block grave;
    private Block ordinary;
    private UUID graveId;
    private Map<Object, UUID> locations;

    @BeforeEach
    void setup() throws Exception {
        plugin = mock(MDVGravesPlugin.class, CALLS_REAL_METHODS);
        config = new YamlConfiguration();
        doReturn(config).when(plugin).getConfig();
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        grave = block(10, 64, 10);
        ordinary = block(11, 64, 10);
        graveId = UUID.randomUUID();
        locations = new HashMap<>();
        var constructor = Class.forName(MDVGravesPlugin.class.getName() + "$BlockKey")
                .getDeclaredConstructor(String.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        locations.put(constructor.newInstance("world", 10, 64, 10), graveId);
        setField("gravesByBlock", locations);
        setField("graves", new HashMap<>());
        setField("activeViewers", new HashMap<>());
        setField("graveKey", new NamespacedKey("mdvgraves", "grave_id"));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = MDVGravesPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private Block block(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getState()).thenReturn(mock(BlockState.class));
        return block;
    }

    @Test
    void flowingWaterOrLavaCannotDestroyClosedBagEvenWithLegacySettingDisabled() {
        config.set("settings.protect-from-fluids", false);
        var event = mock(BlockFromToEvent.class);
        when(event.getToBlock()).thenReturn(grave);

        plugin.onFluidFlow(event);

        verify(event).setCancelled(true);
        assertTrue(locations.containsValue(graveId), "Fluid must not remove the registered grave");
        verify(grave, never()).setType(any(Material.class), anyBoolean());
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    void directBucketProtectsActualTargetIncludingOffhandWithoutBlockingNearbyPlacement() {
        var event = mock(PlayerBucketEmptyEvent.class);
        when(event.getBlock()).thenReturn(grave);
        when(event.getBlockClicked()).thenReturn(ordinary);

        plugin.onBucketEmpty(event);

        verify(event).setCancelled(true);
        verify(event, never()).getHand();
        clearInvocations(event);
        when(event.getBlock()).thenReturn(ordinary);
        when(event.getBlockClicked()).thenReturn(grave);

        plugin.onBucketEmpty(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void indirectPhysicsDestructionIsCancelledAndDecorativeHeadCannotDrop() {
        var event = mock(BlockDestroyEvent.class);
        when(event.getBlock()).thenReturn(grave);

        plugin.onEnvironmentalDestroy(event);

        verify(event).setCancelled(true);
        verify(event).setWillDrop(false);
        verify(event).setExpToDrop(0);
        assertEquals(1, locations.size());
        verify(grave, never()).setType(any(Material.class), anyBoolean());
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    void ordinaryPlayerHeadStillBreaksNormally() {
        Skull skull = mock(Skull.class);
        when(skull.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(ordinary.getState()).thenReturn(skull);
        var event = mock(BlockDestroyEvent.class);
        when(event.getBlock()).thenReturn(ordinary);

        plugin.onEnvironmentalDestroy(event);

        verify(event, never()).setCancelled(anyBoolean());
        verify(event, never()).setWillDrop(anyBoolean());
    }

    @Test
    void gravePersistentMarkerProtectsHeadBeforeLocationCacheIsAvailable() {
        locations.clear();
        Skull skull = mock(Skull.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(skull.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(graveId.toString());
        when(grave.getState()).thenReturn(skull);
        var event = mock(BlockDestroyEvent.class);
        when(event.getBlock()).thenReturn(grave);

        plugin.onEnvironmentalDestroy(event);

        verify(event).setCancelled(true);
        verify(event).setWillDrop(false);
    }

    @Test
    void dispenserFluidBucketsCannotReplaceGraveButOtherItemsAreAllowed() {
        Block dispenser = block(9, 64, 10);
        Directional data = mock(Directional.class);
        when(data.getFacing()).thenReturn(BlockFace.EAST);
        when(dispenser.getBlockData()).thenReturn(data);
        when(dispenser.getRelative(BlockFace.EAST)).thenReturn(grave);
        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(item.getType()).thenReturn(material);
        var event = mock(BlockDispenseEvent.class);
        when(event.getBlock()).thenReturn(dispenser);
        when(event.getItem()).thenReturn(item);

        for (String bucket : new String[]{"WATER_BUCKET", "LAVA_BUCKET", "POWDER_SNOW_BUCKET", "AXOLOTL_BUCKET"}) {
            when(material.name()).thenReturn(bucket);
            plugin.onFluidDispense(event);
        }
        verify(event, times(4)).setCancelled(true);
        clearInvocations(event);
        when(material.name()).thenReturn("ARROW");

        plugin.onFluidDispense(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void fireFormationSpreadAndEntityChangesProtectOnlyRegisteredLocations() {
        var burn = mock(BlockBurnEvent.class);
        var fade = mock(BlockFadeEvent.class);
        var form = mock(BlockFormEvent.class);
        var spread = mock(BlockSpreadEvent.class);
        var entity = mock(EntityChangeBlockEvent.class);
        var physics = mock(BlockPhysicsEvent.class);
        when(burn.getBlock()).thenReturn(grave);
        when(fade.getBlock()).thenReturn(grave);
        when(form.getBlock()).thenReturn(grave);
        when(spread.getBlock()).thenReturn(grave);
        when(entity.getBlock()).thenReturn(grave);
        when(physics.getBlock()).thenReturn(grave);

        plugin.onBurn(burn);
        plugin.onFade(fade);
        plugin.onForm(form);
        plugin.onSpread(spread);
        plugin.onEntityChangeBlock(entity);
        plugin.onPhysics(physics);

        verify(burn).setCancelled(true);
        verify(fade).setCancelled(true);
        verify(form).setCancelled(true);
        verify(spread).setCancelled(true);
        verify(entity).setCancelled(true);
        verify(physics).setCancelled(true);

        var unrelated = mock(BlockFromToEvent.class);
        when(unrelated.getToBlock()).thenReturn(ordinary);
        plugin.onFluidFlow(unrelated);
        verify(unrelated, never()).setCancelled(anyBoolean());
    }
}
