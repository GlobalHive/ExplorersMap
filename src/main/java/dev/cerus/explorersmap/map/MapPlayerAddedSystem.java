package dev.cerus.explorersmap.map;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.cerus.explorersmap.ExplorersMapPlugin;
import java.util.Set;
import javax.annotation.Nonnull;

/** Installs the exploration tracker for every join and world transfer, once the player is in the store. */
public class MapPlayerAddedSystem extends RefSystem<EntityStore> {

    private final ExplorersMapPlugin plugin;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, PlayerSystems.PlayerAddedSystem.class));

    public MapPlayerAddedSystem(ExplorersMapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (player != null && playerRef != null) {
            plugin.initializePlayerMap(player, playerRef, commandBuffer.getExternalData().getWorld());
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }
}
