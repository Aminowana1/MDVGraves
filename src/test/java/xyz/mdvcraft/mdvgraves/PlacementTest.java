package xyz.mdvcraft.mdvgraves;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlacementTest {
    MDVGravesPlugin plugin;
    World world;
    YamlConfiguration config;
    Map<String, Block> blocks;
    Material air, stone;
    int highestRead;

    @BeforeEach void setup() {
        plugin = mock(MDVGravesPlugin.class, CALLS_REAL_METHODS);
        config = new YamlConfiguration();
        config.set("settings.placement-search-radius", 0);
        config.set("settings.replace-thin-blocks", false);
        doReturn(config).when(plugin).getConfig();
        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        air = mock(Material.class); when(air.isAir()).thenReturn(true);
        stone = mock(Material.class);
        blocks = new HashMap<>(); highestRead = Integer.MIN_VALUE;
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0), y=call.getArgument(1), z=call.getArgument(2);
            assertTrue(y >= -64 && y < 320, "Out of world: " + y);
            highestRead = Math.max(highestRead, y);
            return block(x,y,z);
        });
    }
    Block block(int x,int y,int z) {
        return blocks.computeIfAbsent(x+":"+y+":"+z, ignored -> {
            Block b=mock(Block.class);
            when(b.getType()).thenReturn(air);
            when(b.getX()).thenReturn(x); when(b.getY()).thenReturn(y); when(b.getZ()).thenReturn(z);
            return b;
        });
    }
    void solid(int x,int y,int z) { when(block(x,y,z).getType()).thenReturn(stone); }
    Block place(double y) throws Exception {
        var method = MDVGravesPlugin.class.getDeclaredMethod("findPlacementBlock", Location.class);
        method.setAccessible(true);
        return (Block) method.invoke(plugin, new Location(world,0,y,0));
    }
    @Test void caveDeathStaysUnderLowRoofInsteadOfLandingOnTop() throws Exception {
        solid(0,19,0); solid(0,21,0); solid(0,90,0);
        assertSame(block(0,20,0), place(20));
        assertTrue(highestRead <= 20);
    }
    @Test void fractionalFeetDoNotCrossCaveCeiling() throws Exception {
        solid(0,19,0); solid(0,21,0);
        assertSame(block(0,20,0), place(20.3));
        assertTrue(highestRead <= 21);
    }
    @Test void partialFloorAllowsHeadAboveSupport() throws Exception {
        solid(0,19,0);
        assertSame(block(0,20,0), place(19.9375));
    }
    @Test void airborneDeathFallsToFirstFloorNotLowerCave() throws Exception {
        solid(0,9,0); solid(0,-20,0);
        assertSame(block(0,10,0), place(30));
    }
    @Test void blockedColumnSearchesAdjacentSpaceWithoutReplacingSolidBlocks() throws Exception {
        config.set("settings.placement-search-radius", 1);
        solid(0,19,0); solid(0,20,0); solid(-1,19,-1);
        assertSame(block(-1,20,-1), place(20));
    }
    @Test void noFloorReturnsNoPlacementInsteadOfFloatingBag() throws Exception {
        assertNull(place(20));
    }
    @Test void negativeHeightCaveAndWorldLimitsAreRespected() throws Exception {
        solid(0,-31,0); solid(0,-29,0);
        assertSame(block(0,-30,0), place(-30));
        blocks.clear(); solid(0,-64,0);
        assertSame(block(0,-63,0), place(-80));
        blocks.clear(); solid(0,318,0);
        assertSame(block(0,319,0), place(350));
    }
}
