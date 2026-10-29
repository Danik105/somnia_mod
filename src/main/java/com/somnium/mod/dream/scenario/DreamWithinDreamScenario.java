package com.somnium.mod.dream.scenario;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamHints;
import com.somnium.mod.dream.DreamManager;
import com.somnium.mod.dream.DreamOutcome;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.ThinManEntity;
import com.somnium.mod.haunt.Horror;
import com.somnium.mod.haunt.WorldEvents;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

/**
 * Сон-в-сне: точная копия мира вокруг кровати (128×128 блоков), без названия сна,
 * HUD и эффектов — неотличимо от яви. Через 20 секунд наступает ночь, гроза и красные
 * надписи-обманки, зовущие к краю (падение за край — смерть во сне). Настоящий выход —
 * снова уснуть: ПКМ по кровати в копии мира.
 *
 * Ночь и гроза отправляются игрокам пакетами: время и погода измерений снов
 * принадлежат основному миру и не меняются через setTimeOfDay/setWeather.
 */
public final class DreamWithinDreamScenario extends DreamScenario {

    private static final int HALF = 64;
    private static final int DEPTH = 30;
    private static final int NIGHTMARE_AT = 400;
    private static final int MESSAGES = 10;

    private int offsetX;
    private int offsetZ;
    private int minY;
    private int maxY;
    private boolean nightmare;
    private long phaseStart;
    private long nextLightning;
    private long nextMoment;
    private int momentIndex;

    /** Страшные моменты кошмарной фазы по очереди; "cutscene" — встреча с Тощим. */
    private static final String[] MOMENTS = {"footsteps", "torches_out", "thin_glimpse", "knock", "sleep_paralysis",
            "eyes_in_dark", "cutscene", "turn_around", "stranger", "screamer", "pillar_behind", "laughter", "door_toggle"};

    @Override
    public boolean isSilent() {
        return true;
    }

    @Override
    public int areaRadius() {
        return HALF + 16;
    }

    @Override
    public BlockPos setup(DreamSession session, ServerPlayerEntity leader) {
        ServerWorld real = leader.getServerWorld();
        ServerWorld dream = session.world;
        BlockPos center = leader.getBlockPos();
        offsetX = session.origin.getX() - center.getX();
        offsetZ = session.origin.getZ() - center.getZ();
        minY = Math.max(Math.max(real.getBottomY(), dream.getBottomY()), center.getY() - DEPTH);
        maxY = Math.min(Math.min(real.getTopY(), dream.getTopY()) - 1, center.getY() + DEPTH);

        BlockPos.Mutable from = new BlockPos.Mutable();
        BlockPos.Mutable to = new BlockPos.Mutable();
        for (int x = center.getX() - HALF; x < center.getX() + HALF; x++) {
            for (int z = center.getZ() - HALF; z < center.getZ() + HALF; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = real.getBlockState(from.set(x, y, z));
                    if (state.isAir()) continue;
                    dream.setBlockState(to.set(x + offsetX, y, z + offsetZ), state, BULK);
                }
            }
        }

        BlockPos spawn = center.add(offsetX, 0, offsetZ);
        if (dream.getBlockState(spawn.down()).isAir()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    dream.setBlockState(spawn.add(dx, -1, dz), Blocks.STONE.getDefaultState(), BULK);
                }
            }
        }
        return spawn;
    }

    @Override
    public BlockBox buildBounds(DreamSession session) {
        BlockPos o = session.origin;
        return new BlockBox(o.getX() - HALF, minY, o.getZ() - HALF, o.getX() + HALF - 1, maxY, o.getZ() + HALF - 1);
    }

    @Override
    public void tick(DreamSession session) {
        long now = session.now();
        if (!nightmare && session.elapsed() >= NIGHTMARE_AT) {
            nightmare = true;
            phaseStart = now;
            nextLightning = now + 60;
            nextMoment = now + 160;
            for (ServerPlayerEntity player : session.players()) {
                player.playSound(ModSounds.DREAM_DREAD_AMBIENCE, SoundCategory.AMBIENT, 2.0f, 0.5f);
                player.playSound(ModSounds.RUPTURE_STINGER, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
        }
        if (!nightmare) return;

        // Сервер раз в секунду рассылает настоящее время мира — сразу перебиваем своим
        if (now % 20 == 0 || now == phaseStart) {
            for (ServerPlayerEntity player : session.players()) {
                DreamFx.sendTime(player, 18000L);
                DreamFx.sendWeather(player, 1.0f, 1.0f);
            }
        }
        long sincePhase = now - phaseStart;
        if (sincePhase > 0 && sincePhase % 600 == 0) {
            for (ServerPlayerEntity player : session.players()) {
                DreamHints.show(player, tr("somnium.hint.dwd.bed").formatted(Formatting.DARK_GRAY), 8);
            }
        } else if (sincePhase % 100 == 0) {
            Text message = tr("somnium.dwd.lie." + (1 + session.random.nextInt(MESSAGES)))
                    .formatted(Formatting.DARK_RED, Formatting.BOLD);
            for (ServerPlayerEntity player : session.players()) {
                DreamFx.title(player, message, null, 5, 30, 10);
                player.playSound(SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.HOSTILE, 0.8f, 0.5f);
            }
        }
        if (now >= nextMoment) {
            nextMoment = now + 300 + session.random.nextInt(150);
            String moment = MOMENTS[momentIndex++ % MOMENTS.length];
            for (ServerPlayerEntity player : session.players()) {
                if (moment.equals("cutscene")) thinCutscene(session, player);
                else if (!WorldEvents.fire(moment, player)) WorldEvents.fire("whisper", player);
            }
        }
        if (now >= nextLightning) {
            nextLightning = now + 160 + session.random.nextInt(160);
            ServerPlayerEntity target = session.randomPlayer();
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(session.world);
            if (target != null && bolt != null) {
                BlockPos at = session.ringPos(target.getBlockPos(), 18, 36);
                bolt.refreshPositionAfterTeleport(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                bolt.setCosmetic(true);
                session.add(bolt);
            }
        }
    }

    /** Свет гаснет, перед игроком стоит Тощий, камера не отпускает, потом он бросается. */
    private void thinCutscene(DreamSession session, ServerPlayerEntity player) {
        ServerWorld world = session.world;
        BlockPos pos = Horror.ground(world, Horror.around(player, 0, 7).add(0, 1, 0), 6);
        if (pos == null) return;
        ThinManEntity thin = ModEntities.THIN_MAN.create(world);
        if (thin == null) return;
        thin.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
        Horror.face(thin, player.getEyePos());
        thin.glimpse(100);
        session.add(thin);
        WorldEvents.fire("torches_out", player);
        Horror.cutscene(player, 85, () -> thin.isRemoved() ? null : thin.getPos().add(0, thin.getHeight() * 0.85, 0));
        Horror.sound(player, SoundEvents.ENTITY_WARDEN_HEARTBEAT, player.getEyePos(), 1.2f, 0.8f);
        Horror.later(20, () -> Horror.sound(player, SoundEvents.ENTITY_WARDEN_HEARTBEAT, player.getEyePos(), 1.2f, 0.9f));
        Horror.later(30, () -> DreamFx.title(player, tr("somnium.dwd.lie.1").formatted(Formatting.DARK_RED, Formatting.BOLD), null, 2, 25, 5));
        Horror.later(40, () -> Horror.sound(player, SoundEvents.ENTITY_WARDEN_HEARTBEAT, player.getEyePos(), 1.4f, 1.1f));
        Horror.later(62, () -> {
            if (thin.isRemoved()) return;
            var close = Horror.around(player, 0, 1.6);
            thin.refreshPositionAndAngles(close.x, player.getY(), close.z, 0f, 0f);
            Horror.face(thin, player.getEyePos());
            Horror.screamer(player, Horror.FACE_THIN);
        });
        Horror.later(78, thin::discard);
    }

    @Override
    public void tickPlayer(DreamSession session, ServerPlayerEntity player) {
        if (player.getY() < minY - 6 && DreamManager.requestWake(player, DreamOutcome.DIED_IN_DREAM)) {
            player.sendMessage(tr("somnium.hint.dwd.fell").formatted(Formatting.GRAY), true);
        }
    }

    @Override
    public ActionResult onUseBlock(DreamSession session, ServerPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!(session.world.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock)) return ActionResult.PASS;
        if (DreamManager.requestWake(player, DreamOutcome.SURVIVED_OBJECTIVE)) {
            player.playSound(ModSounds.DREAM_WAKE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
        return ActionResult.SUCCESS;
    }
}
