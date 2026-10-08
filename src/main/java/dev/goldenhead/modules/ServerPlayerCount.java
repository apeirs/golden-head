package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/** Displays the current server tab-list player count at the top right. */
public class ServerPlayerCount extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> rightOffset = sgGeneral.add(new IntSetting.Builder()
        .name("right-offset")
        .description("Distance from the right edge of the screen.")
        .defaultValue(4)
        .range(0, 500)
        .sliderRange(0, 100)
        .build()
    );

    public ServerPlayerCount() {
        super(MeteorPitAddon.PIT, "server-player-count", "Shows the current server player count in the top right.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.options.hudHidden || mc.player == null || mc.getNetworkHandler() == null) return;
        int count = mc.getNetworkHandler().getPlayerList().size();
        String text = "Players: " + count;
        int x = Math.max(0, event.drawContext.getScaledWindowWidth() - mc.textRenderer.getWidth(text) - rightOffset.get());
        event.drawContext.drawTextWithShadow(mc.textRenderer, text, x, 4, 0xFFFFFFFF);
    }

    @Override
    public String getInfoString() {
        return mc.getNetworkHandler() == null ? null : Integer.toString(mc.getNetworkHandler().getPlayerList().size());
    }
}
