package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

/** Rotates through varied chat offers for trading two user-entered items. */
public class TradeFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<String> offering = sgGeneral.add(new StringSetting.Builder()
        .name("offering")
        .description("The item you are offering.")
        .defaultValue("")
        .build()
    );
    private final Setting<String> seeking = sgGeneral.add(new StringSetting.Builder()
        .name("seeking")
        .description("The item you want in return.")
        .defaultValue("")
        .build()
    );
    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("How often to send the next trade message.")
        .defaultValue(10)
        .range(1, 120)
        .sliderRange(1, 60)
        .build()
    );

    private static final String[] VARIANTS = {
        "Trading %s for %s.",
        "I am trading %s for %s.",
        "Looking to trade %s in exchange for %s.",
        "Offering %s; seeking %s.",
        "Anyone want to trade? I have %s for %s.",
        "Will trade %s for %s.",
        "I have %s and want %s in return.",
        "Trading away %s in exchange for %s.",
        "Seeking a trade: my %s for your %s.",
        "Can trade %s for %s if anyone is interested."
    };

    private long nextSendAt;
    private int nextVariant;

    public TradeFinder() {
        super(MeteorPitAddon.PIT, "trade-finder", "Cycles through 10 trade offers for your chosen items in chat.");
    }

    @Override
    public void onActivate() {
        nextVariant = 0;
        nextSendAt = System.currentTimeMillis() + interval.get() * 1000L;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        String from = offering.get().trim();
        String to = seeking.get().trim();
        if (from.isEmpty() || to.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now < nextSendAt) return;

        String message = String.format(VARIANTS[nextVariant], from, to);
        ChatUtils.sendPlayerMsg(message, false);
        nextVariant = (nextVariant + 1) % VARIANTS.length;
        nextSendAt = now + interval.get() * 1000L;
    }
}
