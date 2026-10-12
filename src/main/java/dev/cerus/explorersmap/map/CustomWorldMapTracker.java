package dev.cerus.explorersmap.map;

import com.hypixel.hytale.common.fastutil.HLongOpenHashSet;
import com.hypixel.hytale.common.fastutil.HLongSet;
import com.hypixel.hytale.common.plugin.AuthorInfo;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.iterator.CircleSpiralIterator;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.packets.worldmap.ClearWorldMap;
import com.hypixel.hytale.protocol.packets.worldmap.MapChunk;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapSettings;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerTracker;
import dev.cerus.explorersmap.ExplorersMapPlugin;
import dev.cerus.explorersmap.config.ExplorersMapConfig;
import dev.cerus.explorersmap.storage.ExplorationData;
import dev.cerus.explorersmap.storage.ExplorationStorage;
import dev.cerus.explorersmap.storage.ExploredRegion;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * Custom map tracker implementation - Fixed for Thread Safety
 */
public class CustomWorldMapTracker extends WorldMapTracker {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Pattern INSTANCE_SUFFIX_PATTERN = Pattern.compile("-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Field MARKER_TRACKER_FIELD;
    private static final Field PLUGIN_FIELD;

    static {
        try {
            MARKER_TRACKER_FIELD = WorldMapTracker.class.getDeclaredField("markerTracker");
            MARKER_TRACKER_FIELD.setAccessible(true);

            PLUGIN_FIELD = PluginClassLoader.class.getDeclaredField("plugin");
            PLUGIN_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void validateServerApi() {
        // Initializing this class validates the private hooks before registering the plugin.
    }

    private final PlayerRef playerRef;
    private ExplorersMapConfig config;

    private final ReentrantReadWriteLock loadedLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock tickLock = new ReentrantReadWriteLock();
    private final CircleSpiralIterator spiralIterator = new CircleSpiralIterator();
    private final HLongSet loaded = new HLongOpenHashSet();
    private final HLongSet pendingReloadChunks = new HLongOpenHashSet();
    private final MapMarkerTracker mapMarkerTracker;

    private record MapSnapshot(World world, TransformComponent transform) {}

    private final AtomicReference<MapSnapshot> transformRef = new AtomicReference<>();

    private boolean started;
    private List<ExploredRegion> loadFromDisk;
    private ExplorationData explorationData;
    private Resolution currentResolution;

    public CustomWorldMapTracker(Player player, PlayerRef playerRef) {
        super(player);
        this.playerRef = playerRef;
        setPlayerUuid(playerRef.getUuid());
        this.config = ExplorersMapPlugin.getInstance().getConfig().get();
        this.currentResolution = config.getResolution();

        try {
            this.mapMarkerTracker = (MapMarkerTracker) MARKER_TRACKER_FIELD.get(this);
        } catch (IllegalAccessException e) {
            unregisterEvents();
            throw new IllegalStateException("Cannot access the server's map marker tracker", e);
        }
    }

    @Override
    public void copyFrom(WorldMapTracker original) {
        super.copyFrom(original);
        try {
            // The server's copyFrom only copies sent markers, not the visibility filter.
            MapMarkerTracker originalMarkers = (MapMarkerTracker) MARKER_TRACKER_FIELD.get(original);
            mapMarkerTracker.setPlayerMapFilter(originalMarkers.getPlayerMapFilter());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot preserve the original player marker filter", e);
        }
    }

    @Override
    public void tick(float dt) {
        tickLock.writeLock().lock();
        try {
            tick0(dt);
        } finally {
            tickLock.writeLock().unlock();
        }
    }

    private void tick0(float dt) {
        if (!this.started) {
            this.started = true;
            LOGGER.at(Level.INFO).log("Started Generating Map!");
        }

        World world = getPlayer().getWorld();
        if (world == null) {
            return;
        }

        if (!isWorldMapChannelWritable()) {
            return;
        }

        MapSnapshot snapshot = transformRef.get();
        if (snapshot == null || snapshot.world() != world) {
            return;
        }
        Vector3d position = snapshot.transform().getPosition();
        int playerX = MathUtil.floor(position.x());
        int playerZ = MathUtil.floor(position.z());
        int playerChunkX = playerX >> 5;
        int playerChunkZ = playerZ >> 5;

        WorldMapManager worldMapManager = world.getWorldMapManager();
        WorldMapSettings worldMapSettings = worldMapManager.getWorldMapSettings();
        int viewRadius;

        if (this.getViewRadiusOverride() != null) {
            viewRadius = this.getViewRadiusOverride();
        } else if (ExplorersMapPlugin.getInstance().getConfig().get().isUnlimitedMarkerTracking()) {
            viewRadius = 1000;
        } else {
            viewRadius = worldMapSettings.getViewRadius(getPlayer().getViewRadius());
        }

        // Load already explored tiles to send to the player
        if (loadFromDisk == null) {
            explorationData = ExplorationStorage.getOrLoad(sanitizeWorldName(world), playerRef.getUuid());
            if (explorationData != null) {
                ExplorationData dataToUse = config.isPerPlayerMap()
                        ? explorationData
                        : ExplorationStorage.getOrLoad(sanitizeWorldName(world), ExplorationStorage.UUID_GLOBAL);
                loadFromDisk = dataToUse.copyRegionsForSending(playerChunkX, playerChunkZ);
            }
        }

        if (world.isCompassUpdating() && mapMarkerTracker != null) {
            updatePointsOfInterest(worldMapManager, dt, world, viewRadius, playerChunkX, playerChunkZ);
        }

        if (worldMapManager.isWorldMapEnabled()) {
            tickWorldMap(world, worldMapSettings, playerChunkX, playerChunkZ, config.getGenerationRate());
        } else {
            // FIX: Even when live map generation is disabled, still send previously explored
            // tiles from disk so the player sees their explored areas.
            List<MapChunk> toSend = new ArrayList<>();
            reloadPending(world, worldMapSettings, config.getGenerationRate(), toSend);
            loadStored(world, worldMapSettings, config.getDiskLoadRate(), toSend);
            if (!toSend.isEmpty()) {
                writeUpdatePacket(toSend);
            }
        }
    }

    private void updatePointsOfInterest(WorldMapManager worldMapManager, float dt, World world, int viewRadius, int playerChunkX, int playerChunkZ) {
        try {
            mapMarkerTracker.updatePointsOfInterest(dt, world, viewRadius, playerChunkX, playerChunkZ);
        } catch (Throwable t) {
            LOGGER.atWarning().log("Failed to update markers! Are you using any marker mods?");
            t.printStackTrace(System.err);

            // Try to find out which one is causing issues
            CustomMarkersCollector markersCollector = new CustomMarkersCollector(mapMarkerTracker, viewRadius, playerChunkX, playerChunkZ);
            Set<Map.Entry<String, WorldMapManager.MarkerProvider>> providers = Set.copyOf(worldMapManager.getMarkerProviders().entrySet());
            for (Map.Entry<String, WorldMapManager.MarkerProvider> entry : providers) {
                WorldMapManager.MarkerProvider provider = entry.getValue();
                try {
                    provider.update(world, getPlayer(), markersCollector);
                } catch (Throwable tt) {
                    LOGGER.atWarning().log("Identified marker provider '%s' (%s) as erroneous! Removing this provider for now.",
                            entry.getKey(), provider.getClass().getName());
                    if (provider.getClass().getClassLoader() instanceof PluginClassLoader pluginClassLoader) {
                        try {
                            JavaPlugin cause = (JavaPlugin) PLUGIN_FIELD.get(pluginClassLoader);
                            PluginManifest manifest = cause.getManifest();
                            LOGGER.atWarning().log("Identified mod '%s' by %s as the cause. Consider removing this mod until this is fixed.",
                                    manifest.getName(), manifest.getAuthors().stream().map(AuthorInfo::getName).collect(Collectors.joining(", ")));
                        } catch (IllegalAccessException ignored) {
                        }
                    }

                    worldMapManager.getMarkerProviders().remove(entry.getKey());
                }
            }
        }
    }

    private void tickWorldMap(World world, WorldMapSettings worldMapSettings, int playerChunkX, int playerChunkZ, int maxGeneration) {
        List<MapChunk> toSend = new ArrayList<>();

        maxGeneration = loadArea(world, worldMapSettings, playerChunkX, playerChunkZ, maxGeneration, toSend);

        if (!toSend.isEmpty()) {
            if (shouldPersist(world)) {
                // Mark loaded area as explored
                toSend.forEach(chunk -> {
                    if (explorationData != null) {
                        explorationData.markExplored(chunk);
                    }
                    ExplorationStorage.getOrLoad(sanitizeWorldName(world), ExplorationStorage.UUID_GLOBAL).markExplored(chunk);
                });
            }

            if (!config.isPerPlayerMap()) {
                // Broadcast to other players
                List<MapChunk> chunksCopy = List.copyOf(toSend);
                world.execute(() -> {
                    for (PlayerRef otherPlayerRef : world.getPlayerRefs()) {
                        Ref<EntityStore> reference = otherPlayerRef.getReference();
                        if (otherPlayerRef.getUuid().equals(playerRef.getUuid()) || reference == null || !reference.isValid()
                                || reference.getStore() != world.getEntityStore().getStore()) {
                            continue;
                        }
                        Player player = reference.getStore().getComponent(reference, Player.getComponentType());
                        if (player != null && player.getWorldMapTracker() instanceof CustomWorldMapTracker tracker) {
                            tracker.writeUpdatePacket(chunksCopy);
                        }
                    }
                });
            }
        }

        // Reload pending chunks (from building tools or mods)
        maxGeneration = reloadPending(world, worldMapSettings, maxGeneration, toSend);

        // Send pending already explored tiles
        loadStored(world, worldMapSettings, config.getDiskLoadRate(), toSend);

        if (!toSend.isEmpty()) {
            writeUpdatePacket(toSend);
        }
    }

    private int loadArea(World world, WorldMapSettings worldMapSettings, int playerChunkX, int playerChunkZ, int maxGeneration, List<MapChunk> out) {
        if (config.getExplorationRadius() <= 0) {
            return maxGeneration;
        }

        loadedLock.writeLock().lock();
        try {
            this.spiralIterator.init(playerChunkX, playerChunkZ, 0, config.getExplorationRadius());
            while (maxGeneration > 0 && this.spiralIterator.hasNext()) {
                long chunkCoordinates = this.spiralIterator.next();
                if (!this.loaded.contains(chunkCoordinates)) {
                    CompletableFuture<MapImage> future = world.getWorldMapManager().getImageAsync(chunkCoordinates);
                    if (!future.isDone()) {
                        --maxGeneration;
                    } else if (loaded.add(chunkCoordinates)) {
                        int mapChunkX = ChunkUtil.xOfChunkIndex(chunkCoordinates);
                        int mapChunkZ = ChunkUtil.zOfChunkIndex(chunkCoordinates);

                        MapImage mapImage = future.getNow(null);
                        if (mapImage == null) {
                            loaded.remove(chunkCoordinates);
                            continue;
                        }

                        if (shouldPersist(world)) {
                            ExplorersMapPlugin.getInstance().getWorldMapDiskCache().saveImageToDiskAsync(world, mapChunkX, mapChunkZ, worldMapSettings.getImageScale(), currentResolution, mapImage).whenComplete((unused, throwable) -> {
                                if (throwable != null) {
                                    LOGGER.atSevere().log("Failed to save map tile", throwable);
                                }
                            });
                        }

                        mapImage = currentResolution.rescale(mapImage);
                        out.add(new MapChunk(mapChunkX, mapChunkZ, mapImage));
                    }
                }
            }
            return maxGeneration;
        } finally {
            loadedLock.writeLock().unlock();
        }
    }

    private int loadStored(World world, WorldMapSettings worldMapSettings, int maxGeneration, List<MapChunk> out) {
        if (loadFromDisk == null) {
            return maxGeneration;
        }

        loadedLock.writeLock().lock();
        try {
            Iterator<ExploredRegion> regionIterator = loadFromDisk.iterator();
            while (maxGeneration > 0 && regionIterator.hasNext()) {
                ExploredRegion region = regionIterator.next();
                Iterator<Long> iterator = region.getChunks().iterator();
                while (maxGeneration > 0 && iterator.hasNext()) {
                    long chunkCoordinates = iterator.next();
                    int mapChunkX = ChunkUtil.xOfChunkIndex(chunkCoordinates);
                    int mapChunkZ = ChunkUtil.zOfChunkIndex(chunkCoordinates);

                    if (!this.loaded.contains(chunkCoordinates)) {
                        CompletableFuture<MapImage> future = ExplorersMapPlugin.getInstance().getWorldMapDiskCache().readStoredImageAsync(world, mapChunkX, mapChunkZ, worldMapSettings.getImageScale(), currentResolution);
                        if (!future.isDone()) {
                            --maxGeneration;
                        } else if (loaded.add(chunkCoordinates)) {
                            iterator.remove();
                            MapImage mapImage = future.getNow(null);
                            int imageSize = MathUtil.fastFloor(32.0F * currentResolution.getScale());
                            if (mapImage == null || mapImage.width != imageSize || mapImage.height != imageSize) {
                                loaded.remove(chunkCoordinates);
                                continue;
                            }
                            out.add(new MapChunk(mapChunkX, mapChunkZ, mapImage));
                        }
                    } else {
                        iterator.remove();
                    }
                }

                if (region.isDone()) {
                    regionIterator.remove();
                }
            }
            return maxGeneration;
        } finally {
            loadedLock.writeLock().unlock();
        }
    }

    private int reloadPending(World world, WorldMapSettings worldMapSettings, int maxGeneration, List<MapChunk> out) {
        loadedLock.writeLock().lock();
        try {
            LongIterator iterator = pendingReloadChunks.iterator();
            while (maxGeneration > 0 && iterator.hasNext()) {
                long chunkCoordinates = iterator.nextLong();

                if (!this.loaded.contains(chunkCoordinates)) {
                    CompletableFuture<MapImage> future = world.getWorldMapManager().getImageAsync(chunkCoordinates);
                    if (!future.isDone()) {
                        --maxGeneration;
                    } else if (loaded.add(chunkCoordinates)) {
                        int mapChunkX = ChunkUtil.xOfChunkIndex(chunkCoordinates);
                        int mapChunkZ = ChunkUtil.zOfChunkIndex(chunkCoordinates);

                        MapImage mapImage = future.getNow(null);
                        if (mapImage == null) {
                            loaded.remove(chunkCoordinates);
                            continue;
                        }

                        iterator.remove();
                        if (shouldPersist(world)) {
                            ExplorersMapPlugin.getInstance().getWorldMapDiskCache().saveImageToDiskAsync(world, mapChunkX, mapChunkZ, worldMapSettings.getImageScale(), currentResolution, mapImage).whenComplete((unused, throwable) -> {
                                if (throwable != null) {
                                    LOGGER.atSevere().log("Failed to save map tile", throwable);
                                }
                            });
                        }

                        mapImage = currentResolution.rescale(mapImage);
                        out.add(new MapChunk(mapChunkX, mapChunkZ, mapImage));
                    }
                } else {
                    iterator.remove();
                }
            }
            return maxGeneration;
        } finally {
            loadedLock.writeLock().unlock();
        }
    }

    @Override
    public void clearChunks(@Nonnull LongSet chunkIndices) {
        this.tickLock.writeLock().lock();
        this.loadedLock.writeLock().lock();
        try {
            chunkIndices.forEach((index) -> {
                if (!loaded.contains(index) && (loadFromDisk == null || loadFromDisk.stream().noneMatch(reg -> reg.getChunks().contains(index)))) {
                    return;
                }
                this.loaded.remove(index);
                this.pendingReloadChunks.add(index);
            });
        } finally {
            this.loadedLock.writeLock().unlock();
            this.tickLock.writeLock().unlock();
        }
    }

    private void writeUpdatePacket(List<MapChunk> list) {
        UpdateWorldMap packet = new UpdateWorldMap(list.toArray(MapChunk[]::new), null, null);
        playerRef.getPacketHandler().write(packet);
    }

    @Override
    public void clear() {
        reset(true);
    }

    public void reset() {
        reset(true);
    }

    public void reset(boolean unload) {
        // Keep the same lock order as tick(), including resets from the world thread.
        tickLock.writeLock().lock();
        loadedLock.writeLock().lock();

        try {
            if (unload) {
                playerRef.getPacketHandler().write(new ClearWorldMap());
            }

            explorationData = null;
            loaded.clear();
            pendingReloadChunks.clear();
            mapMarkerTracker.getSentMarkers().clear();
            transformRef.set(null);
            loadFromDisk = null;
            config = ExplorersMapPlugin.getInstance().getConfig().get();
            currentResolution = config.getResolution();
        } finally {
            loadedLock.writeLock().unlock();
            tickLock.writeLock().unlock();
        }
    }

    /** Publishes an independent snapshot; ECS components remain owned by the world thread. */
    public void pushTransform(World world, TransformComponent component) {
        transformRef.set(new MapSnapshot(world, component.clone()));
    }

    @Override
    public TransformComponent getTransformComponent() {
        MapSnapshot snapshot = transformRef.get();
        return snapshot == null ? null : snapshot.transform();
    }

    public boolean hasTransform() {
        return transformRef.get() != null;
    }

    private boolean shouldPersist(World world) {
        return !world.getName().startsWith("instance-") || config.isSaveInstanceTiles();
    }

    public boolean isLoaded(int chunkX, int chunkZ) {
        loadedLock.readLock().lock();
        try {
            return loaded.contains(ChunkUtil.indexChunk(chunkX, chunkZ));
        } finally {
            loadedLock.readLock().unlock();
        }
    }

    public static String sanitizeWorldName(World world) {
        String name = world.getName();
        if (name.startsWith("instance-")) {
            return INSTANCE_SUFFIX_PATTERN.matcher(name).replaceFirst("");
        }
        return name;
    }

    private boolean isWorldMapChannelWritable() {
        ChannelConnection channel = playerRef.getPacketHandler().getChannel(NetworkChannel.WorldMap);
        return channel != null && channel.isWritable();
    }
}
