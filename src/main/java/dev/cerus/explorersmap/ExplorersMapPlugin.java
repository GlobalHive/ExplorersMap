package dev.cerus.explorersmap;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMapSettings;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapSettings;
import com.hypixel.hytale.server.core.util.Config;
import dev.cerus.explorersmap.command.ExplorersMapCommand;
import dev.cerus.explorersmap.config.ExplorersMapConfig;
import dev.cerus.explorersmap.map.CustomPlayerIconMarkerProvider;
import dev.cerus.explorersmap.map.CustomWorldMapTracker;
import dev.cerus.explorersmap.map.MapPlayerAddedSystem;
import dev.cerus.explorersmap.map.MapSyncSystem;
import dev.cerus.explorersmap.map.WorldMapDiskCache;
import dev.cerus.explorersmap.storage.ExplorationStorage;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

public class ExplorersMapPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static ExplorersMapPlugin instance;
    private Field worldMapTrackerField;

    private final Config<ExplorersMapConfig> config;
    private WorldMapDiskCache worldMapDiskCache;

    public static ExplorersMapPlugin getInstance() {
        return instance;
    }

    public ExplorersMapPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        config = withConfig("ExplorersMap", ExplorersMapConfig.CODEC);
    }

    @Override
    protected void setup() {
        try {
            worldMapTrackerField = Player.class.getDeclaredField("worldMapTracker");
            worldMapTrackerField.setAccessible(true);
            CustomWorldMapTracker.validateServerApi();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Hytale world map API; expected server 0.7.0", e);
        }
        instance = this;
        config.get().validate();
        config.save();

        worldMapDiskCache = new WorldMapDiskCache(getDataDirectory().resolve("tiles"));

        getEntityStoreRegistry().registerSystem(new MapPlayerAddedSystem(this));
        getEntityStoreRegistry().registerSystem(new MapSyncSystem());

        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onPlayerAddToWorld);
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        getEventRegistry().registerGlobal(AddWorldEvent.class, this::onWorldAdd);
        getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onWorldRemove);

        getCommandRegistry().registerCommand(new ExplorersMapCommand("explorersmap", "Open Explorers Map settings"));

        getEntityStoreRegistry().registerSystem(new DelayedSystem<>(60) {
            @Override
            public void delayedTick(float v, int i, @Nonnull Store<EntityStore> store) {
                CompletableFuture.runAsync(() -> {
                    ExplorationStorage.saveAll(CustomWorldMapTracker.sanitizeWorldName(store.getExternalData().getWorld()));
                });
            }
        });
    }

    @Override
    protected void shutdown() {
        ExplorationStorage.unloadFromAll(ExplorationStorage.UUID_GLOBAL);
        LOGGER.atInfo().log("Explorers Map plugin has been shut down.");
    }

    private void onPlayerAddToWorld(AddPlayerToWorldEvent event) {
        Holder<EntityStore> holder = event.getHolder();
        Player player = holder.getComponent(Player.getComponentType());
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        if (player == null || playerRef == null) return;

        World world = event.getWorld();
        String sanitizedName = CustomWorldMapTracker.sanitizeWorldName(world);
        UUID playerUuid = playerRef.getUuid();

        // This event runs before the client is ready and before the player enters the store.
        // Only preload here; MapPlayerAddedSystem installs the tracker on the world thread.
        CompletableFuture.runAsync(() -> ExplorationStorage.load(sanitizedName, playerUuid))
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        LOGGER.atSevere().withCause(throwable).log("Could not load player exploration data");
                    }
                });
    }

    /** Called on the world thread when the player has actually entered the entity store. */
    public void initializePlayerMap(Player player, PlayerRef playerRef, World world) {
        WorldMapSettings worldMapSettings = world.getWorldMapManager().getWorldMapSettings();
        UpdateWorldMapSettings settingsPacket = worldMapSettings.getSettingsPacket();
        float minZoom = getConfig().get().getMinZoom();
        if (settingsPacket.minScale > minZoom && minZoom < settingsPacket.maxScale) {
            settingsPacket.minScale = Math.max(2, minZoom);
        }

        injectCustomTracker(player, playerRef);
        player.getWorldMapTracker().sendSettings(world);
    }

    private void injectCustomTracker(Player player, PlayerRef playerRef) {
        if (player.getWorldMapTracker().getClass() != WorldMapTracker.class) {
            if (player.getWorldMapTracker() instanceof CustomWorldMapTracker custom) {
                custom.reset();
                return;
            }
            LOGGER.atWarning().log("Failed to inject custom tracker due to mod incompatibility!");
            return;
        }

        WorldMapTracker original = player.getWorldMapTracker();
        CustomWorldMapTracker replacement = new CustomWorldMapTracker(player, playerRef);
        try {
            replacement.copyFrom(original);
            replacement.setViewRadiusOverride(original.getViewRadiusOverride());
            // Player has no tracker setter in 0.7.0. Replace its instance field on the world thread.
            worldMapTrackerField.set(player, replacement);
        } catch (IllegalAccessException | RuntimeException e) {
            replacement.unregisterEvents();
            LOGGER.atSevere().withCause(e).log("Failed to inject custom tracker");
            return;
        }
        original.unregisterEvents();
        replacement.reset();
        LOGGER.atInfo().log("Installed exploration map tracker for %s", playerRef.getUsername());
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID uuid = event.getPlayerRef().getUuid();
        CompletableFuture.runAsync(() -> ExplorationStorage.unloadFromAll(uuid));
    }

    private void onWorldAdd(AddWorldEvent event) {
        World world = event.getWorld();
        String sanitizedName = CustomWorldMapTracker.sanitizeWorldName(world);

        CompletableFuture.runAsync(() -> {
            ExplorationStorage.load(sanitizedName, ExplorationStorage.UUID_GLOBAL);
        });

        // Registry changes are safe on WorldAddEvent as it is a lifecycle event
        WorldMapManager worldMapManager = world.getWorldMapManager();
        WorldMapManager.MarkerProvider original = worldMapManager.getMarkerProviders()
                .getOrDefault("playerIcons", com.hypixel.hytale.server.core.universe.world.worldmap.markers.providers.OtherPlayersMarkerProvider.INSTANCE);
        worldMapManager.addMarkerProvider("playerIcons", new CustomPlayerIconMarkerProvider(original));
    }

    private void onWorldRemove(RemoveWorldEvent event) {
        String sanitizedName = CustomWorldMapTracker.sanitizeWorldName(event.getWorld());
        CompletableFuture.runAsync(() -> ExplorationStorage.unload(sanitizedName, ExplorationStorage.UUID_GLOBAL));
    }

    public Config<ExplorersMapConfig> getConfig() {
        return config;
    }

    public WorldMapDiskCache getWorldMapDiskCache() {
        return worldMapDiskCache;
    }
}
