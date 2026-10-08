package dev.goldenhead;

import dev.goldenhead.commands.KosCommand;
import dev.goldenhead.commands.PitInfoCommand;
import dev.goldenhead.commands.PitSpawnCommand;
import dev.goldenhead.gui.KosTab;
import dev.goldenhead.kos.Kos;
import dev.goldenhead.modules.*;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

import java.util.List;

public class MeteorPitAddon extends MeteorAddon {
    public static final Category PIT = new Category("Pit", Items.GOLDEN_APPLE.getDefaultStack());
    public static final Category COMBAT_18 = new Category("1.8 Combat", Items.DIAMOND_SWORD.getDefaultStack());

    // Modules from other addons that get filed under Pit instead of their own category (see ModulesMixin).
    public static final String ZAIMBOT = "zgoly.meteorist.modules.zaimbot.ZAimbot";

    @Override
    public void onInitialize() {
        Modules.get().add(new AutoGoldenHead());
        Modules.get().add(new TeamDeathmatch());
        Modules.get().add(new FriendAlert());
        Modules.get().add(new Beast());
        Modules.get().add(new DragonEgg());
        Modules.get().add(new CarePackage());
        Modules.get().add(new CarePackageFarm());
        Modules.get().add(new QuickMaths());
        Modules.get().add(new LowPopFinder());
        Modules.get().add(new AutoFight());
        Modules.get().add(new Streak());
        Modules.get().add(new AutoClicker());
        Modules.get().add(new JumpReset());
        Modules.get().add(new WTap());
        Modules.get().add(new KeepSprint());
        Modules.get().add(new WwSprint());
        Modules.get().add(new HitSelect());
        Modules.get().add(new AutoRod());
        Modules.get().add(new GiantCake());
        Modules.get().add(new AutoEvent());
        Modules.get().add(new UpcomingEvents());
        Modules.get().add(new PitTrader());
        Modules.get().add(new AuctionWorth());
        Modules.get().add(new EstWorth());
        Modules.get().add(new ServerPlayerCount());
        Modules.get().add(new TradeFinder());
        Modules.get().add(new PizzaRemoteDeposit());
        Modules.get().add(new AutoLogout());

        Commands.add(new PitInfoCommand());
        Commands.add(new PitSpawnCommand());
        Commands.add(new KosCommand());

        // Saved with the other Meteor systems, then loaded by Systems.load() after addons init.
        Systems.add(new Kos());
        placeKosTab();

        // Always-on spawn learning (used by ZAimbot, Auto Fight and Auto Event).
        MeteorClient.EVENT_BUS.subscribe(SpawnArea.INSTANCE);
    }

    // Friends is already on the bar. KOS sits on its right.
    private static void placeKosTab() {
        KosTab kosTab = new KosTab();
        Tabs.add(kosTab);
        List<Tab> tabs = Tabs.get();
        int friends = -1;
        for (int i = 0; i < tabs.size(); i++) {
            if ("Friends".equals(tabs.get(i).name)) {
                friends = i;
                break;
            }
        }
        int at = tabs.indexOf(kosTab);
        if (friends >= 0 && at >= 0 && at != friends + 1) {
            tabs.remove(at);
            tabs.add(friends + 1, kosTab);
        }
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(PIT);
        Modules.registerCategory(COMBAT_18);
    }

    @Override
    public String getPackage() {
        return "dev.goldenhead";
    }
}
