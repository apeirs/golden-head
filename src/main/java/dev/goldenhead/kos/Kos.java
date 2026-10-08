package dev.goldenhead.kos;

import com.mojang.util.UndashedUuid;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Names combat should go after. An empty list changes nothing. A name here is the only kind of
 * player Auto Fight, aimbot, and Meteor's own attack modules will pick. Friends still win.
 */
public class Kos extends System<Kos> implements Iterable<Friend> {
    private final List<Friend> targets = new ArrayList<>();

    public Kos() {
        super("kos");
    }

    public static Kos get() {
        return Systems.get(Kos.class);
    }

    /** True when this player may be picked. Empty list allows everyone. */
    public static boolean allows(PlayerEntity player) {
        if (player == null) return true;
        Kos kos = get();
        if (kos == null || kos.targets.isEmpty()) return true;
        return kos.get(player) != null;
    }

    public boolean add(Friend friend) {
        if (friend.name.isEmpty() || friend.name.contains(" ")) return false;
        if (get(friend.name) != null) return false;

        targets.add(friend);
        Collections.sort(targets);
        save();
        return true;
    }

    public boolean remove(Friend friend) {
        if (targets.remove(friend)) {
            save();
            return true;
        }
        return false;
    }

    public void clear() {
        if (targets.isEmpty()) return;
        targets.clear();
        save();
    }

    public Friend get(String name) {
        for (Friend friend : targets) {
            if (friend.name.equalsIgnoreCase(name)) return friend;
        }
        return null;
    }

    public Friend get(PlayerEntity player) {
        return get(player.getName().getString());
    }

    public int count() {
        return targets.size();
    }

    public boolean isEmpty() {
        return targets.isEmpty();
    }

    @Override
    public @NotNull Iterator<Friend> iterator() {
        return targets.iterator();
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.put("targets", NbtUtils.listToTag(targets));
        return tag;
    }

    @Override
    public Kos fromTag(NbtCompound tag) {
        targets.clear();

        for (NbtElement element : tag.getListOrEmpty("targets")) {
            if (!(element instanceof NbtCompound friendTag) || !friendTag.contains("name")) continue;

            String name = friendTag.getString("name", "");
            if (name.isEmpty() || get(name) != null) continue;

            String uuid = friendTag.getString("id", "");
            Friend friend = new Friend(name);
            if (!uuid.isBlank()) {
                try {
                    friend = new Friend(name, UndashedUuid.fromStringLenient(uuid));
                } catch (IllegalArgumentException ignored) {
                    friend = new Friend(name);
                }
            }
            targets.add(friend);
        }

        Collections.sort(targets);
        MeteorExecutor.execute(() -> targets.forEach(Friend::updateInfo));
        return this;
    }
}
