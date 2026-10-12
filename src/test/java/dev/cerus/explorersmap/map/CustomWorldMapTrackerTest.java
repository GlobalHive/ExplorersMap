package dev.cerus.explorersmap.map;

import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.packets.worldmap.ClearWorldMap;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.MapChunk;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapSettings;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.ArrayList;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.hypixel.hytale.server.core.util.Config;
import dev.cerus.explorersmap.ExplorersMapPlugin;
import dev.cerus.explorersmap.config.ExplorersMapConfig;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomWorldMapTrackerTest {
    private MockedStatic<HytaleServer> serverStatic;
    private MockedStatic<ExplorersMapPlugin> pluginStatic;
    private ExplorersMapPlugin plugin;
    private Player player;
    private PlayerRef playerRef;
    private PacketHandler packets;
    private CustomWorldMapTracker tracker;

    @BeforeAll
    static void initializeServerOptions() throws Exception {
        Options.parse(new String[]{"--universe", "target/test-universe"});
    }

    @BeforeEach
    void setUp() {
        HytaleServer server = mock(HytaleServer.class);
        when(server.getEventBus()).thenReturn(new EventBus(false));
        serverStatic = mockStatic(HytaleServer.class);
        serverStatic.when(HytaleServer::get).thenReturn(server);
        plugin = mock(ExplorersMapPlugin.class);
        @SuppressWarnings("unchecked")
        Config<ExplorersMapConfig> config = mock(Config.class);
        when(config.get()).thenReturn(new ExplorersMapConfig());
        when(plugin.getConfig()).thenReturn(config);
        pluginStatic = mockStatic(ExplorersMapPlugin.class);
        pluginStatic.when(ExplorersMapPlugin::getInstance).thenReturn(plugin);
        player = mock(Player.class);
        playerRef = mock(PlayerRef.class);
        packets = mock(PacketHandler.class);
        when(playerRef.getUuid()).thenReturn(UUID.randomUUID());
        when(playerRef.getPacketHandler()).thenReturn(packets);
        tracker = new CustomWorldMapTracker(player, playerRef);
    }

    @AfterEach
    void tearDown() {
        if (tracker != null) tracker.unregisterEvents();
        if (pluginStatic != null) pluginStatic.close();
        if (serverStatic != null) serverStatic.close();
    }

    @Test
    void publishedTransformDoesNotChangeWhenEcsComponentMoves() {
        TransformComponent live = new TransformComponent(new Vector3d(32, 64, -96), new Rotation3f());
        tracker.pushTransform(null, live);
        live.getPosition().set(999, 999, 999);
        assertEquals(new Vector3d(32, 64, -96), tracker.getTransformComponent().getPosition());
        assertNotSame(live, tracker.getTransformComponent());
    }

    @Test
    void resetClearsTilesPendingReloadsMarkersAndPosition() throws Exception {
        LongSet loaded = (LongSet) field(CustomWorldMapTracker.class, "loaded").get(tracker);
        loaded.add(ChunkUtil.indexChunk(0, 1));
        assertTrue(tracker.isLoaded(0, 1));
        LongSet pending = (LongSet) field(CustomWorldMapTracker.class, "pendingReloadChunks").get(tracker);
        pending.add(2);
        tracker.getSentMarkers().put("old-world", new MapMarker());
        tracker.pushTransform(null, new TransformComponent());
        tracker.reset();
        assertFalse(tracker.isLoaded(0, 1));
        assertTrue(pending.isEmpty());
        assertTrue(tracker.getSentMarkers().isEmpty());
        assertFalse(tracker.hasTransform());
        verify(packets).write(any(ClearWorldMap.class));
    }

    @Test
    void injectionReplacesFinalFieldAndUnregistersOldListeners() throws Exception {
        WorldMapTracker original = new WorldMapTracker(player);
        field(WorldMapTracker.class, "viewRadiusOverride").set(original, 42);
        Predicate<PlayerRef> filter = ignored -> true;
        original.setPlayerMapFilter(filter);
        Field playerTracker = field(Player.class, "worldMapTracker");
        playerTracker.set(player, original);
        doCallRealMethod().when(player).getWorldMapTracker();
        field(ExplorersMapPlugin.class, "worldMapTrackerField").set(plugin, playerTracker);
        Method inject = ExplorersMapPlugin.class.getDeclaredMethod("injectCustomTracker", Player.class, PlayerRef.class);
        inject.setAccessible(true);
        inject.invoke(plugin, player, playerRef);
        CustomWorldMapTracker replacement = assertInstanceOf(CustomWorldMapTracker.class, player.getWorldMapTracker());
        try {
            assertTrue(((List<?>) field(WorldMapTracker.class, "eventRegistrations").get(original)).isEmpty());
            assertEquals(playerRef.getUuid(), field(WorldMapTracker.class, "playerUuid").get(replacement));
            assertEquals(42, replacement.getViewRadiusOverride());
            var markers = (com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerTracker)
                    field(WorldMapTracker.class, "markerTracker").get(replacement);
            assertSame(filter, markers.getPlayerMapFilter());
        } finally {
            replacement.unregisterEvents();
            original.unregisterEvents();
        }
    }

    @Test
    void tickSkipsMissingOrBackpressuredMapChannel() {
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        tracker.pushTransform(world, new TransformComponent());
        assertDoesNotThrow(() -> tracker.tick(0.05f));
        ChannelConnection channel = mock(ChannelConnection.class);
        when(packets.getChannel(NetworkChannel.WorldMap)).thenReturn(channel);
        when(channel.isWritable()).thenReturn(false);
        assertDoesNotThrow(() -> tracker.tick(0.05f));
        verify(world, never()).getWorldMapManager();
    }

    @Test
    void tickDoesNotExploreNewWorldUsingPreviousWorldPosition() {
        World previous = mock(World.class);
        World current = mock(World.class);
        ChannelConnection channel = mock(ChannelConnection.class);
        when(packets.getChannel(NetworkChannel.WorldMap)).thenReturn(channel);
        when(channel.isWritable()).thenReturn(true);
        when(player.getWorld()).thenReturn(current);
        tracker.pushTransform(previous, new TransformComponent());
        tracker.tick(0.05f);
        verify(current, never()).getWorldMapManager();
    }

    @Test
    void resetDoesNotHoldTileLockWhileWaitingForMapTick() throws Exception {
        ReentrantReadWriteLock tickLock = (ReentrantReadWriteLock) field(CustomWorldMapTracker.class, "tickLock").get(tracker);
        ReentrantReadWriteLock loadedLock = (ReentrantReadWriteLock) field(CustomWorldMapTracker.class, "loadedLock").get(tracker);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Thread reset = Thread.ofPlatform().daemon().unstarted(() -> {
            try (MockedStatic<ExplorersMapPlugin> scoped = mockStatic(ExplorersMapPlugin.class)) {
                scoped.when(ExplorersMapPlugin::getInstance).thenReturn(plugin);
                tracker.reset(false);
                finished.complete(null);
            } catch (Throwable e) {
                finished.completeExceptionally(e);
            }
        });
        tickLock.writeLock().lock();
        try {
            reset.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!tickLock.hasQueuedThread(reset) && System.nanoTime() < deadline && !finished.isDone()) {
                Thread.sleep(1);
            }
            assertTrue(tickLock.hasQueuedThread(reset), "Reset should wait for the active map tick");
            boolean acquired = loadedLock.writeLock().tryLock();
            try {
                assertTrue(acquired, "Reset must not prevent the active map tick from finishing tile work");
            } finally {
                if (acquired) loadedLock.writeLock().unlock();
            }
        } finally {
            tickLock.writeLock().unlock();
        }
        finished.get(5, TimeUnit.SECONDS);
    }

    @Test
    void unavailableReloadImageRemainsPendingUntilItCanBeSent() throws Exception {
        World world = mock(World.class);
        when(world.getName()).thenReturn("instance-test");
        WorldMapManager manager = mock(WorldMapManager.class);
        when(world.getWorldMapManager()).thenReturn(manager);
        long chunk = ChunkUtil.indexChunk(2, 3);
        LongSet pending = (LongSet) field(CustomWorldMapTracker.class, "pendingReloadChunks").get(tracker);
        pending.add(chunk);
        when(manager.getImageAsync(chunk)).thenReturn(CompletableFuture.completedFuture(null));
        Method reload = CustomWorldMapTracker.class.getDeclaredMethod("reloadPending", World.class, WorldMapSettings.class, int.class, List.class);
        reload.setAccessible(true);
        List<MapChunk> output = new ArrayList<>();
        reload.invoke(tracker, world, mock(WorldMapSettings.class), 20, output);
        assertTrue(output.isEmpty());
        assertTrue(pending.contains(chunk));
        assertFalse(tracker.isLoaded(2, 3));

        MapImage image = new MapImage(1, 1, new int[]{0x336699ff}, (byte) 4, new byte[]{0});
        when(manager.getImageAsync(chunk)).thenReturn(CompletableFuture.completedFuture(image));
        reload.invoke(tracker, world, mock(WorldMapSettings.class), 20, output);
        assertEquals(1, output.size());
        assertTrue(pending.isEmpty());
        assertTrue(tracker.isLoaded(2, 3));
        verify(plugin, never()).getWorldMapDiskCache();
    }

    @Test
    void unavailableGeneratedImageIsNotCachedOrMarkedLoaded() throws Exception {
        World world = mock(World.class);
        WorldMapManager manager = mock(WorldMapManager.class);
        when(world.getWorldMapManager()).thenReturn(manager);
        when(manager.getImageAsync(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        Method load = CustomWorldMapTracker.class.getDeclaredMethod("loadArea", World.class, WorldMapSettings.class, int.class, int.class, int.class, List.class);
        load.setAccessible(true);
        List<MapChunk> output = new ArrayList<>();
        load.invoke(tracker, world, mock(WorldMapSettings.class), 0, 0, 20, output);
        assertTrue(output.isEmpty());
        assertTrue(((LongSet) field(CustomWorldMapTracker.class, "loaded").get(tracker)).isEmpty());
        verify(plugin, never()).getWorldMapDiskCache();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
