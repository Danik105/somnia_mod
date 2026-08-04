package com.somnium.mod.entity;

import com.somnium.mod.dream.DreamManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * ДОБАВЛЕНО (мультиплеер, "видел как второй всё ещё спит — моделька которая лежит"):
 * физическое тело игрока, лежащее в кровати в реальном мире, пока сам игрок находится
 * в измерении сна. Спавнится DreamManager#spawnSleepingBody при входе в сон и убирается
 * при пробуждении (DreamManager#despawnSleepingBody).
 *
 * Свойства:
 *  - неуязвима (но creative-игрок при желании может её убить — аварийная очистка);
 *  - не толкается и не двигается (NoAI по сути: AI нет уже на уровне LivingEntity);
 *  - хранит UUID владельца в DataTracker (синхронизируется клиенту для рендера скина);
 *  - самоочистка: если владелец вышел с сервера или уже не во сне (перезапуск сервера,
 *    потерянный discard), тело удаляет себя само через короткий грейс-период.
 */
public class SleepingBodyEntity extends LivingEntity {

    /** UUID игрока-владельца (строкой, чтобы DataTracker умел его синхронизировать). */
    private static final TrackedData<String> OWNER_UUID =
            DataTracker.registerData(SleepingBodyEntity.class, TrackedDataHandlerRegistry.STRING);

    /** Грейс-период до начала самоочистки (тиков) — защита от гонки при спавне. */
    private static final int SELF_CLEAN_GRACE_TICKS = 100;

    public SleepingBodyEntity(EntityType<? extends SleepingBodyEntity> type, World world) {
        super(type, world);
        this.setInvulnerable(true);
        this.setSilent(true);
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(OWNER_UUID, "");
    }

    public void setOwnerUuid(UUID uuid) {
        this.dataTracker.set(OWNER_UUID, uuid.toString());
    }

    public Optional<UUID> getOwnerUuid() {
        String raw = this.dataTracker.get(OWNER_UUID);
        if (raw == null || raw.isEmpty()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static DefaultAttributeContainer.Builder createSleepingBodyAttributes() {
        return LivingEntity.createLivingAttributes();
    }

    // --- "Пустое" тело: без брони, без предметов, без основной руки ---

    @Override
    public Iterable<ItemStack> getArmorItems() {
        return Collections.emptyList();
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {
        // no-op: телу нечего экипировать
    }

    @Override
    public Arm getMainArm() {
        return Arm.RIGHT;
    }

    // --- Неподвижность: тело нельзя сдвинуть с кровати ---

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void pushAway(Entity entity) {
        // no-op: не отталкиваем других
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) return;

        // Самоочистка: раз в 2 секунды после грейс-периода проверяем, что владелец
        // всё ещё на сервере и всё ещё во сне. Иначе тело удаляем — оно не должно
        // остаться "брошенным" в мире при перезапуске сервера или потерянном wake().
        if (this.age > SELF_CLEAN_GRACE_TICKS && this.age % 40 == 0) {
            Optional<UUID> owner = getOwnerUuid();
            boolean shouldDiscard;
            if (owner.isEmpty()) {
                shouldDiscard = true; // владелец неизвестен (битые NBT) — убираем
            } else {
                MinecraftServer server = this.getServer();
                ServerPlayerEntity player = server != null
                        ? server.getPlayerManager().getPlayer(owner.get())
                        : null;
                // Владелец оффлайн ИЛИ онлайн, но уже не во сне — тело больше не нужно.
                shouldDiscard = player == null || !DreamManager.isDreaming(owner.get());
            }
            if (shouldDiscard) {
                this.discard();
            }
        }
    }

    // --- Сохранение владельца в NBT: без этого после перезапуска сервера тело бы ---
    // --- "забыло", чьё оно, и самоочистка сработала бы только по пустому UUID. ---

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        getOwnerUuid().ifPresent(uuid -> nbt.putUuid("OwnerUuid", uuid));
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("OwnerUuid")) {
            setOwnerUuid(nbt.getUuid("OwnerUuid"));
        }
    }
}
