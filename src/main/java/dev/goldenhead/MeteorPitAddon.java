package dev.goldenhead;

import dev.goldenhead.commands.PitInfoCommand;
import dev.goldenhead.commands.PitSpawnCommand;
import dev.goldenhead.modules.*;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

public class MeteorPitAddon extends MeteorAddon {
    public static final Category PIT = new Category("Pit", Items.GOLDEN_APPLE.getDefaultStack());

    // Modules from other addons that get filed under Pit instead of their own category (see ModulesMixin).
    public static final String ZAIMBOT = "zgoly.meteorist.modules.zaimbot.ZAimbot";

    @Override
    public void onInitialize() {
        Modules.get().add(new AutoGoldenHead());
        Modules.get().add(new TeamDeathmatch());
        Modules.get().add(new Beast());
        Modules.get().add(new DragonEgg());
        Modules.get().add(new CarePackage());
        Modules.get().add(new QuickMaths());
        Modules.get().add(new LowPopFinder());
        Modules.get().add(new AutoFight());
        Modules.get().add(new AutoClicker());
        Modules.get().add(new JumpReset());
        Modules.get().add(new WTap());
        Modules.get().add(new KeepSprint());
        Modules.get().add(new HitSelect());
        Modules.get().add(new AutoRod());
        Modules.get().add(new GiantCake());
        Modules.get().add(new AutoEvent());
        Modules.get().add(new UpcomingEvents());

        Commands.add(new PitInfoCommand());
        Commands.add(new PitSpawnCommand());

        // Always-on spawn learning (used by ZAimbot, Auto Fight and Auto Event).
        MeteorClient.EVENT_BUS.subscribe(SpawnArea.INSTANCE);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(PIT);
    }

    @Override
    public String getPackage() {
        return "dev.goldenhead";
    }
}
