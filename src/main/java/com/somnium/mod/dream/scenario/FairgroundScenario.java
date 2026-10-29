package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Заброшенный парк аттракционов ночью. Посередине крутится карусель с костяными лошадьми
 * и играет расстроенная мелодия. По кругу стоят колесо обозрения, шатёр, тир и комната
 * страха, у каждого — колокол. Надо прозвонить все четыре. Тогда карусель останавливается,
 * её столб загорается — встань в центр, и проснёшься. После каждого колокола из-за шатров
 * выходят новые «клоуны».
 */
public final class FairgroundScenario extends DreamScenario {

    private static final int RADIUS = 46;
    private static final int RING = 26;
    private static final int CAROUSEL_R = 5;
    private static final int HORSES = 6;

    private final Pickups bells = new Pickups();
    private final List<MobEntity> horses = new ArrayList<>();
    private BlockPos center;
    private boolean stopped;
    private double angle;
    private int note;

    @Override
    public int areaRadius() {
        return RADIUS + 8;
    }

    @Override
    public int boundaryRadius() {
        return RADIUS - 2;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld world = session.world;
        center = session.origin;

        // Земля: трава, вытоптанные дорожки, мусор
        for (int dx = -RADIUS - 2; dx <= RADIUS + 2; dx++) {
            for (int dz = -RADIUS - 2; dz <= RADIUS + 2; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > RADIUS + 2) continue;
                BlockPos pos = center.add(dx, -1, dz);
                setBulk(world, pos.down(), Blocks.DIRT.getDefaultState());
                boolean path = Math.abs(d - RING + 6) < 1.6 || (Math.abs(dx) < 2 && dz > 0);
                BlockState ground = path ? Blocks.DIRT_PATH.getDefaultState()
                        : switch (session.random.nextInt(9)) {
                            case 0 -> Blocks.COARSE_DIRT.getDefaultState();
                            case 1 -> Blocks.PODZOL.getDefaultState();
                            default -> Blocks.GRASS_BLOCK.getDefaultState();
                        };
                setBulk(world, pos, ground);
                if (!path && d < RADIUS && session.random.nextInt(30) == 0) {
                    setBulk(world, pos.up(), session.random.nextBoolean() ? Blocks.DEAD_BUSH.getDefaultState() : Blocks.GRASS.getDefaultState());
                }
                // Забор по краю
                if (d >= RADIUS && d < RADIUS + 1 && !(Math.abs(dx) < 2 && dz > 0)) {
                    setBulk(world, pos.up(), Blocks.DARK_OAK_FENCE.getDefaultState());
                    setBulk(world, pos.up(2), Blocks.DARK_OAK_FENCE.getDefaultState());
                }
            }
        }
        // Фонарные столбы вдоль дорожки, часть разбита
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI * 2 / 12;
            BlockPos post = center.add((int) Math.round(Math.cos(a) * (RING - 9)), 0, (int) Math.round(Math.sin(a) * (RING - 9)));
            fill(world, post, post.up(3), Blocks.DARK_OAK_FENCE.getDefaultState());
            if (session.random.nextInt(3) != 0) {
                setBulk(world, post.up(4), Blocks.SOUL_LANTERN.getDefaultState().with(LanternBlock.HANGING, false));
            }
        }

        buildCarousel(session);
        double base = session.random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 4; i++) {
            double a = base + i * Math.PI / 2;
            BlockPos at = center.add((int) Math.round(Math.cos(a) * RING), 0, (int) Math.round(Math.sin(a) * RING));
            switch (i) {
                case 0 -> buildWheel(world, at);
                case 1 -> buildTent(world, at);
                case 2 -> buildBooth(session, at);
                default -> buildFunhouse(world, at);
            }
            // Колокол на столбе у входа, ближе к центру
            BlockPos bell = center.add((int) Math.round(Math.cos(a) * (RING - 6)), 0, (int) Math.round(Math.sin(a) * (RING - 6)));
            setBulk(world, bell, Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState());
            setBulk(world, bell.up(), Blocks.BELL.getDefaultState());
            bells.add(bell.up());
        }

        spawnMonsters(session, center, 2, 16, RING + 8);
        return center.add(0, 0, RADIUS - 4);
    }

    private void buildCarousel(DreamSession session) {
        ServerWorld world = session.world;
        for (int dx = -CAROUSEL_R; dx <= CAROUSEL_R; dx++) {
            for (int dz = -CAROUSEL_R; dz <= CAROUSEL_R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > CAROUSEL_R + 0.5) continue;
                setBulk(world, center.add(dx, -1, dz), (dx + dz) % 2 == 0
                        ? Blocks.RED_TERRACOTTA.getDefaultState() : Blocks.WHITE_TERRACOTTA.getDefaultState());
                // Шатёр-крыша конусом
                int roofY = 6 - (int) (d / 2);
                setBulk(world, center.add(dx, roofY, dz), (int) (Math.atan2(dz, dx) * 4 / Math.PI + 8) % 2 == 0
                        ? Blocks.RED_WOOL.getDefaultState() : Blocks.WHITE_WOOL.getDefaultState());
                if (d > CAROUSEL_R - 0.5 && (dx + dz) % 3 == 0) {
                    fill(world, center.add(dx, 0, dz), center.add(dx, roofY - 1, dz), Blocks.BIRCH_FENCE.getDefaultState());
                }
            }
        }
        fill(world, center, center.up(6), Blocks.STRIPPED_BIRCH_LOG.getDefaultState());
        for (int i = 0; i < HORSES; i++) {
            MobEntity horse = session.spawnMob(EntityType.SKELETON_HORSE, center.add(3, 0, 0), 0f);
            if (horse == null) continue;
            horse.setAiDisabled(true);
            horse.setInvulnerable(true);
            horse.setSilent(true);
            horses.add(horse);
        }
    }

    /** Колесо обозрения: обод из решёток, спицы, разноцветные кабинки. */
    private void buildWheel(ServerWorld world, BlockPos at) {
        int r = 9;
        BlockPos hub = at.up(r + 1);
        for (int deg = 0; deg < 360; deg += 3) {
            double a = Math.toRadians(deg);
            setBulk(world, hub.add((int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r), 0), Blocks.IRON_BARS.getDefaultState());
        }
        BlockState[] cabins = {Blocks.RED_WOOL.getDefaultState(), Blocks.YELLOW_WOOL.getDefaultState(),
                Blocks.BLUE_WOOL.getDefaultState(), Blocks.LIME_WOOL.getDefaultState()};
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            for (int s = 1; s < r; s++) {
                setBulk(world, hub.add((int) Math.round(Math.cos(a) * s), (int) Math.round(Math.sin(a) * s), 0), Blocks.CHAIN.getDefaultState());
            }
            BlockPos cabin = hub.add((int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r) - 1, 0);
            setBulk(world, cabin, cabins[i % cabins.length]);
        }
        setBulk(world, hub, Blocks.IRON_BLOCK.getDefaultState());
        for (int y = 0; y <= r; y++) {
            setBulk(world, at.add(-y / 3 - 1, y, 1), Blocks.IRON_BARS.getDefaultState());
            setBulk(world, at.add(y / 3 + 1, y, 1), Blocks.IRON_BARS.getDefaultState());
        }
    }

    /** Полосатый шатёр конусом с проходом. */
    private void buildTent(ServerWorld world, BlockPos at) {
        int r = 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r + 0.5) continue;
                int h = 7 - (int) d;
                boolean stripe = (int) ((Math.atan2(dz, dx) + Math.PI) * 6 / Math.PI) % 2 == 0;
                BlockState wool = stripe ? Blocks.PURPLE_WOOL.getDefaultState() : Blocks.WHITE_WOOL.getDefaultState();
                setBulk(world, at.add(dx, h, dz), wool);
                if (d > r - 0.5 && !(Math.abs(dx) <= 1 && dz < 0)) fill(world, at.add(dx, 0, dz), at.add(dx, h - 1, dz), wool);
                setBulk(world, at.add(dx, -1, dz), Blocks.SPRUCE_PLANKS.getDefaultState());
            }
        }
        setBulk(world, at.up(), Blocks.CANDLE.getDefaultState());
        setBulk(world, at, Blocks.SPRUCE_PLANKS.getDefaultState());
    }

    /** Тир: прилавок, мишени, клоун за стойкой. */
    private void buildBooth(DreamSession session, BlockPos at) {
        ServerWorld world = session.world;
        fill(world, at.add(-3, 0, -2), at.add(3, 0, -2), Blocks.STRIPPED_SPRUCE_LOG.getDefaultState());
        fill(world, at.add(-3, 0, 1), at.add(3, 3, 1), Blocks.SPRUCE_PLANKS.getDefaultState());
        for (int x = -2; x <= 2; x += 2) setBulk(world, at.add(x, 2, 0), Blocks.TARGET.getDefaultState());
        fill(world, at.add(-3, 4, -2), at.add(3, 4, 1), Blocks.RED_WOOL.getDefaultState());
        fill(world, at.add(-3, 1, -2), at.add(-3, 3, -2), Blocks.SPRUCE_FENCE.getDefaultState());
        fill(world, at.add(3, 1, -2), at.add(3, 3, -2), Blocks.SPRUCE_FENCE.getDefaultState());
        ArmorStandEntity clown = new ArmorStandEntity(world, at.getX() + 0.5, at.getY(), at.getZ() - 0.5);
        clown.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        clown.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        clown.setInvulnerable(true);
        faceTowards(clown, center.toCenterPos());
        session.add(clown);
    }

    /** Комната страха: чёрный куб с вывеской-черепом, внутри темно. */
    private void buildFunhouse(ServerWorld world, BlockPos at) {
        fill(world, at.add(-4, 0, -4), at.add(4, 5, 4), Blocks.BLACKSTONE.getDefaultState());
        fill(world, at.add(-3, 0, -3), at.add(3, 4, 3), Blocks.AIR.getDefaultState());
        fill(world, at.add(-4, 0, 0), at.add(-4, 1, 0), Blocks.AIR.getDefaultState());
        fill(world, at.add(4, 0, 0), at.add(4, 1, 0), Blocks.AIR.getDefaultState());
        setBulk(world, at.add(0, 6, 0), Blocks.SKELETON_SKULL.getDefaultState());
        setBulk(world, at.add(0, 2, 3), Blocks.REDSTONE_TORCH.getDefaultState());
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        return around(center, RADIUS + 3, -2, 22);
    }

    @Override
    public void onJoin(DreamSession session, ServerPlayerEntity player, boolean leader) {
        DreamHints.show(player, tr("somnium.hint.fairground.start").formatted(Formatting.GRAY), 10);
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (!session.world.getBlockState(pos).isOf(Blocks.BELL)) return ActionResult.PASS;
        session.sound(pos, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 3.0f, 0.7f);
        if (!bells.take(pos)) return ActionResult.SUCCESS;
        int left = bells.remaining();
        // На звон выходят клоуны
        for (Identifier id : session.type.monsters()) {
            session.spawnMob(id, session.ringPos(player.getBlockPos(), 12, 18), 0f);
        }
        if (left > 0) {
            session.actionBar(tr("somnium.hint.fairground.bell", left).formatted(Formatting.GOLD));
        } else {
            stopped = true;
            fill(session.world, center, center.up(2), Blocks.AIR.getDefaultState());
            setBulk(session.world, center.up(3), Blocks.SEA_LANTERN.getDefaultState());
            setBulk(session.world, center.down(), Blocks.SEA_LANTERN.getDefaultState());
            session.sound(center, ModSounds.EXIT_PORTAL, SoundCategory.AMBIENT, 3.0f, 1.0f);
            session.hint(tr("somnium.hint.fairground.done").formatted(Formatting.WHITE), 8);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public void tick(DreamSession session) {
        ServerWorld world = session.world;
        long now = session.now();
        if (now % 10 == 0) bells.sparkle(world, ParticleTypes.WAX_ON);

        // Карусель крутится и играет мелодию, которая сползает вниз
        if (!stopped) {
            angle += 0.03;
            for (int i = 0; i < horses.size(); i++) {
                MobEntity horse = horses.get(i);
                if (horse.isRemoved()) continue;
                double a = angle + i * Math.PI * 2 / horses.size();
                double bob = Math.sin(angle * 4 + i) * 0.3;
                Vec3d pos = center.toCenterPos().add(Math.cos(a) * 3.5, -0.15 + bob, Math.sin(a) * 3.5);
                horse.refreshPositionAndAngles(pos.x, pos.y, pos.z, (float) Math.toDegrees(a), 0f);
                horse.setNoGravity(true);
            }
            if (now % 8 == 0) {
                int[] tune = {12, 10, 12, 7, 5, 7, 3, 0, 2, 3, 5, 2};
                float pitch = (float) Math.pow(2, (tune[note % tune.length] - 12 - (note / tune.length) % 3) / 12.0);
                session.sound(center.up(3), SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.RECORDS, 2.5f, pitch);
                note++;
            }
        } else if (now % 10 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, 5, 0.5, 0.5, 0.5, 0.02);
        }
        // Смех из шатров и далёкий скрип колеса
        if (now % 260 == 0) {
            ServerPlayerEntity player = session.randomPlayer();
            if (player != null) {
                Horror.sound(player, SoundEvents.ENTITY_WITCH_CELEBRATE, Horror.around(player, session.random.nextDouble() * 360, 14),
                        1.0f, 0.6f);
            }
        }
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (stopped) reachedExit(session, player, center, 1.5);
    }
}
