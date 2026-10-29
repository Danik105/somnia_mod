package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamSession;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Сценарий одного сна. Экземпляр создаётся на каждую сессию, поэтому всё состояние
 * сна — обычные поля сценария. Тики:
 *  - {@link #tick} — раз в тик на всю сессию (вода, подача блюд, сдвиг стен);
 *  - {@link #tickPlayer} — раз в тик на каждого участника (цели, подсказки).
 * Будить игроков — только через DreamManager.requestWake / DreamSession.wakeAll.
 */
public abstract class DreamScenario {

    /** Флаги для массовой постройки: без обновлений соседей (быстро и без "осыпания"). */
    protected static final int BULK = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    /** Строит сцену и возвращает точку появления лидера (уровень ног). */
    public abstract BlockPos setup(DreamSession session, ServerPlayerEntity leader);

    /** Всё, что построил сценарий (вызывается после setup) — зачищается после сна. */
    public abstract BlockBox buildBounds(DreamSession session);

    /** Высота origin сессии; по умолчанию — поверхность плоского мира. */
    public int originY(ServerWorld world, int surfaceY) {
        return surfaceY;
    }

    /** Горизонтальный радиус игровой зоны: сущности в ней принадлежат сну. */
    public int areaRadius() {
        return 64;
    }

    /** Если > 0 — игрока, ушедшего дальше, возвращает назад (для открытых снов). */
    public int boundaryRadius() {
        return 0;
    }

    public boolean allowsJoining() {
        return true;
    }

    /** "Тихий" сон: без названия, HUD, эффектов и фоновой музыки (Сон-в-сне). */
    public boolean isSilent() {
        return false;
    }

    public BlockPos joinSpawn(DreamSession session, ServerPlayerEntity player) {
        BlockPos base = session.spawn();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = session.random.nextDouble() * Math.PI * 2;
            int dx = (int) Math.round(Math.cos(angle) * 2.5);
            int dz = (int) Math.round(Math.sin(angle) * 2.5);
            BlockPos pos = base.add(dx, 0, dz);
            ServerWorld world = session.world;
            if (world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                    && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()
                    && !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty()) {
                return pos;
            }
        }
        return base;
    }

    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {}

    public void applyEffects(ServerPlayerEntity player, int duration) {}

    public void tick(DreamSession session) {}

    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {}

    /** Игрок покидает сон (проснулся или вышел с сервера); сон для остальных продолжается. */
    public void onLeave(DreamSession session, ServerPlayerEntity player) {}

    /** Сон закончился для всех. Сущности и блоки слота удалит DreamSlots. */
    public void onEnd(DreamSession session) {}

    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        return ActionResult.PASS;
    }

    public ActionResult onUseItem(DreamSession session, ServerPlayerEntity player, Hand hand) {
        return ActionResult.PASS;
    }

    public ActionResult onUseEntity(DreamSession session, ServerPlayerEntity player, Hand hand, Entity entity) {
        return ActionResult.PASS;
    }

    public void onFinishUsing(DreamSession session, ServerPlayerEntity player, ItemStack stack) {}

    // ----- помощники -----

    protected static void set(ServerWorld world, BlockPos pos, BlockState state) {
        world.setBlockState(pos, state);
    }

    protected static void setBulk(ServerWorld world, BlockPos pos, BlockState state) {
        world.setBlockState(pos, state, BULK);
    }

    /** Вощёная табличка с текстом (до 4 строк): её нельзя редактировать. */
    protected static void sign(ServerWorld world, BlockPos pos, BlockState state, Text... lines) {
        world.setBlockState(pos, state, BULK);
        if (world.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < lines.length && i < 4; i++) text = text.withMessage(i, lines[i]);
            sign.setText(text, true);
            sign.setWaxed(true);
        }
    }

    /** Двухблочная дверь; lower — нижняя половина. */
    protected static void door(ServerWorld world, BlockPos lower, Block block, Direction facing, boolean open) {
        BlockState state = block.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.OPEN, open);
        world.setBlockState(lower, state.with(DoorBlock.HALF, DoubleBlockHalf.LOWER), BULK);
        world.setBlockState(lower.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), BULK);
    }

    /** Поворачивает голову и тело моба к точке (для мобов без ИИ). */
    protected static void faceTowards(Entity entity, Vec3d target) {
        double dx = target.x - entity.getX();
        double dy = target.y - entity.getEyeY();
        double dz = target.z - entity.getZ();
        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * MathHelper.DEGREES_PER_RADIAN);
        entity.setYaw(yaw);
        entity.setHeadYaw(yaw);
        entity.setPitch(pitch);
        if (entity instanceof LivingEntity living) living.setBodyYaw(yaw);
    }

    protected static MutableText tr(String key, Object... args) {
        return Text.translatable(key, args);
    }

    /** Расставляет монстров сна (из JSON) кольцом вокруг center. */
    protected static void spawnMonsters(DreamSession session, BlockPos center, int perType, double minR, double maxR) {
        for (Identifier id : session.type.monsters()) {
            for (int i = 0; i < perType; i++) {
                session.spawnMob(id, session.ringPos(center, minR, maxR), session.random.nextFloat() * 360f);
            }
        }
    }

    /** Коробка вокруг origin: dx/dz — радиус, y — от origin.y + yMin до origin.y + yMax. */
    protected static BlockBox around(BlockPos origin, int radius, int yMin, int yMax) {
        return new BlockBox(origin.getX() - radius, origin.getY() + yMin, origin.getZ() - radius,
                origin.getX() + radius, origin.getY() + yMax, origin.getZ() + radius);
    }
}
