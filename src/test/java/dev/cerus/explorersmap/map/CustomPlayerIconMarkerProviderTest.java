package dev.cerus.explorersmap.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.util.Config;
import dev.cerus.explorersmap.ExplorersMapPlugin;
import dev.cerus.explorersmap.config.ExplorersMapConfig;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomPlayerIconMarkerProviderTest {
    @Test
    @SuppressWarnings("deprecation")
    void unlimitedRangePreservesProviderFilteringAndMarkerIdentity() {
        ExplorersMapConfig config = new ExplorersMapConfig();
        ExplorersMapPlugin plugin = mock(ExplorersMapPlugin.class);
        @SuppressWarnings("unchecked")
        Config<ExplorersMapConfig> wrapper = mock(Config.class);
        when(wrapper.get()).thenReturn(config);
        when(plugin.getConfig()).thenReturn(wrapper);
        MarkersCollector delegate = mock(MarkersCollector.class);
        Predicate<PlayerRef> filter = ignored -> true;
        when(delegate.getPlayerMapFilter()).thenReturn(filter);
        MapMarker marker = new MapMarker();
        marker.id = "player-marker";
        World world = mock(World.class);
        Player player = mock(Player.class);
        WorldMapManager.MarkerProvider original = (actualWorld, actualPlayer, collector) -> {
            assertSame(world, actualWorld);
            assertSame(player, actualPlayer);
            assertSame(filter, collector.getPlayerMapFilter());
            assertTrue(collector.isInViewDistance(1_000_000, -1_000_000));
            collector.add(marker);
        };
        try (MockedStatic<ExplorersMapPlugin> scoped = mockStatic(ExplorersMapPlugin.class)) {
            scoped.when(ExplorersMapPlugin::getInstance).thenReturn(plugin);
            new CustomPlayerIconMarkerProvider(original).update(world, player, delegate);
        }
        verify(delegate).addIgnoreViewDistance(same(marker));
        verify(delegate, never()).add(any());
    }

    @Test
    void disabledUnlimitedRangeUsesOriginalCollector() {
        ExplorersMapConfig config = new ExplorersMapConfig();
        config.setUnlimitedPlayerTracking(false);
        ExplorersMapPlugin plugin = mock(ExplorersMapPlugin.class);
        @SuppressWarnings("unchecked")
        Config<ExplorersMapConfig> wrapper = mock(Config.class);
        when(wrapper.get()).thenReturn(config);
        when(plugin.getConfig()).thenReturn(wrapper);
        WorldMapManager.MarkerProvider original = mock(WorldMapManager.MarkerProvider.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        MarkersCollector collector = mock(MarkersCollector.class);
        try (MockedStatic<ExplorersMapPlugin> scoped = mockStatic(ExplorersMapPlugin.class)) {
            scoped.when(ExplorersMapPlugin::getInstance).thenReturn(plugin);
            new CustomPlayerIconMarkerProvider(original).update(world, player, collector);
        }
        verify(original).update(world, player, collector);
    }
}
