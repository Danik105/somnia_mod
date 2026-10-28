package com.somnium.mod.player;

import com.somnium.mod.sanity.SanityData;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Всё, что мод хранит на игроке и сохраняет в его NBT (тег "Somnium"):
 *  - рассудок и история снов;
 *  - снимок состояния яви на время сна (инвентарь, здоровье, точка возврата) —
 *    переживает выход с сервера и падение сервера, см. DreamManager#onPlayerJoin;
 *  - точка возврата из мира снов за порталом.
 */
public final class SomniumPlayerState {

    private SanityData sanity = new SanityData();
    @Nullable private NbtCompound dreamSnapshot;
    @Nullable private RegistryKey<World> portalReturnWorld;
    @Nullable private BlockPos portalReturnPos;

    public static SomniumPlayerState of(ServerPlayerEntity player) {
        return ((SomniumPlayerAccess) player).somnium$getState();
    }

    public SanityData sanity() {
        return sanity;
    }

    @Nullable
    public NbtCompound dreamSnapshot() {
        return dreamSnapshot;
    }

    public void setDreamSnapshot(@Nullable NbtCompound snapshot) {
        this.dreamSnapshot = snapshot;
    }

    @Nullable
    public RegistryKey<World> portalReturnWorld() {
        return portalReturnWorld;
    }

    @Nullable
    public BlockPos portalReturnPos() {
        return portalReturnPos;
    }

    public void setPortalReturn(@Nullable RegistryKey<World> world, @Nullable BlockPos pos) {
        this.portalReturnWorld = world;
        this.portalReturnPos = pos;
    }

    public void writeNbt(NbtCompound root) {
        NbtCompound tag = new NbtCompound();
        tag.put("Sanity", sanity.writeNbt(new NbtCompound()));
        if (dreamSnapshot != null) {
            tag.put("DreamSnapshot", dreamSnapshot.copy());
        }
        if (portalReturnWorld != null && portalReturnPos != null) {
            tag.putString("PortalReturnWorld", portalReturnWorld.getValue().toString());
            tag.putLong("PortalReturnPos", portalReturnPos.asLong());
        }
        root.put("Somnium", tag);
    }

    public void readNbt(NbtCompound root) {
        if (!root.contains("Somnium")) return;
        NbtCompound tag = root.getCompound("Somnium");
        sanity = SanityData.readNbt(tag.getCompound("Sanity"));
        dreamSnapshot = tag.contains("DreamSnapshot") ? tag.getCompound("DreamSnapshot").copy() : null;
        Identifier worldId = tag.contains("PortalReturnWorld") ? Identifier.tryParse(tag.getString("PortalReturnWorld")) : null;
        if (worldId != null && tag.contains("PortalReturnPos")) {
            portalReturnWorld = RegistryKey.of(RegistryKeys.WORLD, worldId);
            portalReturnPos = BlockPos.fromLong(tag.getLong("PortalReturnPos"));
        } else {
            portalReturnWorld = null;
            portalReturnPos = null;
        }
    }

    /** Перенос данных при респавне/возвращении из Энда (новый объект игрока). */
    public void copyFrom(SomniumPlayerState other) {
        NbtCompound tmp = new NbtCompound();
        other.writeNbt(tmp);
        readNbt(tmp);
    }
}
