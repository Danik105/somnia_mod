package com.somnium.mod.dream;

import com.somnium.mod.SomniumMod;
import com.somnium.mod.dimension.ModDimensions;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Слоты измерений снов. Каждый сеанс сна получает свой участок измерения
 * (x = slot * SPACING), поэтому две группы с одним и тем же сном не мешают друг другу.
 *
 * После сна участок помечен "грязным" (это сохраняется в мире) и в фоне, порциями,
 * возвращается к чистой плоской поверхности — без лаг-спайка при следующем входе.
 * Пока участок чистится, новые сны занимают другие слоты.
 */
public final class DreamSlots {

    public static final int SPACING = 4096;
    private static final int SCAN_BUDGET = 48_000;
    private static final int CHANGE_BUDGET = 6_000;
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

    private static final Map<RegistryKey<World>, Set<Integer>> ACTIVE = new HashMap<>();
    private static final ArrayDeque<CleanupJob> JOBS = new ArrayDeque<>();

    private DreamSlots() {}

    // ----- выделение слотов -----

    public static int allocate(ServerWorld world) {
        Set<Integer> active = ACTIVE.computeIfAbsent(world.getRegistryKey(), k -> new HashSet<>());
        SlotState state = state(world);
        for (int slot = 0; ; slot++) {
            if (!active.contains(slot) && !state.dirty.containsKey(slot)) {
                active.add(slot);
                return slot;
            }
        }
    }

    public static BlockPos origin(ServerWorld world, int slot, int y) {
        return new BlockPos(slot * SPACING, y, 0);
    }

    /** Высота поверхности плоского генератора измерения (верх последнего слоя). */
    public static int surfaceY(ServerWorld world) {
        if (world.getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator flat) {
            return world.getBottomY() + flat.getConfig().getLayerBlocks().size();
        }
        return world.getSeaLevel();
    }

    /** Запомнить, что в слоте построено (до начала постройки — на случай падения сервера). */
    public static void markDirty(ServerWorld world, int slot, BlockBox box) {
        SlotState state = state(world);
        state.dirty.put(slot, box);
        state.markDirty();
    }

    /** Сон в слоте закончился: убрать сущности сразу, блоки — в фоне. */
    public static void release(ServerWorld world, int slot, BlockBox areaBox) {
        Set<Integer> active = ACTIVE.get(world.getRegistryKey());
        if (active != null) active.remove(slot);
        discardEntities(world, areaBox);
        BlockBox dirty = state(world).dirty.get(slot);
        if (dirty != null) JOBS.add(new CleanupJob(world, slot, dirty));
    }

    /** После старта сервера: дочистить слоты, оставшиеся грязными с прошлого запуска. */
    public static void resumeCleanup(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            if (!ModDimensions.isSessionDimension(world.getRegistryKey())) continue;
            for (Map.Entry<Integer, BlockBox> entry : state(world).dirty.entrySet()) {
                JOBS.add(new CleanupJob(world, entry.getKey(), entry.getValue()));
            }
        }
        if (!JOBS.isEmpty()) {
            SomniumMod.LOGGER.info("[Somnium] Дочищаем {} участков снов с прошлого запуска", JOBS.size());
        }
    }

    public static void onServerStopped() {
        ACTIVE.clear();
        JOBS.clear();
    }

    public static void tick() {
        CleanupJob job = JOBS.peek();
        if (job == null) return;
        if (job.step()) JOBS.poll();
    }

    private static void discardEntities(ServerWorld world, BlockBox box) {
        Box area = new Box(box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX() + 1, box.getMaxY() + 1, box.getMaxZ() + 1);
        for (Entity entity : world.getEntitiesByClass(Entity.class, area, e -> !(e instanceof PlayerEntity))) {
            entity.discard();
        }
    }

    // ----- фоновая зачистка -----

    private static final class CleanupJob {
        private final ServerWorld world;
        private final int slot;
        private final BlockBox box;
        private final List<BlockState> layers;
        private final int bottomY;
        private final List<long[]> forced = new ArrayList<>();
        private int x, y, z;
        private boolean started;

        CleanupJob(ServerWorld world, int slot, BlockBox box) {
            this.world = world;
            this.slot = slot;
            this.box = box;
            this.bottomY = world.getBottomY();
            this.layers = world.getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator flat
                    ? flat.getConfig().getLayerBlocks() : List.of();
            this.x = box.getMinX();
            this.y = box.getMinY();
            this.z = box.getMinZ();
        }

        /** Одна порция работы; true — участок чист. */
        boolean step() {
            if (!started) {
                started = true;
                for (int cx = box.getMinX() >> 4; cx <= box.getMaxX() >> 4; cx++) {
                    for (int cz = box.getMinZ() >> 4; cz <= box.getMaxZ() >> 4; cz++) {
                        world.setChunkForced(cx, cz, true);
                        forced.add(new long[]{cx, cz});
                    }
                }
                return false;
            }
            int scanned = 0;
            int changed = 0;
            BlockPos.Mutable pos = new BlockPos.Mutable();
            while (scanned < SCAN_BUDGET && changed < CHANGE_BUDGET) {
                if (y > box.getMaxY()) {
                    finish();
                    return true;
                }
                pos.set(x, y, z);
                BlockState expected = expectedAt(y);
                if (world.getBlockState(pos) != expected) {
                    world.setBlockState(pos, expected, FLAGS);
                    changed++;
                }
                scanned++;
                if (++x > box.getMaxX()) {
                    x = box.getMinX();
                    if (++z > box.getMaxZ()) {
                        z = box.getMinZ();
                        y++;
                    }
                }
            }
            return false;
        }

        private BlockState expectedAt(int blockY) {
            int index = blockY - bottomY;
            return index >= 0 && index < layers.size() ? layers.get(index) : Blocks.AIR.getDefaultState();
        }

        private void finish() {
            discardEntities(world, box.expand(8));
            for (long[] chunk : forced) {
                world.setChunkForced((int) chunk[0], (int) chunk[1], false);
            }
            SlotState state = state(world);
            state.dirty.remove(slot);
            state.markDirty();
        }
    }

    // ----- сохранение грязных слотов в мире -----

    private static SlotState state(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(SlotState::fromNbt, SlotState::new, "somnium_dream_slots");
    }

    private static final class SlotState extends PersistentState {
        final Map<Integer, BlockBox> dirty = new HashMap<>();

        static SlotState fromNbt(NbtCompound nbt) {
            SlotState state = new SlotState();
            NbtList list = nbt.getList("Dirty", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i);
                int[] b = entry.getIntArray("Box");
                if (b.length == 6) {
                    state.dirty.put(entry.getInt("Slot"), new BlockBox(b[0], b[1], b[2], b[3], b[4], b[5]));
                }
            }
            return state;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            NbtList list = new NbtList();
            for (Map.Entry<Integer, BlockBox> entry : dirty.entrySet()) {
                BlockBox b = entry.getValue();
                NbtCompound e = new NbtCompound();
                e.putInt("Slot", entry.getKey());
                e.putIntArray("Box", new int[]{b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()});
                list.add(e);
            }
            nbt.put("Dirty", list);
            return nbt;
        }
    }
}
