package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.LivingEntity;

/**
 * 1.8 sprint reset. After a hit, let go of forward for a tick so sprint drops, then take it again.
 * The next hit is a sprint hit. This is a real key, not a velocity change, so it does not rubberband.
 */
public class WTap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> ticks = sgGeneral.add(new IntSetting.Builder()
        .name("ticks")
        .description("How long to let go of forward after a hit.")
        .defaultValue(1)
        .range(1, 3)
        .sliderRange(1, 3)
        .build()
    );

    private int left;
    private boolean forced;

    public WTap() {
        super(MeteorPitAddon.COMBAT_18, "w-tap", "Releases forward for one tick after a hit so the next swing is a sprint hit.");
    }

    public static boolean isTapping() {
        WTap module = meteordevelopment.meteorclient.systems.modules.Modules.get().get(WTap.class);
        return module != null && module.isActive() && (module.left > 0 || module.forced);
    }

    @Override
    public void onDeactivate() {
        left = 0;
        restore();
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (mc.player == null || SpawnArea.isInSpawn(mc.player)) return;
        if (WwSprint.lockSprintOverride()) return;
        if (!(event.entity instanceof LivingEntity)) return;
        left = ticks.get();
    }

    // Before movement, so this tick's input packet says you let go of W.
    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (WwSprint.lockSprintOverride()) {
            left = 0;
            restore();
            return;
        }
        if (left > 0) {
            mc.options.forwardKey.setPressed(false);
            mc.player.setSprinting(false);
            forced = true;
            left--;
            return;
        }
        restore();
    }

    private void restore() {
        if (!forced) return;
        forced = false;
        if (Input.isPressed(mc.options.forwardKey)) mc.options.forwardKey.setPressed(true);
    }
}
