package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.FleshGolemEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallBannerBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Кровавый пир ("Последний ужин"): судьи подают 5 блюд. Свежее — съешь, порченое
 * (зелёное имя, дым) — сожги Зажигалкой судей (блюдо в одной руке, зажигалка в другой, ПКМ).
 * Проигнорированное блюдо через 30 секунд убирают обиженные судьи. После пира каждому
 * подают Кубок Тоста — выпивший просыпается. Мясной Голем дремлет у стола с начала пира
 * и просыпается, чтобы охранять кубки.
 *
 * Исправлено: раньше "свипер мобов" удалял голема-охранника через полсекунды после
 * появления, а инструкции и звуки получал только лидер группы.
 */
public final class CrimsonFeastScenario extends DreamScenario {

    private static final int COURSES = 5;
    private static final int FIRST_DISH_DELAY = 100;
    private static final int DISH_INTERVAL = 160;
    private static final int DISH_LIFETIME = 600;
    private static final int BURN_TICKS = 30;
    private static final int JUDGE_FOCUS_TICKS = 60;
    private static final double TAINTED_CHANCE = 0.45;

    private static final float EAT_FRESH_SANITY = 3.0f;
    private static final float EAT_TAINTED_SANITY = -6.0f;
    private static final float BURN_TAINTED_SANITY = 2.0f;
    private static final float BURN_FRESH_SANITY = -2.0f;
    private static final float IGNORED_SANITY = -2.0f;

    private static final String LIGHTER_TAG = "somnium_feast_lighter";
    private static final String TOAST_TAG = "somnium_feast_toast";
    private static final String DISH_TAG = "somnium_feast_dish";
    private static final String TAINTED_TAG = "somnium_feast_tainted";

    private static final Item[] FRESH_MENU = {Items.GOLDEN_CARROT, Items.COOKED_BEEF, Items.BAKED_POTATO, Items.BREAD, Items.APPLE};
    private static final Item[] TAINTED_MENU = {Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO};

    private enum Reaction { APPROVE, ANGRY, LAUGH, CELEBRATE }

    private BlockPos plate;
    private final List<MobEntity> judges = new ArrayList<>();
    @Nullable private FleshGolemEntity golem;

    @Nullable private ItemEntity dish;
    private boolean courseOpen;
    private boolean dishTainted;
    private long dishDeadline;
    private long nextDish;
    private int coursesDone;
    private boolean gobletsServed;
    private final Map<ItemEntity, Long> burning = new HashMap<>();
    @Nullable private BlockPos judgeFocus;
    private long judgeFocusUntil;
    private long nextGuestSound;
    private final Set<UUID> lighterExplained = new HashSet<>();

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        BlockPos center = session.origin;
        buildHall(session, center);
        plate = center.add(0, 0, -3);
        long now = session.now();
        nextDish = now + FIRST_DISH_DELAY;
        nextGuestSound = now + 300 + session.random.nextInt(200);

        FleshGolemEntity guard = ModEntities.FLESH_GOLEM.create(session.world);
        if (guard != null) {
            guard.refreshPositionAndAngles(plate.getX() + 2.5, plate.getY() + 1.0, plate.getZ() + 2.5, 180f, 0f);
            guard.setPersistent();
            guard.setDormant(true);
            golem = session.add(guard);
        }
        return center.add(0, 0, 2);
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(session.origin, 9, -1, 7);
    }

    @Override
    public int areaRadius() {
        return 24;
    }

    @Override
    public void applyEffects(ServerPlayerEntity player, int duration) {
        DreamFx.effect(player, StatusEffects.HUNGER, duration, 1);
        DreamFx.effect(player, StatusEffects.WEAKNESS, duration, 0);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        giveLighter(player);
    }

    // ----- тик -----

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        tickBurning(world, now);
        tickJudges(session, now);

        if (courseOpen) {
            tickCourse(session, now);
            return;
        }
        if (coursesDone >= COURSES) {
            if (!gobletsServed) {
                serveGoblets(session);
            } else if (now % 20 == 0) {
                for (ItemEntity goblet : world.getEntitiesByClass(ItemEntity.class, new Box(plate).expand(12, 4, 12),
                        e -> isToast(e.getStack()))) {
                    world.spawnParticles(ParticleTypes.END_ROD, goblet.getX(), goblet.getY() + 0.4, goblet.getZ(),
                            2, 0.15, 0.1, 0.15, 0.01);
                }
            }
            return;
        }
        if (now >= nextDish) serveDish(session, now);
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        long now = session.now();
        if (now % 40 == 0 && !hasItem(player, CrimsonFeastScenario::isLighter)) giveLighter(player);
        if (now % 10 == 0 && (isTainted(player.getMainHandStack()) || isTainted(player.getOffHandStack()))) {
            session.world.spawnParticles(ParticleTypes.WITCH, player.getX(), player.getY() + 1.4, player.getZ(),
                    2, 0.25, 0.1, 0.25, 0.01);
        }
        if (gobletsServed && now % 200 == 0 && !hasItem(player, CrimsonFeastScenario::isToast)) {
            player.sendMessage(tr("somnium.hint.feast.goblet_waiting").formatted(Formatting.GRAY), true);
        }
    }

    private void tickCourse(DreamSession session, long now) {
        ServerWorld world = session.world;
        if (dish != null && dish.isRemoved()) {
            dish = null; // кто-то поднял блюдо — курс ждёт, пока его съедят или сожгут
            session.actionBar(tr("somnium.hint.feast.dish_taken").formatted(Formatting.GRAY));
        }
        if (now % 10 == 0) {
            if (dish != null && dishTainted) {
                world.spawnParticles(ParticleTypes.WITCH, dish.getX(), dish.getY() + 0.4, dish.getZ(), 3, 0.15, 0.1, 0.15, 0.01);
            }
            for (ItemEntity thrown : world.getEntitiesByClass(ItemEntity.class, new Box(plate).expand(12, 4, 12),
                    e -> isTainted(e.getStack()) && e != dish)) {
                world.spawnParticles(ParticleTypes.WITCH, thrown.getX(), thrown.getY() + 0.4, thrown.getZ(), 2, 0.15, 0.1, 0.15, 0.01);
            }
        }
        if (now >= dishDeadline) {
            removeOpenDish(session);
            finishCourse(now);
            for (ServerPlayerEntity player : session.players()) SanityManager.add(player, IGNORED_SANITY);
            session.actionBar(tr("somnium.hint.feast.ignored").formatted(Formatting.GRAY));
            react(session, Reaction.ANGRY, null);
        }
    }

    private void tickJudges(DreamSession session, long now) {
        if (now % 5 == 0) {
            BlockPos focus = now < judgeFocusUntil ? judgeFocus : null;
            for (MobEntity judge : judges) {
                double tx, tz;
                if (focus != null) {
                    tx = focus.getX() + 0.5;
                    tz = focus.getZ() + 0.5;
                } else {
                    ServerPlayerEntity nearest = session.nearestPlayer(judge.getPos());
                    if (nearest == null) continue;
                    tx = nearest.getX();
                    tz = nearest.getZ();
                }
                float yaw = (float) (Math.atan2(-(tx - judge.getX()), tz - judge.getZ()) * 180.0 / Math.PI);
                judge.setYaw(yaw);
                judge.setBodyYaw(yaw);
                judge.setHeadYaw(yaw);
            }
        }
        if (now >= nextGuestSound) {
            nextGuestSound = now + 300 + session.random.nextInt(300);
            for (MobEntity judge : judges) {
                session.sound(judge.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_AMBIENT, SoundCategory.HOSTILE, 0.7f, 0.45f);
            }
        }
    }

    private void tickBurning(ServerWorld world, long now) {
        if (burning.isEmpty()) return;
        for (var entry : new ArrayList<>(burning.entrySet())) {
            ItemEntity item = entry.getKey();
            if (item.isRemoved()) {
                burning.remove(item);
                continue;
            }
            if (now % 3 == 0) {
                world.spawnParticles(ParticleTypes.FLAME, item.getX(), item.getY() + 0.3, item.getZ(), 4, 0.15, 0.15, 0.15, 0.02);
                world.spawnParticles(ParticleTypes.SMOKE, item.getX(), item.getY() + 0.6, item.getZ(), 2, 0.1, 0.15, 0.1, 0.01);
            }
            if (now >= entry.getValue()) {
                world.playSound(null, item.getX(), item.getY(), item.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH,
                        SoundCategory.BLOCKS, 0.8f, 1.2f);
                world.spawnParticles(ParticleTypes.SOUL, item.getX(), item.getY() + 0.3, item.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
                item.discard();
                burning.remove(item);
            }
        }
    }

    // ----- подача -----

    private void serveDish(DreamSession session, long now) {
        dishTainted = session.random.nextDouble() < TAINTED_CHANCE;
        Item item = dishTainted ? TAINTED_MENU[session.random.nextInt(TAINTED_MENU.length)]
                : FRESH_MENU[session.random.nextInt(FRESH_MENU.length)];
        ItemStack stack = new ItemStack(item);
        stack.setCustomName(tr(dishTainted ? "somnium.item.feast_dish_tainted" : "somnium.item.feast_dish")
                .formatted(dishTainted ? Formatting.DARK_GREEN : Formatting.GOLD));
        stack.getOrCreateNbt().putBoolean(DISH_TAG, true);
        stack.getOrCreateNbt().putBoolean(TAINTED_TAG, dishTainted);
        ItemEntity entity = new ItemEntity(session.world, plate.getX() + 0.5, plate.getY() + 1.0, plate.getZ() + 0.5, stack);
        entity.setVelocity(0, 0, 0);
        entity.setNeverDespawn();
        entity.setGlowing(true);
        dish = session.add(entity);
        courseOpen = true;
        dishDeadline = now + DISH_LIFETIME;
        session.sound(plate, SoundEvents.ENTITY_ITEM_FRAME_PLACE, SoundCategory.BLOCKS, 1.0f, 0.5f);

        // При полной сытости ваниль не даёт есть — перед подачей все слегка голодают
        for (ServerPlayerEntity player : session.players()) {
            player.getHungerManager().setFoodLevel(12);
            player.getHungerManager().setSaturationLevel(1.0f);
        }
        if (coursesDone == 0) session.hint(tr("somnium.hint.feast.rules").formatted(Formatting.GOLD), 10);
    }

    private void finishCourse(long now) {
        dish = null;
        courseOpen = false;
        coursesDone++;
        nextDish = now + DISH_INTERVAL;
    }

    private void serveGoblets(DreamSession session) {
        gobletsServed = true;
        int count = Math.max(1, session.players().size());
        for (int i = 0; i < count; i++) {
            ItemStack goblet = new ItemStack(Items.HONEY_BOTTLE);
            goblet.getOrCreateNbt().putBoolean(TOAST_TAG, true);
            goblet.setCustomName(tr("somnium.item.toast_goblet").formatted(Formatting.GOLD, Formatting.BOLD));
            ItemEntity entity = new ItemEntity(session.world, plate.getX() + 0.5 + (i - (count - 1) / 2.0),
                    plate.getY() + 1.0, plate.getZ() + 0.5, goblet);
            entity.setVelocity(0, 0, 0);
            entity.setNeverDespawn();
            entity.setGlowing(true);
            session.add(entity);
        }
        session.sound(plate, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1.0f, 0.6f);
        react(session, Reaction.APPROVE, plate);
        session.hint(tr("somnium.hint.feast.goblet").formatted(Formatting.GOLD), 10);
        if (golem != null && !golem.isRemoved()) {
            golem.setDormant(false);
            golem.setGuard(plate, 7.0);
            session.sound(golem.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.0f, 0.6f);
            session.hint(tr("somnium.hint.feast.golem").formatted(Formatting.DARK_RED), 8);
        }
    }

    // ----- действия игрока -----

    @Override
    public ActionResult onUseItem(DreamSession session, ServerPlayerEntity player, Hand hand) {
        return useLighter(session, player);
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        return useLighter(session, player);
    }

    /** Зажигалка в одной руке + блюдо в другой — сжечь; иначе — ближайшее брошенное блюдо в 4 блоках. */
    private ActionResult useLighter(DreamSession session, ServerPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        ItemStack off = player.getOffHandStack();
        boolean lighterMain = isLighter(main);
        boolean lighterOff = isLighter(off);
        if (!lighterMain && !lighterOff) return ActionResult.PASS;
        long now = session.now();

        ItemStack held = lighterMain ? off : main;
        if (isDish(held)) {
            boolean tainted = isTainted(held);
            held.decrement(1);
            burnEffects(session.world, player.getX(), player.getY() + 1.2, player.getZ());
            judgeBurn(session, player, tainted, player.getBlockPos(), now);
            return ActionResult.SUCCESS;
        }

        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity e : session.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(4.0),
                e -> isDish(e.getStack()) && !burning.containsKey(e))) {
            double d = e.squaredDistanceTo(player);
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        if (nearest == null) return ActionResult.PASS;
        boolean tainted = isTainted(nearest.getStack());
        nearest.setFireTicks(BURN_TICKS + 20);
        nearest.setPickupDelay(32767);
        burning.put(nearest, now + BURN_TICKS);
        if (nearest == dish) dish = null;
        burnEffects(session.world, nearest.getX(), nearest.getY(), nearest.getZ());
        judgeBurn(session, player, tainted, nearest.getBlockPos(), now);
        return ActionResult.SUCCESS;
    }

    @Override
    public void onFinishUsing(DreamSession session, ServerPlayerEntity player, ItemStack stack) {
        if (isToast(stack)) {
            react(session, Reaction.CELEBRATE, null);
            player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 0.8f);
            DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE);
            return;
        }
        if (!isDish(stack)) return;
        if (courseOpen) finishCourse(session.now());
        if (isTainted(stack)) {
            SanityManager.add(player, EAT_TAINTED_SANITY);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 200, 0));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0));
            player.sendMessage(tr("somnium.hint.feast.ate_tainted").formatted(Formatting.DARK_GREEN), true);
            react(session, Reaction.LAUGH, null);
        } else {
            SanityManager.add(player, EAT_FRESH_SANITY);
            player.sendMessage(tr("somnium.hint.feast.ate_fresh").formatted(Formatting.GOLD), true);
            react(session, Reaction.APPROVE, null);
        }
    }

    private void judgeBurn(DreamSession session, ServerPlayerEntity player, boolean tainted, BlockPos where, long now) {
        if (courseOpen) finishCourse(now);
        if (tainted) {
            SanityManager.add(player, BURN_TAINTED_SANITY);
            player.sendMessage(tr("somnium.hint.feast.burned_tainted").formatted(Formatting.GOLD), true);
            react(session, Reaction.APPROVE, where);
        } else {
            SanityManager.add(player, BURN_FRESH_SANITY);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 300, 0));
            player.sendMessage(tr("somnium.hint.feast.burned_fresh").formatted(Formatting.GRAY), true);
            react(session, Reaction.ANGRY, where);
        }
    }

    private static void burnEffects(ServerWorld world, double x, double y, double z) {
        world.playSound(null, x, y, z, SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        world.playSound(null, x, y, z, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 1.0f, 0.8f);
        world.spawnParticles(ParticleTypes.FLAME, x, y, z, 25, 0.3, 0.4, 0.3, 0.02);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.2, z, 10, 0.2, 0.3, 0.2, 0.01);
    }

    /** Убрать нерешённое блюдо: со стола, из инвентаря участников или с пола. */
    private void removeOpenDish(DreamSession session) {
        if (dish != null && !dish.isRemoved()) {
            dish.discard();
            return;
        }
        for (ServerPlayerEntity player : session.players()) {
            var inv = player.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                if (isDish(inv.getStack(i))) {
                    inv.setStack(i, ItemStack.EMPTY);
                    return;
                }
            }
        }
        for (ItemEntity item : session.world.getEntitiesByClass(ItemEntity.class, new Box(plate).expand(12, 6, 12),
                e -> isDish(e.getStack()) && !burning.containsKey(e))) {
            item.discard();
            return;
        }
    }

    private void react(DreamSession session, Reaction reaction, @Nullable BlockPos focus) {
        ServerWorld world = session.world;
        for (MobEntity judge : judges) {
            double x = judge.getX(), y = judge.getY(), z = judge.getZ();
            switch (reaction) {
                case APPROVE -> {
                    world.playSound(null, x, y, z, SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.HOSTILE, 0.9f, 0.55f);
                    world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 2.2, z, 6, 0.2, 0.2, 0.2, 0.02);
                }
                case ANGRY -> {
                    world.playSound(null, x, y, z, SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.HOSTILE, 0.9f, 0.5f);
                    world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, x, y + 2.2, z, 4, 0.25, 0.2, 0.25, 0.02);
                }
                case LAUGH -> {
                    world.playSound(null, x, y, z, SoundEvents.ENTITY_WITCH_CELEBRATE, SoundCategory.HOSTILE, 0.9f, 0.5f);
                    world.spawnParticles(ParticleTypes.WITCH, x, y + 2.0, z, 8, 0.3, 0.3, 0.3, 0.02);
                }
                case CELEBRATE -> {
                    world.playSound(null, x, y, z, SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.HOSTILE, 1.0f, 0.6f);
                    world.spawnParticles(ParticleTypes.END_ROD, x, y + 2.2, z, 10, 0.3, 0.3, 0.3, 0.05);
                }
            }
        }
        if (focus != null) {
            judgeFocus = focus;
            judgeFocusUntil = session.now() + JUDGE_FOCUS_TICKS;
        }
    }

    // ----- предметы пира -----

    private void giveLighter(ServerPlayerEntity player) {
        ItemStack lighter = new ItemStack(Items.FLINT_AND_STEEL);
        lighter.getOrCreateNbt().putBoolean(LIGHTER_TAG, true);
        lighter.getOrCreateNbt().putBoolean("Unbreakable", true);
        lighter.setCustomName(tr("somnium.item.feast_lighter").formatted(Formatting.GOLD, Formatting.BOLD));
        player.getInventory().offerOrDrop(lighter);
        if (lighterExplained.add(player.getUuid())) {
            DreamHints.show(player, tr("somnium.hint.feast.lighter").formatted(Formatting.GOLD), 8);
        }
    }

    private static boolean hasItem(ServerPlayerEntity player, java.util.function.Predicate<ItemStack> test) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (test.test(inv.getStack(i))) return true;
        }
        return false;
    }

    private static boolean hasTag(ItemStack stack, String tag) {
        return stack.hasNbt() && stack.getNbt().getBoolean(tag);
    }

    private static boolean isLighter(ItemStack stack) {
        return stack.isOf(Items.FLINT_AND_STEEL) && hasTag(stack, LIGHTER_TAG);
    }

    private static boolean isToast(ItemStack stack) {
        return stack.isOf(Items.HONEY_BOTTLE) && hasTag(stack, TOAST_TAG);
    }

    private static boolean isDish(ItemStack stack) {
        return hasTag(stack, DISH_TAG);
    }

    private static boolean isTainted(ItemStack stack) {
        return isDish(stack) && hasTag(stack, TAINTED_TAG);
    }

    // ----- зал -----

    /**
     * Чернитно-багровый чертог с базальтовыми колоннами, люстрой, кострами душ, знамёнами
     * и длинным столом. За столом — пять неподвижных Судей в черепах и тёмных рясах.
     */
    private void buildHall(DreamSession session, BlockPos center) {
        ServerWorld world = session.world;
        int floorY = center.getY() - 1;
        for (int x = -8; x <= 8; x++) {
            for (int z = -7; z <= 7; z++) {
                boolean runner = Math.abs(x) <= 1;
                set(world, new BlockPos(center.getX() + x, floorY, center.getZ() + z),
                        (runner ? Blocks.NETHER_WART_BLOCK : Blocks.POLISHED_BLACKSTONE).getDefaultState());
                set(world, new BlockPos(center.getX() + x, floorY + 7, center.getZ() + z),
                        Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
            }
        }
        for (int y = 0; y <= 5; y++) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -7; z <= 7; z++) {
                    if (Math.abs(x) != 8 && Math.abs(z) != 7) continue;
                    if (z == 7 && Math.abs(x) <= 1 && y <= 2) continue; // проём
                    boolean pillar = (Math.abs(x) == 8 && (Math.abs(z) == 7 || z == -3 || z == 1))
                            || (Math.abs(z) == 7 && (x == -4 || x == 0 || x == 4));
                    BlockPos pos = new BlockPos(center.getX() + x, floorY + 1 + y, center.getZ() + z);
                    if (pillar) {
                        set(world, pos, Blocks.POLISHED_BASALT.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y));
                    } else if (y == 5) {
                        set(world, pos, Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState());
                    } else if (y == 3) {
                        set(world, pos, Blocks.RED_NETHER_BRICKS.getDefaultState());
                    } else {
                        set(world, pos, Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
                    }
                }
            }
        }
        // Проём ведёт в пустоту — закрываем его "дверью" из тьмы, чтобы гости не убегали со сцены
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                set(world, new BlockPos(center.getX() + x, center.getY() + y, center.getZ() + 8),
                        Blocks.BLACK_CONCRETE.getDefaultState());
            }
        }

        set(world, center.add(0, 5, -3), Blocks.CHAIN.getDefaultState());
        set(world, center.add(0, 4, -3), Blocks.SOUL_LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        for (int[] c : new int[][]{{-6, -5}, {6, -5}, {-6, 5}, {6, 5}}) {
            set(world, new BlockPos(center.getX() + c[0], floorY + 1, center.getZ() + c[1]),
                    Blocks.SOUL_CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, true));
        }
        for (int bx : new int[]{-3, 0, 3}) {
            set(world, center.add(bx, 3, -6), Blocks.RED_WALL_BANNER.getDefaultState()
                    .with(WallBannerBlock.FACING, Direction.SOUTH));
        }
        for (int x = -4; x <= 4; x++) {
            set(world, center.add(x, 0, -3), Blocks.POLISHED_BLACKSTONE.getDefaultState());
        }
        for (int cx : new int[]{-3, 3}) {
            set(world, center.add(cx, 1, -3), Blocks.RED_CANDLE.getDefaultState()
                    .with(CandleBlock.CANDLES, 2).with(CandleBlock.LIT, true));
        }
        set(world, center.add(-5, 0, -3), Blocks.SOUL_LANTERN.getDefaultState());
        set(world, center.add(5, 0, -3), Blocks.SOUL_LANTERN.getDefaultState());
        for (int tx = -4; tx <= 4; tx += 2) {
            set(world, center.add(tx, 0, -5), Blocks.BLACKSTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
            for (int dy = 0; dy <= 2; dy++) set(world, center.add(tx, dy, -6), Blocks.POLISHED_BLACKSTONE.getDefaultState());
        }

        for (int x = -4; x <= 4; x += 2) {
            MobEntity judge = ModEntities.FERAL_VILLAGER.create(world);
            if (judge == null) continue;
            judge.refreshPositionAndAngles(center.getX() + x + 0.5, center.getY(), center.getZ() - 4 + 0.5, 0f, 0f);
            judge.setAiDisabled(true);
            judge.setInvulnerable(true);
            judge.setSilent(true);
            judge.setPersistent();
            judge.setCustomName(tr("somnium.entity.feast_judge").formatted(Formatting.DARK_RED, Formatting.BOLD));
            judge.setCustomNameVisible(true);
            judge.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.WITHER_SKELETON_SKULL));
            judge.equipStack(EquipmentSlot.CHEST, robe(Items.LEATHER_CHESTPLATE));
            judge.equipStack(EquipmentSlot.LEGS, robe(Items.LEATHER_LEGGINGS));
            for (EquipmentSlot slot : EquipmentSlot.values()) judge.setEquipmentDropChance(slot, 0.0f);
            judges.add(session.add(judge));
        }
    }

    private static ItemStack robe(Item item) {
        ItemStack stack = new ItemStack(item);
        if (stack.getItem() instanceof DyeableItem dyeable) dyeable.setColor(stack, 0x1B0507);
        return stack;
    }
}
