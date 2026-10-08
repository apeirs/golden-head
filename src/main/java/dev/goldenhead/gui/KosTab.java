package dev.goldenhead.gui;

import dev.goldenhead.kos.Kos;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPlus;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.gui.screen.Screen;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Top-bar list of players combat should target. Same shape as the Friends tab. */
public class KosTab extends Tab {
    public KosTab() {
        super("KOS");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new KosScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof KosScreen;
    }

    private static class KosScreen extends WindowTabScreen {
        public KosScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            WTable table = add(theme.table()).expandX().minWidth(400).widget();
            initTable(table);

            add(theme.horizontalSeparator()).expandX();

            WHorizontalList list = add(theme.horizontalList()).expandX().widget();

            WTextBox nameW = list.add(theme.textBox("", (text, c) -> c != ' ')).expandX().widget();
            nameW.setFocused(true);

            WPlus add = list.add(theme.plus()).widget();
            add.action = () -> {
                String name = nameW.get().trim();
                Friend friend = new Friend(name);

                if (Kos.get().add(friend)) {
                    nameW.set("");
                    initTable(table);
                    nameW.setFocused(true);

                    MeteorExecutor.execute(() -> {
                        friend.updateInfo();
                        mc.execute(() -> {
                            initTable(table);
                            nameW.setFocused(true);
                        });
                    });
                }
            };

            enterAction = add.action;
        }

        private void initTable(WTable table) {
            table.clear();

            if (Kos.get().isEmpty()) {
                table.add(theme.label("No one yet. Combat targets everyone except friends until you add a name.")).expandX();
                table.row();
                return;
            }

            for (Friend friend : Kos.get()) {
                MeteorExecutor.execute(() -> {
                    if (friend.headTextureNeedsUpdate()) friend.updateInfo();
                });
            }

            for (Friend friend : Kos.get()) {
                table.add(theme.texture(32, 32, friend.getHead().needsRotate() ? 90 : 0, friend.getHead()));
                String label = friend.getName();
                if (Friends.get().get(friend.getName()) != null) label = label + " (friend, not attacked)";
                table.add(theme.label(label));

                WMinus remove = table.add(theme.minus()).expandCellX().right().widget();
                remove.action = () -> {
                    Kos.get().remove(friend);
                    initTable(table);
                };

                table.row();
            }
        }

        @Override
        public boolean toClipboard() {
            return NbtUtils.toClipboard(Kos.get());
        }

        @Override
        public boolean fromClipboard() {
            return NbtUtils.fromClipboard(Kos.get());
        }
    }
}
