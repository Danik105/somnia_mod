package com.somnium.mod.dream;

import com.somnium.mod.registry.ModTags;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Состояние игрока в яви на момент засыпания: куда вернуть, что вернуть.
 *
 * Снимок сериализуется в NBT игрока (SomniumPlayerState), поэтому выход с сервера
 * или падение сервера посреди сна больше не уничтожает инвентарь: при следующем
 * входе игрок восстанавливается из снимка (см. DreamManager#onPlayerJoin).
 *
 * Предметы с тегом somnium:dream_carry в снимок не попадают — они физически уходят
 * в сон вместе с игроком. При пробуждении они возвращаются в свободные слоты,
 * поэтому перекладывание их внутри сна больше не дублирует предметы.
 */
public final class DreamSnapshot {

    public final Identifier dreamId;
    public final RegistryKey<World> returnWorld;
    public final Vec3d returnPos;
    public final float yaw;
    public final float pitch;
    @Nullable public final BlockPos bedPos;

    private final List<ItemStack> inventory;
    private final float health;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;
    private final int xpLevel;
    private final float xpProgress;
    private final int xpTotal;
    private final int air;
    private final List<StatusEffectInstance> effects;

    private DreamSnapshot(Identifier dreamId, RegistryKey<World> returnWorld, Vec3d returnPos, float yaw, float pitch,
                          @Nullable BlockPos bedPos, List<ItemStack> inventory, float health, int foodLevel,
                          float saturation, float exhaustion, int xpLevel, float xpProgress, int xpTotal, int air,
                          List<StatusEffectInstance> effects) {
        this.dreamId = dreamId;
        this.returnWorld = returnWorld;
        this.returnPos = returnPos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.bedPos = bedPos;
        this.inventory = inventory;
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.xpLevel = xpLevel;
        this.xpProgress = xpProgress;
        this.xpTotal = xpTotal;
        this.air = air;
        this.effects = effects;
    }

    public static boolean isCarried(ItemStack stack) {
        return !stack.isEmpty() && stack.isIn(ModTags.DREAM_CARRY);
    }

    public static DreamSnapshot capture(ServerPlayerEntity player, Identifier dreamId, @Nullable BlockPos bedPos) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> items = new ArrayList<>(inv.size());
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            items.add(isCarried(stack) ? ItemStack.EMPTY : stack.copy());
        }
        List<StatusEffectInstance> effects = new ArrayList<>();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            effects.add(new StatusEffectInstance(effect));
        }
        var hunger = player.getHungerManager();
        return new DreamSnapshot(dreamId, player.getWorld().getRegistryKey(), player.getPos(),
                player.getYaw(), player.getPitch(), bedPos, items, player.getHealth(),
                hunger.getFoodLevel(), hunger.getSaturationLevel(), hunger.getExhaustion(),
                player.experienceLevel, player.experienceProgress, player.totalExperience,
                player.getAir(), effects);
    }

    /** Подготовка "тела сна": пустой инвентарь (кроме уносимых предметов), полное здоровье и сытость. */
    public static void prepareDreamBody(ServerPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.size(); slot++) {
            if (!isCarried(inv.getStack(slot))) inv.setStack(slot, ItemStack.EMPTY);
        }
        player.clearStatusEffects();
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0f);
        player.setAir(player.getMaxAir());
        player.extinguish();
        player.fallDistance = 0;
        player.currentScreenHandler.sendContentUpdates();
    }

    /** Возвращает яви её состояние. Телепорт делает вызывающий код. */
    public void restore(ServerPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> carried = new ArrayList<>();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (isCarried(stack)) carried.add(stack);
        }
        for (int slot = 0; slot < inv.size(); slot++) {
            inv.setStack(slot, slot < inventory.size() ? inventory.get(slot).copy() : ItemStack.EMPTY);
        }
        for (ItemStack stack : carried) {
            inv.offerOrDrop(stack);
        }
        player.currentScreenHandler.sendContentUpdates();

        player.clearStatusEffects();
        for (StatusEffectInstance effect : effects) {
            player.addStatusEffect(new StatusEffectInstance(effect));
        }
        player.setHealth(health > 0 ? Math.min(health, player.getMaxHealth()) : player.getMaxHealth());
        var hunger = player.getHungerManager();
        hunger.setFoodLevel(foodLevel);
        hunger.setSaturationLevel(saturation);
        hunger.setExhaustion(exhaustion);
        player.experienceLevel = xpLevel;
        player.experienceProgress = xpProgress;
        player.totalExperience = xpTotal;
        player.addExperience(0); // форсирует синхронизацию полосы опыта
        player.setAir(air);
        player.extinguish();
        player.fallDistance = 0;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Dream", dreamId.toString());
        nbt.putString("ReturnWorld", returnWorld.getValue().toString());
        nbt.putDouble("X", returnPos.x);
        nbt.putDouble("Y", returnPos.y);
        nbt.putDouble("Z", returnPos.z);
        nbt.putFloat("Yaw", yaw);
        nbt.putFloat("Pitch", pitch);
        if (bedPos != null) nbt.putLong("Bed", bedPos.asLong());

        NbtList items = new NbtList();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) continue;
            NbtCompound entry = stack.writeNbt(new NbtCompound());
            entry.putInt("SomniumSlot", slot);
            items.add(entry);
        }
        nbt.putInt("InventorySize", inventory.size());
        nbt.put("Inventory", items);

        nbt.putFloat("Health", health);
        nbt.putInt("Food", foodLevel);
        nbt.putFloat("Saturation", saturation);
        nbt.putFloat("Exhaustion", exhaustion);
        nbt.putInt("XpLevel", xpLevel);
        nbt.putFloat("XpProgress", xpProgress);
        nbt.putInt("XpTotal", xpTotal);
        nbt.putInt("Air", air);

        NbtList effectList = new NbtList();
        for (StatusEffectInstance effect : effects) {
            effectList.add(effect.writeNbt(new NbtCompound()));
        }
        nbt.put("Effects", effectList);
        return nbt;
    }

    @Nullable
    public static DreamSnapshot fromNbt(NbtCompound nbt) {
        Identifier dream = Identifier.tryParse(nbt.getString("Dream"));
        Identifier world = Identifier.tryParse(nbt.getString("ReturnWorld"));
        if (dream == null || world == null) return null;

        int size = Math.max(nbt.getInt("InventorySize"), 0);
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) items.add(ItemStack.EMPTY);
        NbtList list = nbt.getList("Inventory", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            int slot = entry.getInt("SomniumSlot");
            if (slot >= 0 && slot < size) items.set(slot, ItemStack.fromNbt(entry));
        }

        List<StatusEffectInstance> effects = new ArrayList<>();
        NbtList effectList = nbt.getList("Effects", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < effectList.size(); i++) {
            StatusEffectInstance effect = StatusEffectInstance.fromNbt(effectList.getCompound(i));
            if (effect != null) effects.add(effect);
        }

        return new DreamSnapshot(dream, RegistryKey.of(RegistryKeys.WORLD, world),
                new Vec3d(nbt.getDouble("X"), nbt.getDouble("Y"), nbt.getDouble("Z")),
                nbt.getFloat("Yaw"), nbt.getFloat("Pitch"),
                nbt.contains("Bed") ? BlockPos.fromLong(nbt.getLong("Bed")) : null,
                items, nbt.getFloat("Health"), nbt.getInt("Food"), nbt.getFloat("Saturation"),
                nbt.getFloat("Exhaustion"), nbt.getInt("XpLevel"), nbt.getFloat("XpProgress"),
                nbt.getInt("XpTotal"), nbt.contains("Air") ? nbt.getInt("Air") : 300, effects);
    }
}
