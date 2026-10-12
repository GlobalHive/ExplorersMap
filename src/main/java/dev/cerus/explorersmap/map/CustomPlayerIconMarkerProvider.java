package dev.cerus.explorersmap.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.cerus.explorersmap.ExplorersMapPlugin;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Extends marker range while preserving the server's hidden-player and spectator rules. */
public class CustomPlayerIconMarkerProvider implements WorldMapManager.MarkerProvider {
    private final WorldMapManager.MarkerProvider original;

    public CustomPlayerIconMarkerProvider(WorldMapManager.MarkerProvider original) {
        this.original = original;
    }

    @Override
    public void update(@Nonnull World world, @Nonnull Player player, @Nonnull MarkersCollector collector) {
        original.update(world, player,
                ExplorersMapPlugin.getInstance().getConfig().get().isUnlimitedPlayerTracking()
                        ? new UnlimitedRangeCollector(collector) : collector);
    }

    record UnlimitedRangeCollector(MarkersCollector delegate) implements MarkersCollector {
        @Override
        public boolean isInViewDistance(double x, double z) {
            return true;
        }

        @Override
        public void add(MapMarker marker) {
            delegate.addIgnoreViewDistance(marker);
        }

        @Override
        public void addIgnoreViewDistance(MapMarker marker) {
            delegate.addIgnoreViewDistance(marker);
        }

        // Still required by MarkersCollector and used by the server's 0.7.0 provider.
        @Nullable
        @Override
        @SuppressWarnings("deprecation")
        public Predicate<PlayerRef> getPlayerMapFilter() {
            return delegate.getPlayerMapFilter();
        }
    }
}
