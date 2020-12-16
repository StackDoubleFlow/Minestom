package net.minestom.server.thread;

import io.netty.util.NettyRuntime;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.*;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.SharedInstance;
import net.minestom.server.lock.AcquirableElement;
import net.minestom.server.utils.callback.validator.EntityValidator;
import net.minestom.server.utils.chunk.ChunkUtils;
import net.minestom.server.utils.thread.MinestomThread;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Used to link chunks into multiple groups.
 * Then executed into a thread pool.
 * <p>
 * You can change the current thread provider by calling {@link net.minestom.server.UpdateManager#setThreadProvider(ThreadProvider)}.
 */
public abstract class ThreadProvider {

    /**
     * The thread pool of this thread provider.
     */
    protected ExecutorService pool;
    /**
     * The amount of threads in the thread pool
     */
    private int threadCount;

    {
        // Default thread count in the pool (cores * 2)
        setThreadCount(NettyRuntime.availableProcessors() * 2);
    }

    /**
     * Called when an {@link Instance} is registered.
     *
     * @param instance the newly create {@link Instance}
     */
    public abstract void onInstanceCreate(@NotNull Instance instance);

    /**
     * Called when an {@link Instance} is unregistered.
     *
     * @param instance the deleted {@link Instance}
     */
    public abstract void onInstanceDelete(@NotNull Instance instance);

    /**
     * Called when a chunk is loaded.
     * <p>
     * Be aware that this is possible for an instance to load chunks before being registered.
     *
     * @param instance the instance of the chunk
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     */
    public abstract void onChunkLoad(@NotNull Instance instance, int chunkX, int chunkZ);

    /**
     * Called when a chunk is unloaded.
     *
     * @param instance the instance of the chunk
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     */
    public abstract void onChunkUnload(@NotNull Instance instance, int chunkX, int chunkZ);

    /**
     * Performs a server tick for all chunks based on their linked thread.
     *
     * @param time the update time in milliseconds
     */
    public abstract void update(long time);

    /**
     * Gets the current size of the thread pool.
     *
     * @return the thread pool's size
     */
    public int getThreadCount() {
        return threadCount;
    }

    /**
     * Changes the amount of threads in the thread pool.
     *
     * @param threadCount the new amount of threads
     */
    public synchronized void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
        refreshPool();
    }

    public ExecutorService getPool() {
        return pool;
    }

    public void execute(@NotNull Runnable runnable) {
        this.pool.execute(() -> {
            runnable.run();
            AcquirableElement.Handler.reset();
        });
    }

    private void refreshPool() {
        if (pool != null) {
            this.pool.shutdown();
        }
        this.pool = new MinestomThread(threadCount, MinecraftServer.THREAD_NAME_TICK);
    }

    // INSTANCE UPDATE

    /**
     * Processes a whole tick for a chunk.
     *
     * @param instance   the instance of the chunk
     * @param chunkIndex the index of the chunk {@link ChunkUtils#getChunkIndex(int, int)}
     * @param time       the time of the update in milliseconds
     */
    protected void processChunkTick(@NotNull Instance instance, long chunkIndex, long time) {
        final int chunkX = ChunkUtils.getChunkCoordX(chunkIndex);
        final int chunkZ = ChunkUtils.getChunkCoordZ(chunkIndex);

        final Chunk chunk = instance.getChunk(chunkX, chunkZ);
        processChunkTick(instance, chunk, time);
    }

    protected void processChunkTick(@NotNull Instance instance, @Nullable Chunk chunk, long time) {
        if (!ChunkUtils.isLoaded(chunk))
            return;

        updateChunk(instance, chunk, time);

        updateEntities(instance, chunk, time);
    }

    /**
     * Executes an instance tick.
     *
     * @param instance the instance
     * @param time     the current time in ms
     */
    protected void updateInstance(@NotNull Instance instance, long time) {
        // The instance
        instance.tick(time);
    }

    /**
     * Executes a chunk tick (blocks update).
     *
     * @param instance the chunk's instance
     * @param chunk    the chunk
     * @param time     the current time in ms
     */
    protected void updateChunk(@NotNull Instance instance, @NotNull Chunk chunk, long time) {
        chunk.tick(time, instance);
    }

    // ENTITY UPDATE

    /**
     * Executes an entity tick (all entities type creatures/objects/players) in an instance's chunk.
     *
     * @param instance the chunk's instance
     * @param chunk    the chunk
     * @param time     the current time in ms
     */
    protected void updateEntities(@NotNull Instance instance, @NotNull Chunk chunk, long time) {
        conditionalEntityUpdate(instance, chunk, time, null);
    }

    /**
     * Executes an entity tick in an instance's chunk if condition is verified.
     *
     * @param instance  the chunk's instance
     * @param chunk     the chunk
     * @param time      the current time in ms
     * @param condition the condition which confirm if the update happens or not
     */
    protected void conditionalEntityUpdate(@NotNull Instance instance, @NotNull Chunk chunk, long time,
                                           @Nullable EntityValidator condition) {
        final Set<Entity> entities = instance.getChunkEntities(chunk);

        if (!entities.isEmpty()) {

            // REFRESH HANDLER
            // TODO
            for (Entity entity : entities) {
                if (shouldTick(entity, condition) && entity instanceof Player) {
                    ((Player) entity).getAcquiredElement().getHandler().startTick();
                }
            }

            for (Entity entity : entities) {
                if (shouldTick(entity, condition))
                    entity.tick(time);
            }

            // REFRESH HANDLER
            // TODO
            for (Entity entity : entities) {
                if (shouldTick(entity, condition) && entity instanceof Player) {
                    ((Player) entity).getAcquiredElement().getHandler().endTick();
                }
            }
        }

        updateSharedInstances(instance, sharedInstance -> conditionalEntityUpdate(sharedInstance, chunk, time, condition));
    }

    private static boolean shouldTick(@NotNull Entity entity, @Nullable EntityValidator condition) {
        return condition == null || condition.isValid(entity);
    }

    /**
     * If {@code instance} is an {@link InstanceContainer}, run a callback for all of its
     * {@link SharedInstance}.
     *
     * @param instance the instance
     * @param callback the callback to run for all the {@link SharedInstance}
     */
    private void updateSharedInstances(@NotNull Instance instance, @NotNull Consumer<SharedInstance> callback) {
        if (instance instanceof InstanceContainer) {
            final InstanceContainer instanceContainer = (InstanceContainer) instance;

            if (!instanceContainer.hasSharedInstances())
                return;

            for (SharedInstance sharedInstance : instanceContainer.getSharedInstances()) {
                callback.accept(sharedInstance);
            }
        }
    }

}
