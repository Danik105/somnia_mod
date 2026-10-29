package com.somnium.mod.haunt;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.dream.DreamSession;
import com.somnium.mod.entity.nightmare.DrownedWretchEntity;
import com.somnium.mod.entity.nightmare.ThinManEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Свои страшные моменты для каждого сна. Раз в 15-25 секунд каждому участнику достаётся
 * случайный момент из набора его сна (первые 10 секунд сна тихо). Звуки и видения личные.
 */
public final class DreamScares {

    @FunctionalInterface
    interface Scare {
        void run(DreamSession session, ServerPlayerEntity player, Random random);
    }

    private static final Map<String, List<Scare>> BY_SCENARIO = new HashMap<>();
    private static final Map<DreamSession, Long> NEXT = new WeakHashMap<>();

    private DreamScares() {}

    private static Scare event(String id) {
        return (session, player, random) -> WorldEvents.fire(id, player);
    }

    private static Scare sound(net.minecraft.sound.SoundEvent sound, double distance, float volume, float pitch) {
        return (session, player, random) -> Horror.sound(player, sound,
                Horror.around(player, random.nextDouble() * 360, distance).add(0, 1, 0), volume, pitch);
    }

    private static Scare title(String key) {
        return (session, player, random) -> {
            DreamFx.title(player, Text.translatable(key).formatted(Formatting.DARK_RED, Formatting.BOLD), null, 0, 12, 6);
            Horror.sound(player, ModSounds.RUPTURE_STINGER, player.getEyePos(), 0.8f, 1.3f);
        };
    }

    /** Тощий мелькает вдали, в стороне от взгляда. */
    private static final Scare THIN = (session, player, random) -> {
        ServerWorld world = session.world;
        BlockPos pos = Horror.ground(world, Horror.around(player, (random.nextDouble() - 0.5) * 120, 18 + random.nextInt(8))
                .add(0, 1, 0), 8);
        if (pos == null) return;
        ThinManEntity thin = ModEntities.THIN_MAN.create(world);
        if (thin == null) return;
        thin.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        Horror.face(thin, player.getEyePos());
        thin.glimpse(70);
        session.add(thin);
    };

    static {
        BY_SCENARIO.put("drowning_city", List.of(
                sound(SoundEvents.BLOCK_BELL_USE, 40, 3.0f, 0.4f),
                (session, player, random) -> {
                    // Пузыри вокруг и захлёбывающийся хрип у самого уха
                    session.world.spawnParticles(player, ParticleTypes.BUBBLE_COLUMN_UP, true,
                            player.getX(), player.getY(), player.getZ(), 40, 1.5, 0.5, 1.5, 0.1);
                    Horror.sound(player, SoundEvents.ENTITY_DROWNED_AMBIENT_WATER, Horror.behind(player, 1), 1.2f, 0.6f);
                },
                (session, player, random) -> {
                    // Утопленник стоит на воде и смотрит, потом уходит под воду
                    BlockPos pos = Horror.ground(session.world, Horror.around(player, (random.nextDouble() - 0.5) * 90, 14)
                            .add(0, 1, 0), 10);
                    if (pos == null) return;
                    DrownedWretchEntity wretch = ModEntities.DROWNED_WRETCH.create(session.world);
                    if (wretch == null) return;
                    wretch.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                    wretch.setAiDisabled(true);
                    wretch.setInvulnerable(true);
                    Horror.face(wretch, player.getEyePos());
                    session.add(wretch);
                    Horror.later(80, wretch::discard);
                },
                (session, player, random) -> {
                    Horror.sound(player, ModSounds.WATER_RISE, player.getPos(), 1.5f, 0.6f);
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 80, 0, false, false, false));
                },
                event("whisper")));
        BY_SCENARIO.put("shadow_forest", List.of(
                sound(SoundEvents.BLOCK_WOOD_BREAK, 6, 1.2f, 0.6f),
                event("name_call"), event("eyes_in_dark"), event("shadow_runner"), event("breathing"), THIN,
                sound(SoundEvents.ENTITY_WOLF_HOWL, 40, 2.0f, 0.5f)));
        BY_SCENARIO.put("mirror_wastes", List.of(
                sound(SoundEvents.BLOCK_GLASS_HIT, 5, 1.2f, 0.5f),
                sound(SoundEvents.BLOCK_GLASS_BREAK, 12, 1.0f, 0.7f),
                event("footsteps"), title("somnium.scare.mirror"), event("laughter"),
                (session, player, random) -> Horror.lookAt(player, Horror.behind(player, 4))));
        BY_SCENARIO.put("collapsing_mine", List.of(
                (session, player, random) -> {
                    // Где-то рядом осыпается свод
                    Horror.sound(player, SoundEvents.BLOCK_GRAVEL_BREAK, player.getEyePos().add(0, 2, 0), 1.2f, 0.5f);
                    session.world.spawnParticles(player, new BlockStateParticleEffect(ParticleTypes.FALLING_DUST,
                            Blocks.GRAVEL.getDefaultState()), true, player.getX(), player.getY() + 3, player.getZ(), 30, 2, 0.2, 2, 0);
                },
                sound(ModSounds.MINER_SCREAM, 25, 1.0f, 0.6f),
                sound(SoundEvents.BLOCK_STONE_HIT, 4, 1.0f, 0.8f),
                sound(SoundEvents.ENTITY_MINECART_RIDING, 15, 0.8f, 0.5f),
                event("torches_out"), event("heartbeat")));
        BY_SCENARIO.put("crimson_feast", List.of(
                sound(SoundEvents.ENTITY_GENERIC_EAT, 2, 1.0f, 0.5f),
                sound(SoundEvents.BLOCK_GLASS_BREAK, 6, 1.0f, 1.2f),
                event("laughter"), event("heartbeat"), title("somnium.scare.feast"),
                sound(SoundEvents.ENTITY_VILLAGER_AMBIENT, 3, 1.0f, 0.4f)));
        BY_SCENARIO.put("void_of_eyes", List.of(
                event("eyes_in_dark"), event("eyes_in_dark"),
                sound(SoundEvents.ENTITY_ENDERMAN_STARE, 6, 1.0f, 0.5f),
                title("somnium.scare.eyes"), event("whisper"), event("darkness_pulse")));
        BY_SCENARIO.put("stairwell", List.of(
                event("knock"),
                (session, player, random) -> {
                    // Шаги этажом ниже, всё ближе
                    for (int i = 0; i < 6; i++) {
                        int step = i;
                        Horror.later(1 + i * 9, () -> Horror.sound(player, SoundEvents.BLOCK_STONE_STEP,
                                player.getPos().add(0, -6 + step, 0), 1.0f, 0.8f));
                    }
                },
                sound(SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, 5, 1.2f, 0.6f),
                (session, player, random) -> Horror.lookAt(player, player.getEyePos().add(0, -10, 0.01)),
                event("breathing"), THIN));
        BY_SCENARIO.put("mirror_room", List.of(
                sound(SoundEvents.BLOCK_GLASS_HIT, 4, 1.3f, 0.5f),
                event("whisper"), title("somnium.scare.mirror"), event("heartbeat")));
        BY_SCENARIO.put("elevator", List.of(
                (session, player, random) -> Horror.sound(player, SoundEvents.BLOCK_CHAIN_STEP,
                        player.getEyePos().add(0, 3, 0), 1.5f, 0.5f),
                (session, player, random) -> Horror.sound(player, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR,
                        player.getEyePos().add(0, 2.5, 0), 1.0f, 0.5f),
                event("breathing"), event("whisper"),
                (session, player, random) -> Horror.sound(player, SoundEvents.BLOCK_GRINDSTONE_USE,
                        player.getEyePos().add(0, 3, 0), 0.7f, 0.5f)));
        BY_SCENARIO.put("night_train", List.of(
                (session, player, random) -> {
                    // Кто-то стучит в окно снаружи
                    Vec3d side = Horror.around(player, 90, 3).add(0, 1.5, 0);
                    for (int i = 0; i < 3; i++) {
                        Horror.later(1 + i * 8, () -> Horror.sound(player, SoundEvents.BLOCK_GLASS_HIT, side, 1.3f, 0.6f));
                    }
                },
                (session, player, random) -> player.sendMessage(Text.translatable("somnium.scare.train.station."
                        + (1 + random.nextInt(3))).formatted(Formatting.GRAY, Formatting.ITALIC), false),
                sound(SoundEvents.BLOCK_GRINDSTONE_USE, 10, 1.0f, 0.4f),
                event("laughter"), event("whisper")));
        BY_SCENARIO.put("backrooms", List.of(event("footsteps"), event("far_scream"), event("breathing")));
    }

    /** Вызывается раз в тик для каждой сессии сна. */
    public static void tick(DreamSession session) {
        List<Scare> scares = BY_SCENARIO.get(session.type.scenario().getPath());
        if (scares == null || session.elapsed() < 200) return;
        long now = session.now();
        Long next = NEXT.get(session);
        if (next == null) {
            NEXT.put(session, now + 100 + session.random.nextInt(200));
            return;
        }
        if (now < next) return;
        NEXT.put(session, now + 300 + session.random.nextInt(200));
        for (ServerPlayerEntity player : session.players()) {
            if (Horror.inCutscene(player.getUuid())) continue;
            scares.get(session.random.nextInt(scares.size())).run(session, player, session.random);
        }
    }
}
