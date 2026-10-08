package dev.goldenhead.modules;

import com.mojang.authlib.GameProfile;
import dev.goldenhead.MeteorPitAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Friends render green on nametags. Joining a world announces friends already on the tab list.
 * Someone who joins after that plays a sound and shows the same notice, which fades out.
 * A tab-list refresh removes and re-adds the same id, so a name is not announced again for a few seconds.
 */
public class FriendAlert extends Module {
    private static final long SETTLE_MS = 3000;
    private static final long REALERT_MS = 8000;
    private static final int MAX_NOTICES = 4;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> colorNametags = sgGeneral.add(new BoolSetting.Builder()
        .name("color-nametags")
        .description("Draw friends in green on nametags.")
        .defaultValue(true)
        .build()
    );

    public final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Nametag color for friends.")
        .defaultValue(new SettingColor(0, 255, 0))
        .visible(colorNametags::get)
        .build()
    );

    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a bell when a friend joins, and once when friends are already in the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> noticeSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("notice-seconds")
        .description("How long the \"in your server\" notice stays on screen.")
        .defaultValue(5)
        .range(1, 30)
        .sliderRange(1, 15)
        .build()
    );

    private final Set<UUID> seen = new HashSet<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private ClientWorld world;
    private boolean settling;
    private long settleUntil;

    public FriendAlert() {
        super(MeteorPitAddon.PIT, "friend-alert", "Friends light up green on nametags. A friend in your server shows a notice that fades, and a join plays an alert.");
    }

    @Override
    public void onActivate() {
        seen.clear();
        lastAlert.clear();
        notices.clear();
        world = mc.world;
        settling = false;
        if (mc.world != null) announcePresent();
    }

    @Override
    public void onDeactivate() {
        notices.clear();
        settling = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world != world) {
            world = mc.world;
            if (world == null) {
                settling = false;
                seen.clear();
                notices.clear();
                return;
            }
            beginSettle();
        }
        if (settling && System.currentTimeMillis() >= settleUntil) {
            settling = false;
            announcePresent();
        }
        if (lastAlert.size() > 200) {
            long now = System.currentTimeMillis();
            lastAlert.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerRemoveS2CPacket remove) {
            for (UUID id : remove.profileIds()) seen.remove(id);
            return;
        }
        if (settling || !(event.packet instanceof PlayerListS2CPacket packet)) return;
        if (!packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;

        boolean ding = false;
        for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
            GameProfile profile = entry.profile();
            if (profile == null) continue;
            String name = friendName(profile.id(), profile.name());
            if (name == null || !mark(profile.id())) continue;
            push(name + " is in your server");
            ding = true;
        }
        if (ding) ding();
    }

    @EventHandler
    private void onRender(Render2DEvent event) {
        long now = System.currentTimeMillis();
        notices.removeIf(notice -> notice.until <= now);
        if (notices.isEmpty() || mc.options.hudHidden || mc.textRenderer == null) return;

        int y = 8;
        for (Notice notice : notices) {
            long left = notice.until - now;
            float fade = left < 800 ? Math.max(0f, left / 800f) : 1f;
            int alpha = Math.round(fade * 255);
            if (alpha < 8) continue;

            int width = mc.textRenderer.getWidth(notice.text) + 16;
            int x = (event.screenWidth - width) / 2;
            int height = 18;
            int bgAlpha = Math.round(fade * 0xE0);
            event.drawContext.fill(x, y, x + width, y + height, (bgAlpha << 24) | 0x101610);
            event.drawContext.fill(x, y, x + 3, y + height, (alpha << 24) | 0x3CFF5A);
            event.drawContext.drawTextWithShadow(mc.textRenderer, notice.text, x + 8, y + 5, (alpha << 24) | 0xB6FFC0);
            y += height + 4;
        }
    }

    private void beginSettle() {
        seen.clear();
        lastAlert.clear();
        notices.clear();
        settling = true;
        settleUntil = System.currentTimeMillis() + SETTLE_MS;
    }

    /** Friends already on the tab list. One sound for the whole group. */
    private void announcePresent() {
        if (mc.getNetworkHandler() == null) return;
        List<String> names = new ArrayList<>();
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            String name = friendName(entry.getProfile().id(), entry.getProfile().name());
            if (name == null || !mark(entry.getProfile().id())) continue;
            names.add(name);
        }
        if (names.isEmpty()) return;
        ding();
        int shown = 0;
        for (String name : names) {
            if (shown == 3) break;
            push(name + " is in your server");
            shown++;
        }
        int extra = names.size() - shown;
        if (extra == 1) push("1 more friend is in your server");
        else if (extra > 1) push(extra + " more friends are in your server");
    }

    private String friendName(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) return null;
        if (mc.player != null && id.equals(mc.player.getUuid())) return null;
        return Friends.get().get(name) != null ? name : null;
    }

    /** True when this id should get a notice. Repeat adds inside the quiet window are ignored. */
    private boolean mark(UUID id) {
        if (!seen.add(id)) return false;
        long now = System.currentTimeMillis();
        Long last = lastAlert.get(id);
        if (last != null && now - last < REALERT_MS) return false;
        lastAlert.put(id, now);
        return true;
    }

    private void push(String text) {
        notices.add(new Notice(text, System.currentTimeMillis() + noticeSeconds.get() * 1000L));
        while (notices.size() > MAX_NOTICES) notices.remove(0);
    }

    private void ding() {
        if (!sound.get() || mc.getSoundManager() == null) return;
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.15f));
    }

    public static SettingColor nametagColorFor(PlayerEntity player) {
        FriendAlert module = active();
        if (module == null || !module.colorNametags.get() || player == null || player == module.mc.player) return null;
        if (!Friends.get().isFriend(player)) return null;
        return module.color.get();
    }

    private static FriendAlert active() {
        FriendAlert module = Modules.get().get(FriendAlert.class);
        return module != null && module.isActive() ? module : null;
    }

    @Override
    public String getInfoString() {
        if (mc.getNetworkHandler() == null) return null;
        int count = 0;
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (friendName(entry.getProfile().id(), entry.getProfile().name()) != null) count++;
        }
        return count == 0 ? null : String.valueOf(count);
    }

    private record Notice(String text, long until) {}
}
