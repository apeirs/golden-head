package dev.goldenhead.pittrade;

import dev.goldenhead.modules.PitTrader;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WItem;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The item sits on the left and its vile price on the right. Each enchant row does the same.
 * Insert copies the mystic in your hand into the rows.
 */
public class PitTraderScreen extends WindowScreen {
    private static final Color QUOTED = new Color(255, 196, 64);
    private static final Color ESTIMATE = new Color(170, 170, 170);
    private static final Color MUTED = new Color(150, 150, 150);

    private final PitTrader module;

    private WItem icon;
    private WLabel nameLabel;
    private WLabel valueLabel;
    private WLabel freshLabel;
    private WLabel offerLabel;
    private WLabel tokensLabel;
    private WLabel statusLabel;
    private WTextBox search;
    private WVerticalList enchantList;
    private final Map<String, Row> rows = new HashMap<>();

    public PitTraderScreen(GuiTheme theme, PitTrader module) {
        super(theme, "Pit Trader");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        VileBook.Wanted wanted = module.wanted;
        WVerticalList root = theme.verticalList();
        root.minWidth = 440;
        add(root).expandX();

        WHorizontalList header = theme.horizontalList();
        icon = theme.item(MysticRead.icon(wanted.kind, wanted.color));
        header.add(icon).padRight(6);
        WVerticalList titles = theme.verticalList();
        nameLabel = titles.add(theme.label(VileBook.itemName(wanted.kind, wanted.color, wanted.enchants), true)).widget();
        valueLabel = titles.add(theme.label("")).widget();
        freshLabel = titles.add(theme.label("")).widget();
        header.add(titles).padRight(8);
        WButton insert = header.add(theme.button("Insert held item")).expandCellX().right().widget();
        insert.action = this::insertHeld;
        insert.tooltip = "Copies the mystic or trade item in your hand. Nothing is taken out of the slot.";
        root.add(header).expandX();

        WHorizontalList kindRow = theme.horizontalList();
        kindRow.add(theme.label("Item"));
        WDropdown<VileBook.MysticKind> kinds = kindRow.add(theme.dropdown(wanted.kind)).padHorizontal(6).widget();
        kinds.action = () -> taskAfterRender = () -> {
            wanted.kind = kinds.get();
            wanted.enchants.entrySet().removeIf(entry -> !VileBook.fits(VileBook.get(entry.getKey()), wanted.kind));
            reload();
        };
        root.add(kindRow);

        if (wanted.kind == VileBook.MysticKind.PANTS) {
            WHorizontalList colors = theme.horizontalList();
            for (VileBook.PantColor color : VileBook.PantColor.values()) {
                boolean on = color == wanted.color;
                WButton button = colors.add(theme.button(on ? "[" + color.label + "]" : color.label)).widget();
                button.action = () -> taskAfterRender = () -> {
                    wanted.color = color;
                    reload();
                };
            }
            root.add(colors);
        }

        if (!wanted.kind.tradeGood()) {
            WHorizontalList lives = theme.horizontalList();
            lives.add(theme.label("Price at"));
            WIntEdit priceLives = lives.add(theme.intEdit(wanted.priceLives, 1, 200, 1, 100)).padHorizontal(4).widget();
            priceLives.action = () -> {
                wanted.priceLives = priceLives.get();
                refreshPrices();
            };
            lives.add(theme.label("lives"));
            lives.add(theme.label("Min lives")).padLeft(12);
            WIntEdit minLives = lives.add(theme.intEdit(wanted.minLives, 0, 200, 0, 100)).padHorizontal(4).widget();
            minLives.action = () -> wanted.minLives = minLives.get();
            lives.add(theme.label("0 = any"));
            root.add(lives);
        }

        tokensLabel = root.add(theme.label("")).widget();
        statusLabel = root.add(theme.label("")).widget();

        search = root.add(theme.textBox("", "Search enchants")).expandX().widget();
        search.action = () -> taskAfterRender = this::fillEnchants;

        WView view = theme.view();
        enchantList = theme.verticalList();
        view.add(enchantList).expandX();
        root.add(view).expandX();
        view.maxHeight = theme.scale(240);

        root.add(theme.horizontalSeparator("Offer")).expandX();
        WHorizontalList offer = theme.horizontalList();
        WCheckbox under = offer.add(theme.checkbox(module.underpay.get())).widget();
        under.action = () -> {
            module.underpay.set(under.checked);
            refreshPrices();
        };
        offer.add(theme.label("Underpay"));
        WIntEdit percent = offer.add(theme.intEdit(module.underpayPercent.get(), 1, 100, 50, 100, true)).padHorizontal(4).widget();
        percent.action = () -> {
            module.underpayPercent.set(percent.get());
            refreshPrices();
        };
        offer.add(theme.label("%"));
        WCheckbox real = offer.add(theme.checkbox(module.realPrice.get())).padLeft(10).widget();
        real.action = () -> {
            module.realPrice.set(real.checked);
            refreshPrices();
        };
        offer.add(theme.label("Real price"));
        WButton clear = offer.add(theme.button("Clear enchants")).expandCellX().right().widget();
        clear.action = () -> taskAfterRender = () -> {
            wanted.enchants.clear();
            reload();
        };
        root.add(offer).expandX();

        offerLabel = root.add(theme.label("")).widget();
        root.add(theme.label("Both off: a popup only, no /trade. If both are on, the offer is the underpay. Accept stays off unless Confirm offer is enabled in the module settings.", 420)).widget();
        root.add(theme.label("Quoted rows are 2024–2026 market prints. est. is a conservative guess where nobody posted a number. Per-life rows use the lives above.", 420)).widget().color(MUTED);

        fillEnchants();
        refreshPrices();
    }

    @Override
    protected void onClosed() {
        Modules.get().save();
    }

    private void insertHeld() {
        if (client().player == null) return;
        ItemStack held = client().player.getMainHandStack();
        MysticRead.Seen seen = MysticRead.parse(held);
        if (seen == null) {
            statusLabel.set("Hold a mystic or a trade item, then insert it.");
            statusLabel.color(ESTIMATE);
            return;
        }
        module.wanted.kind = seen.kind;
        if (seen.color != null) module.wanted.color = seen.color;
        module.wanted.enchants.clear();
        module.wanted.enchants.putAll(seen.enchants);
        if (seen.lives > 0) {
            module.wanted.priceLives = seen.lives;
            module.wanted.minLives = Math.max(1, seen.lives);
        }
        taskAfterRender = this::reload;
    }

    private void fillEnchants() {
        rows.clear();
        enchantList.clear();
        String query = search == null || search.get() == null ? "" : search.get().trim().toLowerCase(Locale.ROOT);
        List<VileBook.Enchant> enchants = new ArrayList<>(VileBook.forKind(module.wanted.kind));
        enchants.removeIf(enchant -> !query.isEmpty()
            && !enchant.name.toLowerCase(Locale.ROOT).contains(query)
            && !(enchant.rare && "rare".equals(query)));
        enchants.sort(Comparator
            .comparing((VileBook.Enchant enchant) -> !module.wanted.enchants.containsKey(enchant.name))
            .thenComparing(enchant -> !enchant.rare)
            .thenComparing(enchant -> enchant.name));

        String section = "";
        if (enchants.isEmpty()) {
            enchantList.add(theme.label(module.wanted.kind.enchantable() ? "No enchants match." : "This item isn't enchanted. The price above is the item itself.")).widget().color(MUTED);
        }
        for (VileBook.Enchant enchant : enchants) {
            boolean selected = module.wanted.enchants.containsKey(enchant.name);
            String next = selected ? "Selected" : enchant.rare ? "Rare" : "Enchants";
            if (!next.equals(section)) {
                section = next;
                enchantList.add(theme.label(section)).widget().color(MUTED);
            }
            enchantList.add(row(enchant)).expandX();
        }
        refreshPrices();
    }

    private WHorizontalList row(VileBook.Enchant enchant) {
        WHorizontalList line = theme.horizontalList();
        int selectedTier = module.wanted.enchants.getOrDefault(enchant.name, 0);
        WCheckbox box = line.add(theme.checkbox(selectedTier > 0)).widget();
        WLabel name = line.add(theme.label(enchant.name)).minWidth(210).widget();
        if (enchant.rare) name.color(new Color(255, 120, 160));

        WButton one = null;
        WButton two = null;
        WButton three = null;
        if (!enchant.single) {
            one = tierButton(line, enchant, 1);
            two = tierButton(line, enchant, 2);
            three = tierButton(line, enchant, 3);
        }

        WLabel value = line.add(theme.label("")).expandCellX().right().widget();
        Row row = new Row(enchant, box, one, two, three, value);
        rows.put(enchant.name, row);
        box.action = () -> toggle(row);
        paint(row);
        return line;
    }

    private WButton tierButton(WHorizontalList line, VileBook.Enchant enchant, int tier) {
        WButton button = line.add(theme.button(VileBook.romanNumeral(tier))).padHorizontal(1).widget();
        button.action = () -> selectTier(enchant, tier);
        return button;
    }

    private void toggle(Row row) {
        VileBook.Wanted wanted = module.wanted;
        if (row.box.checked) {
            int tier = row.enchant.single ? 1 : row.enchant.maxTier();
            if (!fitsCap(row.enchant, tier)) {
                row.box.checked = false;
                statusLabel.set("Pit items stop at 3 enchants and 8 tokens.");
                statusLabel.color(ESTIMATE);
                return;
            }
            wanted.enchants.put(row.enchant.name, tier);
            if (wanted.kind == VileBook.MysticKind.DARK && !"Somber".equals(row.enchant.name)) {
                wanted.enchants.putIfAbsent("Somber", 1);
                Row somber = rows.get("Somber");
                if (somber != null) {
                    somber.box.checked = true;
                    paint(somber);
                }
            }
            statusLabel.set("");
        } else {
            wanted.enchants.remove(row.enchant.name);
            statusLabel.set("");
        }
        paint(row);
        refreshPrices();
    }

    private void selectTier(VileBook.Enchant enchant, int tier) {
        if (enchant.tier(tier) == null) return;
        if (!fitsCap(enchant, tier)) {
            statusLabel.set("That tier would pass 8 tokens.");
            statusLabel.color(ESTIMATE);
            return;
        }
        module.wanted.enchants.put(enchant.name, tier);
        Row row = rows.get(enchant.name);
        if (row != null) {
            row.box.checked = true;
            paint(row);
        }
        statusLabel.set("");
        refreshPrices();
    }

    private boolean fitsCap(VileBook.Enchant enchant, int tier) {
        if (!module.wanted.kind.cappedTokens()) return true;
        boolean already = module.wanted.enchants.containsKey(enchant.name);
        int count = module.wanted.enchants.size() + (already ? 0 : 1);
        if (count > 3) return false;
        int tokens = VileBook.tokens(module.wanted.enchants) - module.wanted.enchants.getOrDefault(enchant.name, 0) + tier;
        return tokens <= 8;
    }

    private void paint(Row row) {
        int tier = module.wanted.enchants.getOrDefault(row.enchant.name, 0);
        if (row.one != null) row.one.set(mark(1, tier));
        if (row.two != null) row.two.set(mark(2, tier));
        if (row.three != null) row.three.set(mark(3, tier));

        int shown = tier > 0 ? tier : row.enchant.maxTier();
        VileBook.Tier price = row.enchant.tier(shown);
        if (price == null) {
            row.value.set("");
            return;
        }
        int hundredths = price.perLife() ? price.hundredths() * module.wanted.priceLives : price.hundredths();
        String text = VileBook.vile(hundredths);
        if (price.perLife()) text += "  " + VileBook.vile(price.hundredths()) + "/life";
        if (tier == 0) text = "(" + text + ")";
        if (price.confidence() == VileBook.Confidence.ESTIMATE) text += "  est.";
        row.value.set(text);
        row.value.color(price.confidence() == VileBook.Confidence.QUOTED && tier > 0 ? QUOTED : ESTIMATE);
        row.value.tooltip = price.perLife()
            ? (price.confidence() == VileBook.Confidence.QUOTED ? "Quoted per life." : "Estimate, per life.")
            : (price.confidence() == VileBook.Confidence.QUOTED ? "Quoted." : "Estimate. No current print for this one.");
    }

    private static String mark(int tier, int selected) {
        String roman = VileBook.romanNumeral(tier);
        return tier == selected ? "[" + roman + "]" : roman;
    }

    private void refreshPrices() {
        VileBook.Wanted wanted = module.wanted;
        VileBook.Quote quote = VileBook.quote(wanted.kind, wanted.enchants, wanted.priceLives);
        if (icon != null) icon.set(MysticRead.icon(wanted.kind, wanted.color));
        nameLabel.set(VileBook.itemName(wanted.kind, wanted.color, wanted.enchants));
        String total = VileBook.vile(quote.hundredths);
        if (quote.confidence == VileBook.Confidence.ESTIMATE) total += "  est.";
        else total += "  quoted";
        valueLabel.set(total);
        valueLabel.color(quote.confidence == VileBook.Confidence.QUOTED ? QUOTED : ESTIMATE);

        if (wanted.kind.tradeGood()) {
            freshLabel.set(switch (wanted.kind) {
                case CACTUS -> "2.2v each. Nine of these equal one pants bundle.";
                case BUNDLE -> "Filled bundle of 10 fresh. An empty shop bundle is ignored.";
                case FEATHER -> "Wiki lists 30v and 35v. This is the middle, marked est.";
                case GEM -> "Wiki band is 40–45v. No forum print, so this stays est.";
                case CAKE -> "Old ratio of 1–3 fresh, at today's 2v fresh. Marked est.";
                default -> "";
            });
        } else if (quote.fresh) {
            freshLabel.set(quote.confidence == VileBook.Confidence.QUOTED ? "Fresh price." : "Fresh price, estimated.");
        } else {
            String fresh = "Fresh is " + VileBook.vile(quote.freshHundredths);
            if (quote.freshConfidence == VileBook.Confidence.ESTIMATE) fresh += " est.";
            fresh += ", not added on top.";
            freshLabel.set(fresh);
        }
        freshLabel.color(MUTED);

        if (wanted.kind.cappedTokens()) {
            String tokens = wanted.enchants.size() + "/3 enchants · " + VileBook.tokens(wanted.enchants) + "/8 tokens";
            if (wanted.kind == VileBook.MysticKind.RAGE) tokens += " · rage pool plus normal pants enchants";
            tokensLabel.set(tokens);
        } else if (wanted.kind == VileBook.MysticKind.DARK) {
            tokensLabel.set("Dark enchants are single level. Somber is included when you pick another.");
        } else {
            tokensLabel.set("");
        }
        tokensLabel.color(MUTED);

        boolean under = module.underpay.get();
        boolean real = module.realPrice.get();
        if (!under && !real) {
            offerLabel.set("Alert only. Nothing is offered.");
            offerLabel.color(MUTED);
        } else {
            int count = VileBook.offerCount(quote.hundredths, under, module.underpayPercent.get());
            if (count <= 0) {
                offerLabel.set("That rounds to 0 vile, so it will only alert.");
                offerLabel.color(ESTIMATE);
            } else if (under) {
                offerLabel.set("Will offer " + count + "v (" + module.underpayPercent.get() + "% of " + VileBook.vile(quote.hundredths) + ").");
                offerLabel.color(QUOTED);
            } else {
                offerLabel.set("Will offer " + count + "v, the real price rounded to a whole vile.");
                offerLabel.color(QUOTED);
            }
        }

        for (Row row : rows.values()) paint(row);
        Modules.get().save();
    }

    private net.minecraft.client.MinecraftClient client() {
        return net.minecraft.client.MinecraftClient.getInstance();
    }

    private static final class Row {
        final VileBook.Enchant enchant;
        final WCheckbox box;
        final WButton one, two, three;
        final WLabel value;

        Row(VileBook.Enchant enchant, WCheckbox box, WButton one, WButton two, WButton three, WLabel value) {
            this.enchant = enchant;
            this.box = box;
            this.one = one;
            this.two = two;
            this.three = three;
            this.value = value;
        }
    }
}
