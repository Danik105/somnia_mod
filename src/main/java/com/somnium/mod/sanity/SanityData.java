package com.somnium.mod.sanity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Данные рассудка одного игрока. Хранятся в NBT игрока (см. SomniumPlayerState). */
public final class SanityData {

    public static final float MAX_SANITY = 100.0f;
    public static final float MIN_SANITY = 0.0f;
    /** Сколько последних снов не повторяется при выборе следующего. */
    private static final int HISTORY_SIZE = 2;

    private float sanity = MAX_SANITY;
    /** Тиков с последнего сна (считаются только пока игрок онлайн и не спит). */
    private long ticksSinceLastSleep = 0L;
    /** Последние сны, новые — в начале списка. Первый — источник монстров прорыва. */
    private final List<Identifier> recentDreams = new ArrayList<>();

    public float getSanity() {
        return sanity;
    }

    public void setSanity(float value) {
        this.sanity = Math.max(MIN_SANITY, Math.min(MAX_SANITY, value));
    }

    public long getTicksSinceLastSleep() {
        return ticksSinceLastSleep;
    }

    public void setTicksSinceLastSleep(long ticks) {
        this.ticksSinceLastSleep = Math.max(0L, ticks);
    }

    public List<Identifier> getRecentDreams() {
        return Collections.unmodifiableList(recentDreams);
    }

    public Identifier getLastDreamId() {
        return recentDreams.isEmpty() ? null : recentDreams.get(0);
    }

    public void pushDream(Identifier dreamId) {
        recentDreams.remove(dreamId);
        recentDreams.add(0, dreamId);
        while (recentDreams.size() > HISTORY_SIZE) {
            recentDreams.remove(recentDreams.size() - 1);
        }
    }

    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putFloat("Sanity", sanity);
        nbt.putLong("TicksSinceLastSleep", ticksSinceLastSleep);
        NbtList history = new NbtList();
        for (Identifier id : recentDreams) {
            history.add(NbtString.of(id.toString()));
        }
        nbt.put("RecentDreams", history);
        return nbt;
    }

    public static SanityData readNbt(NbtCompound nbt) {
        SanityData data = new SanityData();
        if (nbt.contains("Sanity")) data.setSanity(nbt.getFloat("Sanity"));
        data.setTicksSinceLastSleep(nbt.getLong("TicksSinceLastSleep"));
        NbtList history = nbt.getList("RecentDreams", NbtElement.STRING_TYPE);
        for (int i = 0; i < history.size() && i < HISTORY_SIZE; i++) {
            Identifier id = Identifier.tryParse(history.getString(i));
            if (id != null) data.recentDreams.add(id);
        }
        return data;
    }
}
