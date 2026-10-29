package com.somnium.mod.haunt;

import com.somnium.mod.dream.DreamFx;
import com.somnium.mod.entity.nightmare.LurkingShadeEntity;
import com.somnium.mod.entity.nightmare.PassengerEntity;
import com.somnium.mod.entity.nightmare.ThinManEntity;
import com.somnium.mod.registry.ModEntities;
import com.somnium.mod.registry.ModSounds;
import com.somnium.mod.sanity.SanityManager;
import net.minecraft.block.TorchBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationPropertyHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Пятьдесят случайных страшных событий в мире. Почти все личные: звуки, ложные блоки,
 * чат, погода и время приходят пакетами только одному игроку, остальные ничего не видят.
 * У каждого события есть порог рассудка: оно случается, только если рассудок не выше порога.
 * Чем ниже рассудок, тем больше событий доступно и тем чаще они случаются (HauntManager).
 */
public final class WorldEvents {

    @FunctionalInterface
    interface Action {
        /** false — событие здесь не подходит (нет дверей, факелов и т.п.). */
        boolean run(ServerPlayerEntity player, ServerWorld world, Random random);
    }

    record Event(String id, float maxSanity, int weight, Action action) {}

    private static final List<Event> EVENTS = new ArrayList<>();
    private static final Map<String, Event> BY_ID = new HashMap<>();
    private static final Random RANDOM = new Random();

    private WorldEvents() {}

    private static void add(String id, float maxSanity, int weight, Action action) {
        Event event = new Event(id, maxSanity, weight, action);
        EVENTS.add(event);
        BY_ID.put(id, event);
    }

    public static int count() {
        return EVENTS.size();
    }

    /** Случайное подходящее событие для этого рассудка. */
    public static boolean fireRandom(ServerPlayerEntity player, float sanity) {
        List<Event> pool = new ArrayList<>();
        int total = 0;
        for (Event event : EVENTS) {
            if (sanity <= event.maxSanity()) {
                pool.add(event);
                total += event.weight();
            }
        }
        for (int attempt = 0; attempt < 6 && total > 0; attempt++) {
            int roll = RANDOM.nextInt(total);
            for (Event event : pool) {
                roll -= event.weight();
                if (roll < 0) {
                    if (event.action().run(player, player.getServerWorld(), RANDOM)) return true;
                    break;
                }
            }
        }
        return false;
    }

    /** Конкретное событие без проверки рассудка (для сценариев снов). */
    public static boolean fire(String id, ServerPlayerEntity player) {
        Event event = BY_ID.get(id);
        return event != null && event.action().run(player, player.getServerWorld(), RANDOM);
    }

    // ----- помощники -----

    private static void sound(ServerPlayerEntity p, SoundEvent sound, Vec3d pos, float volume, float pitch) {
        Horror.sound(p, sound, pos, volume, pitch);
    }

    private static Vec3d randomAround(ServerPlayerEntity p, Random r, double dist) {
        return Horror.around(p, r.nextDouble() * 360, dist).add(0, 1, 0);
    }

    private static void chat(ServerPlayerEntity p, Text text) {
        p.sendMessage(text, false);
    }

    private static Text line(String key, Random r, int variants) {
        return Text.translatable(key + "." + (1 + r.nextInt(variants)));
    }

    private static List<BlockPos> find(ServerWorld w, BlockPos center, int radius, int limit,
                                       java.util.function.Predicate<BlockState> test) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterateOutwards(center, radius, radius / 2 + 1, radius)) {
            if (test.test(w.getBlockState(pos))) {
                found.add(pos.toImmutable());
                if (found.size() >= limit) break;
            }
        }
        return found;
    }

    private static void personalParticle(ServerPlayerEntity p, ParticleEffect effect, Vec3d at, int count, double spread) {
        p.getServerWorld().spawnParticles(p, effect, true, at.x, at.y, at.z, count, spread, spread, spread, 0.0);
    }

    private static <T extends MobEntity> T spawnStill(ServerWorld w, EntityType<T> type, BlockPos pos, ServerPlayerEntity faceTo) {
        T mob = type.create(w);
        if (mob == null) return null;
        mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
        mob.setAiDisabled(true);
        mob.setSilent(true);
        mob.setInvulnerable(true);
        mob.addCommandTag("somnium_haunt");
        Horror.face(mob, faceTo.getEyePos());
        w.spawnEntity(mob);
        return mob;
    }

    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.45f, 0.0f, 0.0f), 1.2f);
    private static final DustParticleEffect EYE_WHITE = new DustParticleEffect(new Vector3f(1.0f, 1.0f, 0.95f), 0.7f);
    private static final DustParticleEffect EYE_RED = new DustParticleEffect(new Vector3f(1.0f, 0.1f, 0.1f), 0.7f);

    static {
        // ----- звуки (рассудок до 70) -----
        add("footsteps", 70, 10, (p, w, r) -> {
            for (int i = 0; i < 6; i++) {
                double dist = 7 - i;
                Horror.later(i * 8, () -> sound(p, SoundEvents.BLOCK_GRAVEL_STEP, Horror.behind(p, dist).add(0, -1.4, 0), 0.9f, 0.8f));
            }
            return true;
        });
        add("whisper", 70, 10, (p, w, r) -> {
            sound(p, ModSounds.WHISPER_AMBIENT, Horror.behind(p, 1.5), 1.0f, 0.9f);
            return true;
        });
        add("creeper_hiss", 60, 6, (p, w, r) -> {
            sound(p, SoundEvents.ENTITY_CREEPER_PRIMED, Horror.behind(p, 2), 1.0f, 0.9f);
            return true;
        });
        add("knock", 70, 8, (p, w, r) -> {
            List<BlockPos> doors = find(w, p.getBlockPos(), 12, 1, s -> s.isIn(BlockTags.WOODEN_DOORS));
            Vec3d at = doors.isEmpty() ? Horror.behind(p, 5) : doors.get(0).toCenterPos();
            for (int i = 0; i < 3; i++) {
                Horror.later(1 + i * 10, () -> sound(p, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, at, 0.9f, 0.7f));
            }
            return true;
        });
        add("door_creak", 70, 6, (p, w, r) -> {
            Vec3d at = randomAround(p, r, 6);
            sound(p, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, at, 1.0f, 0.6f);
            Horror.later(40, () -> sound(p, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, at, 1.0f, 0.6f));
            return true;
        });
        add("breathing", 50, 8, (p, w, r) -> {
            for (int i = 0; i < 3; i++) {
                Horror.later(1 + i * 30, () -> sound(p, SoundEvents.ENTITY_HORSE_BREATHE, Horror.behind(p, 0.8), 0.8f, 0.4f));
            }
            return true;
        });
        add("cave", 70, 8, (p, w, r) -> {
            Horror.sound(p, SoundEvents.AMBIENT_CAVE, randomAround(p, r, 8), 1.0f, 0.8f);
            return true;
        });
        add("far_scream", 60, 6, (p, w, r) -> {
            sound(p, SoundEvents.ENTITY_GHAST_SCREAM, randomAround(p, r, 30), 3.0f, 0.5f);
            return true;
        });
        add("own_hurt", 50, 5, (p, w, r) -> {
            sound(p, SoundEvents.ENTITY_PLAYER_HURT, p.getPos(), 1.0f, 1.0f);
            return true;
        });
        add("eating", 60, 5, (p, w, r) -> {
            for (int i = 0; i < 5; i++) {
                Horror.later(1 + i * 5, () -> sound(p, SoundEvents.ENTITY_GENERIC_EAT, Horror.behind(p, 2), 0.8f, 0.7f));
            }
            return true;
        });
        add("chest", 70, 6, (p, w, r) -> {
            List<BlockPos> chests = find(w, p.getBlockPos(), 10, 1, s -> s.isOf(Blocks.CHEST) || s.isOf(Blocks.BARREL));
            Vec3d at = chests.isEmpty() ? randomAround(p, r, 5) : chests.get(0).toCenterPos();
            sound(p, SoundEvents.BLOCK_CHEST_OPEN, at, 1.0f, 0.8f);
            Horror.later(40, () -> sound(p, SoundEvents.BLOCK_CHEST_CLOSE, at, 1.0f, 0.8f));
            return true;
        });
        add("tnt", 40, 4, (p, w, r) -> {
            sound(p, SoundEvents.ENTITY_TNT_PRIMED, Horror.behind(p, 3), 1.0f, 1.0f);
            return true;
        });
        add("digging", 60, 6, (p, w, r) -> {
            for (int i = 0; i < 6; i++) {
                Horror.later(1 + i * 12, () -> sound(p, SoundEvents.BLOCK_STONE_BREAK, p.getPos().add(0, -4, 0), 1.0f, 0.7f));
            }
            return true;
        });
        add("bell", 60, 4, (p, w, r) -> {
            Vec3d at = randomAround(p, r, 40);
            for (int i = 0; i < 3; i++) Horror.later(1 + i * 30, () -> sound(p, SoundEvents.BLOCK_BELL_USE, at, 4.0f, 0.5f));
            return true;
        });
        add("heartbeat", 50, 7, (p, w, r) -> {
            for (int i = 0; i < 4; i++) Horror.later(1 + i * 15, () -> sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, p.getEyePos(), 1.0f, 1.0f));
            return true;
        });
        add("glass", 60, 5, (p, w, r) -> {
            sound(p, SoundEvents.BLOCK_GLASS_BREAK, randomAround(p, r, 8), 1.0f, 0.8f);
            return true;
        });
        add("villager_cry", 60, 4, (p, w, r) -> {
            sound(p, SoundEvents.ENTITY_VILLAGER_HURT, randomAround(p, r, 20), 2.0f, 0.6f);
            return true;
        });
        add("laughter", 50, 6, (p, w, r) -> {
            sound(p, SoundEvents.ENTITY_WITCH_CELEBRATE, Horror.behind(p, 3), 1.0f, 0.6f);
            return true;
        });
        add("music_11", 40, 3, (p, w, r) -> {
            Vec3d at = randomAround(p, r, 12);
            sound(p, SoundEvents.MUSIC_DISC_11, at, 1.0f, 1.0f);
            Horror.later(160, () -> p.networkHandler.sendPacket(new StopSoundS2CPacket(SoundEvents.MUSIC_DISC_11.getId(), SoundCategory.HOSTILE)));
            return true;
        });
        add("name_call", 50, 6, (p, w, r) -> {
            chat(p, Text.literal("...").append(p.getName()).append("...").formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
            sound(p, ModSounds.WHISPER_AMBIENT, Horror.behind(p, 1), 1.0f, 1.1f);
            return true;
        });

        // ----- видимое (рассудок до 50) -----
        add("torches_out", 50, 8, (p, w, r) -> {
            List<BlockPos> lights = find(w, p.getBlockPos(), 10, 40,
                    s -> s.getBlock() instanceof TorchBlock || s.getBlock() instanceof LanternBlock);
            if (lights.isEmpty()) return false;
            for (BlockPos pos : lights) Horror.fakeBlock(p, pos, Blocks.AIR.getDefaultState(), 60 + r.nextInt(20));
            sound(p, SoundEvents.BLOCK_FIRE_EXTINGUISH, p.getEyePos(), 1.0f, 0.6f);
            return true;
        });
        add("pillar_behind", 50, 6, (p, w, r) -> {
            BlockPos base = Horror.ground(w, Horror.behind(p, 3), 4);
            if (base == null) return false;
            for (int y = 0; y < 3; y++) Horror.fakeBlock(p, base.up(y), Blocks.COBBLESTONE.getDefaultState(), 100);
            return true;
        });
        add("door_toggle", 50, 6, (p, w, r) -> {
            List<BlockPos> doors = find(w, p.getBlockPos(), 10, 1,
                    s -> s.getBlock() instanceof DoorBlock && s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER);
            if (doors.isEmpty()) return false;
            BlockPos lower = doors.get(0);
            BlockState state = w.getBlockState(lower);
            boolean open = !state.get(DoorBlock.OPEN);
            Horror.fakeBlock(p, lower, state.with(DoorBlock.OPEN, open), 60);
            Horror.fakeBlock(p, lower.up(), w.getBlockState(lower.up()).with(DoorBlock.OPEN, open), 60);
            sound(p, open ? SoundEvents.BLOCK_WOODEN_DOOR_OPEN : SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, lower.toCenterPos(), 1.0f, 0.7f);
            return true;
        });
        add("sign", 40, 5, (p, w, r) -> {
            BlockPos pos = Horror.ground(w, Horror.around(p, 0, 4).add(0, 1, 0), 3);
            if (pos == null) return false;
            BlockState state = Blocks.OAK_SIGN.getDefaultState()
                    .with(SignBlock.ROTATION, RotationPropertyHelper.fromYaw(p.getYaw() + 180f));
            SignBlockEntity sign = new SignBlockEntity(pos, state);
            sign.setText(new SignText().withMessage(1, line("somnium.event.sign", r, 4).copy().formatted(Formatting.DARK_RED)), true);
            Horror.fakeBlock(p, pos, state, 200);
            p.networkHandler.sendPacket(BlockEntityUpdateS2CPacket.create(sign));
            return true;
        });
        add("blood_trail", 50, 5, (p, w, r) -> {
            double angle = r.nextDouble() * 360;
            for (int i = 0; i < 14; i++) {
                int step = i;
                Horror.later(1 + i * 2, () -> personalParticle(p, BLOOD, Horror.around(p, angle, 1 + step).add(0, 0.1, 0), 3, 0.1));
            }
            return true;
        });
        add("eyes_in_dark", 45, 7, (p, w, r) -> {
            Vec3d at = Horror.around(p, (r.nextDouble() - 0.5) * 120, 10 + r.nextInt(5)).add(0, 1.6, 0);
            if (w.getLightLevel(BlockPos.ofFloored(at)) > 7) return false;
            Vec3d side = new Vec3d(at.z - p.getZ(), 0, p.getX() - at.x).normalize().multiply(0.18);
            DustParticleEffect eye = r.nextBoolean() ? EYE_WHITE : EYE_RED;
            for (int i = 0; i < 12; i++) {
                Horror.later(1 + i * 5, () -> {
                    personalParticle(p, eye, at.add(side), 1, 0);
                    personalParticle(p, eye, at.subtract(side), 1, 0);
                });
            }
            return true;
        });
        add("fake_creeper", 40, 4, (p, w, r) -> {
            BlockPos pos = Horror.ground(w, Horror.behind(p, 3), 3);
            if (pos == null) return false;
            CreeperEntity creeper = spawnStill(w, EntityType.CREEPER, pos, p);
            if (creeper == null) return false;
            sound(p, SoundEvents.ENTITY_CREEPER_PRIMED, pos.toCenterPos(), 1.0f, 1.0f);
            Horror.later(30, creeper::discard);
            return true;
        });
        add("stranger", 40, 6, (p, w, r) -> {
            BlockPos pos = Horror.ground(w, Horror.around(p, (r.nextDouble() - 0.5) * 100, 14).add(0, 1, 0), 6);
            if (pos == null) return false;
            PassengerEntity stranger = spawnStill(w, ModEntities.PASSENGER, pos, p);
            if (stranger == null) return false;
            stranger.setVariant(1);
            for (int i = 1; i <= 20; i++) {
                Horror.later(i * 10, () -> {
                    if (stranger.isRemoved()) return;
                    Horror.face(stranger, p.getEyePos());
                    if (stranger.squaredDistanceTo(p) < 64) stranger.discard();
                });
            }
            Horror.later(205, stranger::discard);
            return true;
        });
        add("thin_glimpse", 35, 6, (p, w, r) -> {
            BlockPos pos = Horror.ground(w, Horror.around(p, (r.nextDouble() - 0.5) * 90, 26 + r.nextInt(10)).add(0, 1, 0), 10);
            if (pos == null) return false;
            ThinManEntity thin = spawnStill(w, ModEntities.THIN_MAN, pos, p);
            if (thin == null) return false;
            thin.setAiDisabled(false);
            thin.glimpse(80);
            return true;
        });
        add("shadow_runner", 45, 5, (p, w, r) -> {
            Vec3d from = Horror.around(p, -50, 12).add(0, 1, 0);
            Vec3d to = Horror.around(p, 50, 12).add(0, 1, 0);
            BlockPos start = Horror.ground(w, from, 4);
            if (start == null) return false;
            LurkingShadeEntity shade = spawnStill(w, ModEntities.LURKING_SHADE, start, p);
            if (shade == null) return false;
            for (int i = 1; i <= 15; i++) {
                double t = i / 15.0;
                Horror.later(i, () -> {
                    Vec3d at = from.lerp(to, t);
                    shade.refreshPositionAndAngles(at.x, shade.getY(), at.z, shade.getYaw(), 0f);
                });
            }
            Horror.later(17, shade::discard);
            return true;
        });
        add("nausea", 40, 5, (p, w, r) -> {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, false, false, false));
            return true;
        });
        add("darkness_pulse", 45, 6, (p, w, r) -> {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0, false, false, false));
            sound(p, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, p.getEyePos(), 0.8f, 0.6f);
            return true;
        });
        add("turn_around", 25, 4, (p, w, r) -> {
            Vec3d back = Horror.behind(p, 5);
            Horror.lookAt(p, back);
            sound(p, ModSounds.WHISPER_AMBIENT, back, 1.0f, 0.8f);
            return true;
        });
        add("look_up", 30, 3, (p, w, r) -> {
            Horror.lookAt(p, p.getEyePos().add(0, 10, 0.01));
            sound(p, SoundEvents.ENTITY_PHANTOM_SWOOP, p.getEyePos().add(0, 4, 0), 1.0f, 0.5f);
            return true;
        });
        add("hotbar_shift", 50, 5, (p, w, r) -> {
            int slot = r.nextInt(9);
            p.getInventory().selectedSlot = slot;
            p.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(slot));
            return true;
        });
        add("hotbar_swap", 35, 3, (p, w, r) -> {
            var inv = p.getInventory();
            int a = r.nextInt(9);
            int b = r.nextInt(9);
            if (a == b) return false;
            ItemStack tmp = inv.getStack(a);
            inv.setStack(a, inv.getStack(b));
            inv.setStack(b, tmp);
            return true;
        });

        // ----- чат и экран -----
        add("fake_advancement", 50, 4, (p, w, r) -> {
            chat(p, Text.translatable("chat.type.advancement.task", p.getDisplayName(),
                    Text.translatable("somnium.event.advancement").formatted(Formatting.GREEN)));
            return true;
        });
        add("fake_join", 40, 4, (p, w, r) -> {
            chat(p, Text.translatable("multiplayer.player.joined", p.getDisplayName()).formatted(Formatting.YELLOW));
            return true;
        });
        add("fake_death", 30, 3, (p, w, r) -> {
            chat(p, Text.translatable("death.attack.mob", p.getDisplayName(), Text.translatable("entity.somnium.thin_man")));
            return true;
        });
        add("whisper_chat", 45, 7, (p, w, r) -> {
            chat(p, Text.literal("<").append(Text.literal("?").formatted(Formatting.OBFUSCATED)).append("> ")
                    .append(line("somnium.event.chat", r, 8)).formatted(Formatting.GRAY));
            return true;
        });
        add("title_flash", 30, 5, (p, w, r) -> {
            DreamFx.title(p, line("somnium.event.title", r, 6).copy().formatted(Formatting.DARK_RED, Formatting.BOLD), null, 0, 8, 4);
            sound(p, ModSounds.RUPTURE_STINGER, p.getEyePos(), 0.8f, 1.4f);
            return true;
        });
        add("screamer", 15, 4, (p, w, r) -> {
            Horror.screamer(p, r.nextInt(Horror.FACES));
            SanityManager.add(p, -2.0f);
            return true;
        });
        add("silent_lightning", 50, 4, (p, w, r) -> {
            BlockPos pos = Horror.ground(w, randomAround(p, r, 28), 16);
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
            if (pos == null || bolt == null) return false;
            bolt.refreshPositionAfterTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            bolt.setCosmetic(true);
            w.spawnEntity(bolt);
            return true;
        });
        add("sudden_night", 40, 4, (p, w, r) -> {
            for (int i = 0; i < 100; i++) Horror.later(1 + i, () -> DreamFx.sendTime(p, 18000L));
            Horror.later(102, () -> p.networkHandler.sendPacket(new WorldTimeUpdateS2CPacket(w.getTime(), w.getTimeOfDay(),
                    w.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE))));
            return true;
        });
        add("phantom_rain", 50, 4, (p, w, r) -> {
            DreamFx.sendWeather(p, 1.0f, 0.8f);
            Horror.later(200, () -> DreamFx.sendWeather(p, w.getRainGradient(1.0f), w.getThunderGradient(1.0f)));
            return true;
        });
        add("ash", 45, 5, (p, w, r) -> {
            for (int i = 0; i < 20; i++) Horror.later(1 + i * 10, () -> personalParticle(p, ParticleTypes.ASH, p.getEyePos(), 40, 4));
            return true;
        });
        add("cold_touch", 40, 5, (p, w, r) -> {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1, false, false, false));
            personalParticle(p, ParticleTypes.SNOWFLAKE, p.getEyePos(), 20, 0.4);
            p.sendMessage(Text.translatable("somnium.event.cold").formatted(Formatting.AQUA, Formatting.ITALIC), true);
            return true;
        });
        add("sleep_paralysis", 20, 4, (p, w, r) -> {
            BlockPos pos = Horror.ground(w, Horror.around(p, 0, 3).add(0, 1, 0), 3);
            if (pos == null) return false;
            PassengerEntity her = spawnStill(w, ModEntities.PASSENGER, pos, p);
            if (her == null) return false;
            her.setVariant(0);
            Horror.cutscene(p, 70, () -> her.getEyePos());
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 80, 0, false, false, false));
            sound(p, ModSounds.WHISPER_AMBIENT, her.getEyePos(), 1.2f, 0.7f);
            Horror.later(40, () -> {
                if (!her.isRemoved()) her.refreshPositionAndAngles(Horror.around(p, 0, 1.3).x, p.getY(), Horror.around(p, 0, 1.3).z, 0f, 0f);
                Horror.face(her, p.getEyePos());
            });
            Horror.later(55, () -> Horror.screamer(p, Horror.FACE_PALE));
            Horror.later(60, her::discard);
            SanityManager.add(p, -3.0f);
            return true;
        });
        add("fake_ore", 50, 4, (p, w, r) -> {
            List<BlockPos> stones = find(w, p.getBlockPos(), 6, 30, s -> s.isOf(Blocks.STONE) || s.isOf(Blocks.DEEPSLATE));
            for (BlockPos pos : stones) {
                for (Direction dir : Direction.values()) {
                    if (w.getBlockState(pos.offset(dir)).isAir()) {
                        Horror.fakeBlock(p, pos, Blocks.DIAMOND_ORE.getDefaultState(), 160);
                        return true;
                    }
                }
            }
            return false;
        });
        add("mob_stare", 50, 6, (p, w, r) -> {
            List<MobEntity> mobs = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(16),
                    m -> m instanceof PassiveEntity && m.isAlive());
            if (mobs.isEmpty()) return false;
            for (int i = 0; i < 40; i++) {
                Horror.later(1 + i * 2, () -> {
                    for (MobEntity mob : mobs) {
                        if (mob.isRemoved()) continue;
                        mob.getNavigation().stop();
                        mob.getLookControl().lookAt(p, 90f, 90f);
                    }
                });
            }
            return true;
        });
    }
}
