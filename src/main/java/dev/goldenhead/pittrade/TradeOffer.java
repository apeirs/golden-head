package dev.goldenhead.pittrade;

import dev.goldenhead.modules.PitTrader;
import dev.goldenhead.utils.PitUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends /trade and moves Chunks of Vile into our side of the window, one click at a time.
 * The accept button is only pressed when confirm is on and their side is holding the mystic.
 */
public final class TradeOffer {
    private enum Phase { IDLE, WAIT_SCREEN, PLACE }

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private Phase phase = Phase.IDLE;
    private UUID playerId;
    private String playerName = "";
    private int vileWanted;
    private long phaseSince;
    private long nextClick;
    private long lastRequest;
    private UUID lastPlayer;
    private boolean toldLayout;
    private boolean toldPlaced;
    private boolean toldWaiting;
    private boolean confirmClicked;
    private int syncId = -1;

    public void clear() {
        phase = Phase.IDLE;
        syncId = -1;
    }

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    /**
     * @param livesKnown false when a per-life enchant is priced and the worn item didn't say its lives
     */
    public void request(PitTrader module, PlayerEntity player, int vile, boolean livesKnown) {
        MinecraftClient client = module.client();
        if (player == null || client.player == null) return;
        long now = System.currentTimeMillis();
        if (phase != Phase.IDLE) return;
        if (client.currentScreen != null) return;
        if (player.getUuid().equals(lastPlayer) && now - lastRequest < module.tradeCooldownMs()) return;

        lastPlayer = player.getUuid();
        lastRequest = now;

        if (!livesKnown) {
            module.info("%s has it, but the lives aren't on the item, so no offer was sent.", player.getName().getString());
            return;
        }
        if (vile <= 0) {
            module.info("%s has it. The offer rounds to 0 vile, so this was an alert only.", player.getName().getString());
            return;
        }

        int have = MysticRead.countVile(client.player);
        if (have < vile) {
            module.info("%s has it. Offer is %dv and you have %dv.", player.getName().getString(), vile, have);
            return;
        }

        String name = player.getName().getString();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return;

        ChatUtils.sendPlayerMsg("/trade " + name, false);
        playerId = player.getUuid();
        playerName = name;
        vileWanted = vile;
        phase = Phase.WAIT_SCREEN;
        phaseSince = now;
        toldLayout = false;
        toldPlaced = false;
        toldWaiting = false;
        confirmClicked = false;
        syncId = -1;
        module.info("Asked %s to trade for %dv.", name, vile);
    }

    public void tick(PitTrader module) {
        MinecraftClient client = module.client();
        if (phase == Phase.IDLE || client.player == null || client.interactionManager == null) return;
        long now = System.currentTimeMillis();

        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) || !isTrade(module)) {
            if (phase == Phase.WAIT_SCREEN && now - phaseSince > 20_000) {
                module.info("%s didn't open the trade. Left it.", playerName);
                clear();
            } else if (phase == Phase.PLACE) {
                clear();
            }
            return;
        }

        if (phase == Phase.WAIT_SCREEN) {
            phase = Phase.PLACE;
            phaseSince = now;
            nextClick = now + module.clickDelayMs();
            syncId = handler.syncId;
            return;
        }

        if (handler.syncId != syncId) {
            syncId = handler.syncId;
            confirmClicked = false;
            toldPlaced = false;
            toldWaiting = false;
        }
        if (now < nextClick) return;

        Layout layout = Layout.read(handler);
        if (layout == null) {
            if (!toldLayout) {
                module.warning("Trade with %s is open, but the slots aren't a layout I can place vile into.", playerName);
                toldLayout = true;
            }
            return;
        }

        int placed = layout.vile(handler);
        int need = vileWanted - placed;
        ItemStack cursor = handler.getCursorStack();

        if (!cursor.isEmpty()) {
            if (MysticRead.isVile(cursor) && need > 0) {
                int dest = layout.emptyOur(handler);
                if (dest < 0) dest = layout.partialVile(handler);
                if (dest >= 0) click(handler, dest, cursor.getCount() <= need ? 0 : 1);
            } else {
                int back = inventoryDest(handler, layout.containerSize, cursor);
                if (back >= 0) click(handler, back, 0);
            }
            nextClick = now + module.clickDelayMs();
            return;
        }

        if (need <= 0) {
            finishPlacing(module, handler, layout);
            return;
        }

        int from = vileInInventory(handler, layout.containerSize);
        if (from < 0) {
            module.warning("Ran out of vile while offering %s.", playerName);
            clear();
            return;
        }
        click(handler, from, 0);
        nextClick = now + module.clickDelayMs();
    }

    private void finishPlacing(PitTrader module, GenericContainerScreenHandler handler, Layout layout) {
        if (!toldPlaced) {
            module.info("Put %dv in the trade with %s.", vileWanted, playerName);
            toldPlaced = true;
        }
        if (!module.confirmOffer()) return;
        if (confirmClicked) return;
        if (layout.confirm < 0) {
            if (!toldWaiting) {
                module.info("Vile is in. I couldn't see an accept button, so I left it for you.");
                toldWaiting = true;
            }
            return;
        }
        if (!theirSideMatches(module, handler, layout)) {
            if (!toldWaiting) {
                module.info("Vile is in. Waiting until %s puts the item on their side.", playerName);
                toldWaiting = true;
            }
            return;
        }
        click(handler, layout.confirm, 0);
        confirmClicked = true;
        nextClick = System.currentTimeMillis() + module.clickDelayMs();
        module.info("Accepted the trade with %s.", playerName);
    }

    private boolean theirSideMatches(PitTrader module, GenericContainerScreenHandler handler, Layout layout) {
        for (int slot : layout.theirs) {
            MysticRead.Seen seen = MysticRead.parse(handler.getSlot(slot).getStack());
            if (MysticRead.matches(seen, module.wanted, module.exactEnchants())) return true;
        }
        return false;
    }

    private boolean isTrade(PitTrader module) {
        MinecraftClient client = module.client();
        if (client.currentScreen == null) return false;
        String title = PitUtils.clean(client.currentScreen.getTitle());
        return title.contains("trade") && !title.contains("limit");
    }

    private void click(GenericContainerScreenHandler handler, int slot, int button) {
        mc.interactionManager.clickSlot(handler.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    private static int vileInInventory(GenericContainerScreenHandler handler, int containerSize) {
        int end = Math.min(handler.slots.size(), containerSize + 36);
        for (int i = containerSize; i < end; i++) {
            if (MysticRead.isVile(handler.getSlot(i).getStack())) return i;
        }
        return -1;
    }

    private static int inventoryDest(GenericContainerScreenHandler handler, int containerSize, ItemStack stack) {
        int end = Math.min(handler.slots.size(), containerSize + 36);
        int empty = -1;
        for (int i = containerSize; i < end; i++) {
            ItemStack there = handler.getSlot(i).getStack();
            if (there.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (MysticRead.isVile(there) && there.getCount() < there.getMaxCount() && ItemStack.areItemsAndComponentsEqual(there, stack)) {
                return i;
            }
        }
        return empty;
    }

    /** Our columns are left of the glass divider. Theirs are to the right. Buttons are neither. */
    private static final class Layout {
        final int containerSize;
        final List<Integer> ours = new ArrayList<>();
        final List<Integer> theirs = new ArrayList<>();
        final int confirm;

        Layout(int containerSize, int confirm) {
            this.containerSize = containerSize;
            this.confirm = confirm;
        }

        int vile(GenericContainerScreenHandler handler) {
            int count = 0;
            for (int slot : ours) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (MysticRead.isVile(stack)) count += stack.getCount();
            }
            return count;
        }

        int emptyOur(GenericContainerScreenHandler handler) {
            for (int slot : ours) if (handler.getSlot(slot).getStack().isEmpty()) return slot;
            return -1;
        }

        int partialVile(GenericContainerScreenHandler handler) {
            for (int slot : ours) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (MysticRead.isVile(stack) && stack.getCount() < stack.getMaxCount()) return slot;
            }
            return -1;
        }

        static Layout read(GenericContainerScreenHandler handler) {
            int rows = handler.getRows();
            int size = rows * 9;
            int separator = -1;
            int mostPanes = 1;
            for (int column = 0; column < 9; column++) {
                int panes = 0;
                for (int row = 0; row < rows; row++) {
                    if (pane(handler.getSlot(row * 9 + column).getStack())) panes++;
                }
                if (panes > mostPanes) {
                    mostPanes = panes;
                    separator = column;
                }
            }
            if (separator <= 0 || separator >= 8) return null;

            int confirm = -1;
            List<Integer> ours = new ArrayList<>();
            List<Integer> theirs = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                int column = i % 9;
                ItemStack stack = handler.getSlot(i).getStack();
                if (column == separator || pane(stack)) continue;
                String name = PitUtils.clean(stack.getName());
                boolean confirmName = !stack.isEmpty() && (name.contains("accept") || name.contains("confirm")) && !name.contains("cancel") && !name.contains("waiting");
                if (confirmName && column < separator) {
                    confirm = i;
                    continue;
                }
                if (button(stack)) continue;
                if (column < separator) ours.add(i);
                else if (!stack.isEmpty()) theirs.add(i);
            }
            if (ours.isEmpty()) return null;
            Layout layout = new Layout(size, confirm);
            layout.ours.addAll(ours);
            layout.theirs.addAll(theirs);
            return layout;
        }

        private static boolean pane(ItemStack stack) {
            if (stack.isEmpty()) return false;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            return id.contains("glass_pane");
        }

        private static boolean button(ItemStack stack) {
            if (stack.isEmpty()) return false;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (id.contains("concrete") || id.equals("barrier") || id.equals("clock")) return true;
            if (id.contains("gold_nugget") || id.contains("gold_ingot") || id.contains("gold_block")) return true;
            String name = PitUtils.clean(stack.getName());
            return name.contains("cancel") || name.contains("decline") || name.contains("deny") || name.contains("status");
        }
    }
}
