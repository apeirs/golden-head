package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

/**
 * 1.8 hits during i-frames do nothing. While this is on, Auto Clicker and Auto Fight wait until
 * the locked enemy's hurt time is over so the click is one that can actually land.
 */
public class HitSelect extends Module {
    public HitSelect() {
        super(MeteorPitAddon.PIT, "hit-select", "Only swings when the enemy can take a hit (hurt time is over).");
    }

    /** True when a click now can register. Inactive module means no extra gate. */
    public static boolean allow(Entity target) {
        HitSelect module = Modules.get().get(HitSelect.class);
        if (module == null || !module.isActive()) return true;
        if (!(target instanceof LivingEntity living)) return true;
        return living.hurtTime == 0;
    }
}
