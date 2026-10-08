package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.PitUtils;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Leaves the Pit when a real player gets within the range slider. Spawn, friends, teammates, and
 * NPCs do not count, so the spawn platform does not disconnect you.
 */
public class AutoLogout extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Log out when a player is this many blocks away.")
        .defaultValue(4)
        .range(1, 32)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Friends do not log you out.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreSpawn = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-spawn")
        .description("Stay connected while you or the other player is on the spawn platform.")
        .defaultValue(true)
        .build()
    );

    private boolean leaving;
    private String near;

    public AutoLogout() {
        super(MeteorPitAddon.PIT, "auto-logout", "Logs out of the Pit when a player gets within range. The range slider is in blocks.");
    }

    @Override
    public void onActivate() {
        leaving = false;
        near = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.player.networkHandler == null) {
            leaving = false;
            near = null;
            return;
        }
        if (leaving) return;
        if (mc.player.isDead() || mc.player.getHealth() <= 0) return;
        if (!PitUtils.sidebarContains("pit")) {
            near = null;
            return;
        }
        if (ignoreSpawn.get() && SpawnArea.isInSpawn(mc.player)) {
            near = null;
            return;
        }

        double limit = range.get() * (double) range.get();
        PlayerEntity closest = null;
        double best = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!counts(player)) continue;
            double distance = mc.player.squaredDistanceTo(player);
            if (distance < best) {
                best = distance;
                closest = player;
            }
        }
        if (closest == null) {
            near = null;
            return;
        }
        double blocks = Math.sqrt(best);
        near = String.format(Locale.ROOT, "%.1f", blocks);
        if (best <= limit) logout(closest, blocks);
    }

    private boolean counts(PlayerEntity player) {
        if (player == mc.player || !player.isAlive() || player.isRemoved() || player.isSpectator()) return false;
        if (ignoreSpawn.get() && SpawnArea.isInSpawn(player)) return false;
        if (ignoreFriends.get() && Friends.get().isFriend(player)) return false;
        if (!TeamDeathmatch.allowTarget(player)) return false;
        return isRealPlayer(player);
    }

    /** NPCs are player entities too. Real players are on the tab list and do not use version-2 UUIDs. */
    private boolean isRealPlayer(PlayerEntity player) {
        if (player.getUuid().version() == 2 || mc.getNetworkHandler() == null) return false;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return entry != null;
    }

    private void logout(PlayerEntity player, double blocks) {
        leaving = true;
        String name = player.getName().getString();
        Text reason = Text.literal("Auto Logout: " + name + " is " + String.format(Locale.ROOT, "%.1f", blocks) + " blocks away");
        mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(reason));
    }

    @Override
    public String getInfoString() {
        return near;
    }
}
