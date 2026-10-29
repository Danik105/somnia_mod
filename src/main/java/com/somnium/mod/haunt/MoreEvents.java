package com.somnium.mod.haunt;

import com.somnium.mod.dream.DreamFx;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.BatEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Ещё около пятидесяти событий: звуки зверей и чудовищ, призрак стража на весь экран,
 * ложный тотем, наблюдатели вдали, мир вокруг "сбоит" (вода становится лавой, листва
 * исчезает, факелы синеют, таблички зовут на помощь). Всё видит только сам игрок.
 */
final class MoreEvents {

    private MoreEvents() {}

    private static void add(String id, float maxSanity, int weight, WorldEvents.Action action) {
        WorldEvents.add(id, maxSanity, weight, action);
    }

    private static WorldEvents.Action behindSound(SoundEvent sound, double dist, float volume, float pitch, int repeats, int gap) {
        return (p, w, r) -> {
            for (int i = 0; i < repeats; i++) {
                Horror.later(1 + i * gap, () -> Horror.sound(p, sound, Horror.behind(p, dist), volume, pitch));
            }
            return true;
        };
    }

    private static WorldEvents.Action farSound(SoundEvent sound, double dist, float volume, float pitch) {
        return (p, w, r) -> {
            Horror.sound(p, sound, Horror.around(p, r.nextDouble() * 360, dist).add(0, 1, 0), volume, pitch);
            return true;
        };
    }

    private static List<BlockPos> find(ServerWorld w, BlockPos center, int radius, int limit, Predicate<BlockState> test) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterateOutwards(center, radius, radius / 2 + 1, radius)) {
            if (test.test(w.getBlockState(pos))) {
                found.add(pos.toImmutable());
                if (found.size() >= limit) break;
            }
        }
        return found;
    }

    /** Неподвижный наблюдатель на расстоянии: смотрит на игрока, исчезает, если долго смотреть в ответ. */
    private static <T extends MobEntity> WorldEvents.Action watcher(EntityType<T> type, double dist, int count) {
        return (p, w, r) -> {
            List<MobEntity> spawned = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                BlockPos pos = Horror.ground(w, Horror.around(p, (r.nextDouble() - 0.5) * 80, dist + r.nextInt(5)).add(0, 1, 0), 10);
                if (pos == null) continue;
                T mob = type.create(w);
                if (mob == null) continue;
                mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                mob.setAiDisabled(true);
                mob.setSilent(true);
                mob.setInvulnerable(true);
                mob.addCommandTag("somnium_haunt");
                Horror.vision(mob);
                Horror.face(mob, p.getEyePos());
                w.spawnEntity(mob);
                spawned.add(mob);
            }
            if (spawned.isEmpty()) return false;
            int[] watched = {0};
            for (int t = 1; t <= 40; t++) {
                Horror.later(t * 5, () -> {
                    for (MobEntity mob : spawned) {
                        if (mob.isRemoved()) continue;
                        Horror.face(mob, p.getEyePos());
                        if (Horror.isLookingAt(p, mob, 0.95) && ++watched[0] > 6) mob.discard();
                    }
                });
            }
            Horror.later(205, () -> spawned.forEach(MobEntity::discard));
            return true;
        };
    }

    static void registerAll() {
        // ----- звуки -----
        add("enderman_scream", 40, 5, behindSound(SoundEvents.ENTITY_ENDERMAN_SCREAM, 2, 1.0f, 0.6f, 1, 1));
        add("warden_roar", 30, 3, farSound(SoundEvents.ENTITY_WARDEN_ROAR, 30, 3.0f, 0.7f));
        add("ravager_roar", 40, 4, farSound(SoundEvents.ENTITY_RAVAGER_ROAR, 25, 3.0f, 0.6f));
        add("goat_scream", 50, 4, farSound(SoundEvents.ENTITY_GOAT_SCREAMING_AMBIENT, 20, 2.0f, 0.6f));
        add("fox_screech", 50, 5, behindSound(SoundEvents.ENTITY_FOX_SCREECH, 4, 1.0f, 0.6f, 1, 1));
        add("cat_hiss", 50, 5, behindSound(SoundEvents.ENTITY_CAT_HISS, 1.5, 1.0f, 0.8f, 1, 1));
        add("wolf_growl", 45, 5, behindSound(SoundEvents.ENTITY_WOLF_GROWL, 2, 1.0f, 0.6f, 2, 25));
        add("spider_walk", 45, 5, behindSound(SoundEvents.ENTITY_SPIDER_STEP, 2, 1.0f, 0.7f, 6, 5));
        add("bones_rattle", 50, 5, behindSound(SoundEvents.ENTITY_SKELETON_AMBIENT, 3, 1.0f, 0.6f, 2, 20));
        add("groan", 50, 5, behindSound(SoundEvents.ENTITY_ZOMBIE_AMBIENT, 2, 1.0f, 0.5f, 1, 1));
        add("vex_charge", 35, 4, behindSound(SoundEvents.ENTITY_VEX_CHARGE, 1.5, 1.0f, 0.8f, 3, 6));
        add("evoker_cast", 40, 4, behindSound(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 4, 1.0f, 0.7f, 1, 1));
        add("phantom_flap", 45, 5, (p, w, r) -> {
            for (int i = 0; i < 4; i++) Horror.later(1 + i * 8, () -> Horror.sound(p, SoundEvents.ENTITY_PHANTOM_FLAP, p.getEyePos().add(0, 4, 0), 1.0f, 0.6f));
            return true;
        });
        add("anvil_drop", 45, 4, behindSound(SoundEvents.BLOCK_ANVIL_LAND, 3, 1.0f, 0.7f, 1, 1));
        add("thunder_far", 60, 5, farSound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 60, 4.0f, 0.6f));
        add("music_13", 40, 3, (p, w, r) -> {
            Horror.sound(p, SoundEvents.MUSIC_DISC_13, Horror.around(p, r.nextDouble() * 360, 12), 1.0f, 1.0f);
            Horror.later(200, () -> p.networkHandler.sendPacket(new StopSoundS2CPacket(SoundEvents.MUSIC_DISC_13.getId(), SoundCategory.HOSTILE)));
            return true;
        });
        add("bass_melody", 50, 4, (p, w, r) -> {
            float[] notes = {0.8f, 0.7f, 0.63f, 0.6f, 0.5f};
            Vec3d at = Horror.behind(p, 5);
            for (int i = 0; i < notes.length; i++) {
                float pitch = notes[i];
                Horror.later(1 + i * 10, () -> Horror.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BASS, at, 1.5f, pitch));
            }
            return true;
        });
        add("wings_above", 45, 4, (p, w, r) -> {
            Horror.sound(p, SoundEvents.ITEM_ELYTRA_FLYING, p.getEyePos().add(0, 6, 0), 0.8f, 0.6f);
            Horror.later(60, () -> p.networkHandler.sendPacket(new StopSoundS2CPacket(SoundEvents.ITEM_ELYTRA_FLYING.getId(), SoundCategory.HOSTILE)));
            return true;
        });
        add("pot_shatter", 50, 4, behindSound(SoundEvents.BLOCK_DECORATED_POT_SHATTER, 3, 1.0f, 0.8f, 1, 1));
        add("bubbles_ear", 45, 4, behindSound(SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.6, 1.0f, 0.6f, 2, 20));
        add("levelup_wrong", 50, 3, behindSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 2, 1.0f, 0.4f, 1, 1));
        add("lock_click", 50, 4, behindSound(SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 2, 1.0f, 0.5f, 2, 8));

        // ----- экран и тело -----
        add("elder_ghost", 35, 4, (p, w, r) -> {
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.ELDER_GUARDIAN_EFFECT, 1.0f));
            return true;
        });
        add("fake_totem", 40, 3, (p, w, r) -> {
            p.networkHandler.sendPacket(new EntityStatusS2CPacket(p, (byte) 35));
            return true;
        });
        add("phantom_hit", 45, 5, (p, w, r) -> {
            p.networkHandler.sendPacket(new DamageTiltS2CPacket(p.getId(), r.nextFloat() * 360f - 180f));
            Horror.sound(p, SoundEvents.ENTITY_PLAYER_HURT, p.getPos(), 1.0f, 1.0f);
            return true;
        });
        add("shake", 30, 3, (p, w, r) -> {
            for (int i = 0; i < 6; i++) {
                Horror.later(1 + i * 3, () -> p.networkHandler.sendPacket(new DamageTiltS2CPacket(p.getId(), r.nextFloat() * 360f - 180f)));
            }
            Horror.sound(p, SoundEvents.ENTITY_GENERIC_EXPLODE, p.getPos().add(0, -6, 0), 1.0f, 0.4f);
            return true;
        });
        add("levitate", 35, 3, (p, w, r) -> {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 20, 0, false, false, false));
            p.sendMessage(Text.translatable("somnium.event.lifted").formatted(Formatting.GRAY, Formatting.ITALIC), true);
            return true;
        });
        add("hunger_pang", 50, 4, (p, w, r) -> {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 80, 1, false, false, false));
            Horror.sound(p, SoundEvents.ENTITY_GENERIC_EAT, Horror.behind(p, 1), 1.0f, 0.5f);
            return true;
        });
        add("blink", 30, 4, (p, w, r) -> {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 15, 0, false, false, false));
            return true;
        });
        add("self_chat", 40, 4, (p, w, r) -> {
            p.sendMessage(Text.literal("<").append(p.getDisplayName()).append("> ")
                    .append(Text.translatable("somnium.event.self." + (1 + r.nextInt(4)))), false);
            return true;
        });

        // ----- наблюдатели -----
        add("enderman_watch", 40, 5, watcher(EntityType.ENDERMAN, 20, 1));
        add("cat_watch", 50, 4, watcher(EntityType.CAT, 8, 1));
        add("villager_watch", 45, 5, watcher(EntityType.VILLAGER, 12, 1));
        add("wolf_pack", 40, 4, watcher(EntityType.WOLF, 18, 3));
        add("golem_watch", 45, 3, watcher(EntityType.IRON_GOLEM, 22, 1));
        add("witch_watch", 35, 3, watcher(EntityType.WITCH, 16, 1));
        add("skeleton_watch", 40, 4, watcher(EntityType.SKELETON, 15, 2));
        add("bat_swarm", 45, 4, (p, w, r) -> {
            List<BatEntity> bats = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                BatEntity bat = EntityType.BAT.create(w);
                if (bat == null) continue;
                Vec3d at = p.getEyePos().add(r.nextDouble() * 2 - 1, r.nextDouble(), r.nextDouble() * 2 - 1);
                bat.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
                Horror.vision(bat);
                w.spawnEntity(bat);
                bats.add(bat);
            }
            Horror.later(60, () -> bats.forEach(BatEntity::discard));
            return !bats.isEmpty();
        });
        add("armor_stand", 40, 4, (p, w, r) -> {
            BlockPos pos = Horror.ground(w, Horror.behind(p, 3), 4);
            if (pos == null) return false;
            ArmorStandEntity stand = new ArmorStandEntity(w, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            Horror.face(stand, p.getEyePos());
            Horror.vision(stand);
            w.spawnEntity(stand);
            int[] watched = {0};
            for (int t = 1; t <= 60; t++) {
                Horror.later(t * 5, () -> {
                    if (stand.isRemoved()) return;
                    if (Horror.isLookingAt(p, stand, 0.9) && ++watched[0] > 4) {
                        Horror.sound(p, SoundEvents.ENTITY_ARMOR_STAND_BREAK, stand.getPos(), 1.0f, 0.6f);
                        stand.discard();
                    }
                });
            }
            Horror.later(305, stand::discard);
            return true;
        });
        add("chicken_follow", 50, 3, (p, w, r) -> {
            MobEntity chicken = EntityType.CHICKEN.create(w);
            if (chicken == null) return false;
            chicken.setAiDisabled(true);
            chicken.setSilent(true);
            chicken.setInvulnerable(true);
            Horror.vision(chicken);
            Vec3d at = Horror.behind(p, 3);
            chicken.refreshPositionAndAngles(at.x, p.getY(), at.z, 0, 0);
            w.spawnEntity(chicken);
            for (int t = 1; t <= 20; t++) {
                Horror.later(t * 10, () -> {
                    if (chicken.isRemoved()) return;
                    Vec3d back = Horror.behind(p, 3);
                    chicken.refreshPositionAndAngles(back.x, p.getY(), back.z, 0, 0);
                    Horror.face(chicken, p.getEyePos());
                });
            }
            Horror.later(205, chicken::discard);
            return true;
        });

        // ----- мир сбоит (ложные блоки) -----
        add("soul_torches", 45, 5, (p, w, r) -> {
            List<BlockPos> torches = find(w, p.getBlockPos(), 10, 40, s -> s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH));
            if (torches.isEmpty()) return false;
            for (BlockPos pos : torches) {
                BlockState s = w.getBlockState(pos);
                BlockState soul = s.isOf(Blocks.WALL_TORCH)
                        ? Blocks.SOUL_WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, s.get(WallTorchBlock.FACING))
                        : Blocks.SOUL_TORCH.getDefaultState();
                Horror.fakeBlock(p, pos, soul, 200);
            }
            return true;
        });
        add("soul_ground", 40, 4, (p, w, r) -> {
            List<BlockPos> ground = find(w, p.getBlockPos().down(), 4, 60, s -> s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT));
            if (ground.isEmpty()) return false;
            for (BlockPos pos : ground) Horror.fakeBlock(p, pos, Blocks.SOUL_SOIL.getDefaultState(), 200);
            Horror.sound(p, SoundEvents.BLOCK_SOUL_SOIL_STEP, p.getPos(), 1.0f, 0.6f);
            return true;
        });
        add("leaves_vanish", 45, 4, (p, w, r) -> {
            List<BlockPos> leaves = find(w, p.getBlockPos().up(3), 8, 120, s -> s.getBlock() instanceof LeavesBlock);
            if (leaves.isEmpty()) return false;
            for (BlockPos pos : leaves) Horror.fakeBlock(p, pos, Blocks.AIR.getDefaultState(), 100);
            Horror.sound(p, SoundEvents.BLOCK_GRASS_BREAK, p.getEyePos().add(0, 3, 0), 1.0f, 0.5f);
            return true;
        });
        add("water_to_lava", 35, 4, (p, w, r) -> {
            List<BlockPos> water = find(w, p.getBlockPos(), 10, 80, s -> s.getFluidState().isOf(Fluids.WATER) && s.isOf(Blocks.WATER));
            if (water.isEmpty()) return false;
            for (BlockPos pos : water) Horror.fakeBlock(p, pos, Blocks.LAVA.getDefaultState(), 100);
            Horror.sound(p, SoundEvents.BLOCK_LAVA_AMBIENT, p.getPos(), 1.0f, 0.7f);
            return true;
        });
        add("chests_open", 45, 5, (p, w, r) -> {
            List<BlockPos> chests = find(w, p.getBlockPos(), 10, 6, s -> s.getBlock() instanceof ChestBlock);
            if (chests.isEmpty()) return false;
            for (BlockPos pos : chests) {
                Block block = w.getBlockState(pos).getBlock();
                p.networkHandler.sendPacket(new BlockEventS2CPacket(pos, block, 1, 1));
                Horror.sound(p, SoundEvents.BLOCK_CHEST_OPEN, pos.toCenterPos(), 1.0f, 0.7f);
                Horror.later(60, () -> {
                    p.networkHandler.sendPacket(new BlockEventS2CPacket(pos, block, 1, 0));
                    Horror.sound(p, SoundEvents.BLOCK_CHEST_CLOSE, pos.toCenterPos(), 1.0f, 0.7f);
                });
            }
            return true;
        });
        add("signs_help", 40, 4, (p, w, r) -> {
            List<BlockPos> signs = find(w, p.getBlockPos(), 12, 10, s -> s.isIn(BlockTags.ALL_SIGNS));
            if (signs.isEmpty()) return false;
            for (BlockPos pos : signs) {
                if (!(w.getBlockEntity(pos) instanceof SignBlockEntity real)) continue;
                SignBlockEntity fake = new SignBlockEntity(pos, w.getBlockState(pos));
                SignText text = new SignText().withMessage(1, Text.translatable("somnium.event.help").formatted(Formatting.DARK_RED));
                fake.setText(text, true);
                fake.setText(text, false);
                p.networkHandler.sendPacket(BlockEntityUpdateS2CPacket.create(fake));
                Horror.later(200, () -> p.networkHandler.sendPacket(BlockEntityUpdateS2CPacket.create(real)));
            }
            return true;
        });
        add("doors_open", 45, 4, (p, w, r) -> {
            List<BlockPos> doors = find(w, p.getBlockPos(), 12, 8,
                    s -> s.getBlock() instanceof DoorBlock && s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !s.get(DoorBlock.OPEN));
            if (doors.isEmpty()) return false;
            for (BlockPos pos : doors) {
                Horror.fakeBlock(p, pos, w.getBlockState(pos).with(DoorBlock.OPEN, true), 100);
                Horror.fakeBlock(p, pos.up(), w.getBlockState(pos.up()).with(DoorBlock.OPEN, true), 100);
            }
            Horror.sound(p, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, doors.get(0).toCenterPos(), 1.0f, 0.6f);
            return true;
        });
        add("glitch", 35, 4, (p, w, r) -> {
            List<BlockPos> solid = find(w, p.getBlockPos(), 6, 200, s -> s.isOpaqueFullCube(w, BlockPos.ORIGIN));
            if (solid.isEmpty()) return false;
            BlockState[] odd = {Blocks.NETHERRACK.getDefaultState(), Blocks.OBSIDIAN.getDefaultState(),
                    Blocks.REDSTONE_BLOCK.getDefaultState(), Blocks.SCULK.getDefaultState()};
            for (int i = 0; i < 12; i++) {
                BlockPos pos = solid.get(r.nextInt(solid.size()));
                Horror.fakeBlock(p, pos, odd[r.nextInt(odd.length)], 10 + r.nextInt(20));
            }
            Horror.sound(p, SoundEvents.BLOCK_SCULK_SENSOR_CLICKING, p.getEyePos(), 1.0f, 0.5f);
            return true;
        });
        add("cracked_floor", 35, 4, (p, w, r) -> {
            List<BlockPos> floor = find(w, p.getBlockPos().down(), 3, 30, s -> s.isOpaqueFullCube(w, BlockPos.ORIGIN));
            if (floor.isEmpty()) return false;
            for (BlockPos pos : floor) Horror.fakeBlock(p, pos, Blocks.CRACKED_DEEPSLATE_TILES.getDefaultState(), 120);
            Horror.sound(p, SoundEvents.BLOCK_DEEPSLATE_BREAK, p.getPos(), 1.0f, 0.5f);
            return true;
        });
        add("ores_bleed", 40, 4, (p, w, r) -> {
            List<BlockPos> ores = find(w, p.getBlockPos(), 8, 40, s -> s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES));
            if (ores.isEmpty()) return false;
            for (BlockPos pos : ores) Horror.fakeBlock(p, pos, Blocks.REDSTONE_ORE.getDefaultState()
                    .with(net.minecraft.block.RedstoneOreBlock.LIT, true), 160);
            return true;
        });
        add("bed_red", 40, 3, (p, w, r) -> {
            List<BlockPos> beds = find(w, p.getBlockPos(), 12, 4, s -> s.isIn(BlockTags.BEDS));
            if (beds.isEmpty()) return false;
            for (BlockPos pos : beds) {
                BlockState s = w.getBlockState(pos);
                BlockState red = Blocks.RED_BED.getDefaultState()
                        .with(net.minecraft.block.BedBlock.FACING, s.get(net.minecraft.block.BedBlock.FACING))
                        .with(net.minecraft.block.BedBlock.PART, s.get(net.minecraft.block.BedBlock.PART));
                Horror.fakeBlock(p, pos, red, 200);
            }
            Horror.sound(p, com.somnium.mod.registry.ModSounds.WHISPER_AMBIENT, beds.get(0).toCenterPos(), 1.0f, 0.7f);
            return true;
        });
        add("night_title", 30, 3, (p, w, r) -> {
            DreamFx.title(p, Text.translatable("somnium.event.night").formatted(Formatting.DARK_RED, Formatting.ITALIC), null, 5, 30, 10);
            return true;
        });
    }
}
