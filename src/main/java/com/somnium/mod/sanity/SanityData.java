package com.somnium.mod.sanity;

import net.minecraft.nbt.NbtCompound;

/**
 * Чистые данные рассудка одного игрока.
 * Хранятся в персистентных данных игрока (см. SanityManager#load/save)
 * через стандартный механизм PersistentState / кастомные NBT-теги на PlayerEntity.
 */
public final class SanityData {

    public static final float MAX_SANITY = 100.0f;
    public static final float MIN_SANITY = 0.0f;

    /** Текущий рассудок игрока, 0..100 */
    private float sanity = MAX_SANITY;

    /** Сколько тиков прошло с последнего успешного сна */
    private long ticksSinceLastSleep = 0L;

    /** Идентификатор последнего пройденного сна — нужен для Bleed-Through (спавн "родных" монстров) */
    private String lastDreamId = "";

    public float getSanity() {
        return sanity;
    }

    public void setSanity(float value) {
        this.sanity = Math.max(MIN_SANITY, Math.min(MAX_SANITY, value));
    }

    public void addSanity(float delta) {
        setSanity(this.sanity + delta);
    }

    public long getTicksSinceLastSleep() {
        return ticksSinceLastSleep;
    }

    public void setTicksSinceLastSleep(long ticks) {
        this.ticksSinceLastSleep = ticks;
    }

    public void resetSleepTimer() {
        this.ticksSinceLastSleep = 0L;
    }

    public String getLastDreamId() {
        return lastDreamId;
    }

    public void setLastDreamId(String lastDreamId) {
        this.lastDreamId = lastDreamId;
    }

    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putFloat("Sanity", sanity);
        nbt.putLong("TicksSinceLastSleep", ticksSinceLastSleep);
        nbt.putString("LastDreamId", lastDreamId);
        return nbt;
    }

    public static SanityData readNbt(NbtCompound nbt) {
        SanityData data = new SanityData();
        data.sanity = nbt.contains("Sanity") ? nbt.getFloat("Sanity") : MAX_SANITY;
        data.ticksSinceLastSleep = nbt.contains("TicksSinceLastSleep") ? nbt.getLong("TicksSinceLastSleep") : 0L;
        data.lastDreamId = nbt.contains("LastDreamId") ? nbt.getString("LastDreamId") : "";
        return data;
    }
}
