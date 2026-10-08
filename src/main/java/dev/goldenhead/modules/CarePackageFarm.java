package dev.goldenhead.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.PitUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One Care Package per game launch, driven by {@code care-package-farm} on Linux
 * (~/.local/bin/care-package-farm). That script launches the instance about 5 minutes before a
 * scheduled Care Package and leaves a session file. This module sees the file on the title screen,
 * logs in to the session's account through In-Game Account Switcher, joins Hypixel, sends /play pit, turns Care Package on, and quits the game a while after the
 * package chest is gone. A launch without the file is left alone.
 */
public class CarePackageFarm extends Module {
    /**
     * {"event": epoch ms of the Care Package, "deadline": epoch ms to quit no matter what,
     * "account": In-Game Account Switcher account to log in to first, "password": its Crypt password}.
     */
    public static final Path SESSION = Path.of(System.getProperty("user.home"), ".local", "state", "care-package-farm", "session.json");

    private static final Logger LOG = LoggerFactory.getLogger("meteor-pit");

    private enum State { Connect, JoinPit, Wait, Quit }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> server = sgGeneral.add(new StringSetting.Builder()
        .name("server")
        .description("Address to join. A server-list entry with this address is used, so its ViaFabricPlus version applies.")
        .defaultValue("mc.hypixel.net")
        .build()
    );

    private final Setting<String> joinCommand = sgGeneral.add(new StringSetting.Builder()
        .name("join-command")
        .description("Command that sends you to a Pit lobby.")
        .defaultValue("/play pit")
        .build()
    );

    private final Setting<Integer> quitDelay = sgGeneral.add(new IntSetting.Builder()
        .name("quit-delay-seconds")
        .description("Quit the game this long after the package chest is gone.")
        .defaultValue(30)
        .range(0, 600)
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<Boolean> antiAfk = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-afk")
        .description("Jump and turn a little every 40-80s while waiting in spawn, so Hypixel does not send you to limbo.")
        .defaultValue(true)
        .build()
    );

    /** Chest missing this long counts as gone. The package blinks while it is lowered. */
    private static final int GONE_TICKS = 40;
    /** Accepted /play pit lines were 6 s apart (see LowPopFinder); stay clear of the spam limit. */
    private static final long JOIN_GAP_MS = 9_000;
    private static final long RECONNECT_MS = 15_000;

    private State state;
    private long eventAt;
    private long deadline;
    private long nextActionAt;
    private int joins;
    private BlockPos chest;
    private int missing;
    private long quitAt;
    private boolean announced;
    private boolean armed;
    private boolean stale;
    private Screen lastScreen;
    private String account = "";
    private String cryptPassword = "";
    private long switchStarted;
    private long nextSwitchAt;
    private Screen passwordScreen;

    public CarePackageFarm() {
        super(MeteorPitAddon.PIT, "care-package-farm", "Launched by care-package-farm: joins Hypixel Pit, plays the Care Package, then quits the game.");
        // It starts on the title screen, before there is a world.
        runInMainMenu = true;
        MeteorClient.EVENT_BUS.subscribe(new SessionWatch());
    }

    /** Turns the module on when the launcher script left a session file. */
    private final class SessionWatch {
        private boolean checked;

        @EventHandler
        private void onTick(TickEvent.Pre event) {
            if (checked || !(mc.currentScreen instanceof TitleScreen)) return;
            checked = true;
            if (!Files.isRegularFile(SESSION)) return;
            try {
                JsonObject json = JsonParser.parseString(Files.readString(SESSION)).getAsJsonObject();
                // Taken, so a manual launch later does not start a farm run. It also holds the Crypt password.
                Files.delete(SESSION);
                long until = json.get("deadline").getAsLong();
                if (until <= System.currentTimeMillis()) return;
                if (isActive()) toggle();
                eventAt = json.get("event").getAsLong();
                deadline = until;
                account = json.has("account") ? json.get("account").getAsString().trim() : "";
                cryptPassword = json.has("password") ? json.get("password").getAsString() : "";
                toggle();
            } catch (Exception e) {
                LOG.error("care-package-farm: bad session file", e);
            }
        }
    }

    @Override
    public void onActivate() {
        // Meteor restores the on/off state at startup. Without a session file that is not a farm run.
        stale = deadline == 0 && mc.world == null;
        if (stale) return;
        if (deadline == 0) {
            // Turned on by hand: play the next Care Package that is announced, then quit.
            eventAt = System.currentTimeMillis();
            deadline = eventAt + 30 * 60_000L;
        }
        state = mc.world == null ? State.Connect : State.JoinPit;
        nextActionAt = System.currentTimeMillis() + 3_000;
        joins = 0;
        chest = null;
        missing = 0;
        quitAt = 0;
        announced = false;
        armed = false;
        info("Care Package at %tR, quitting by %tR.", eventAt, deadline);
    }

    @Override
    public void onDeactivate() {
        deadline = 0;
        account = "";
        cryptPassword = "";
        switchStarted = 0;
        passwordScreen = null;
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String line = Beast.clean(event.getMessage()).toUpperCase(Locale.ROOT);
        if (line.contains("CARE PACKAGE IN ")) announced = true;
        // Fallback for a chest we never saw: the event itself has ended.
        if (line.contains("CARE PACKAGE") && (line.contains("ENDED") || line.endsWith(" OVER") || line.endsWith(" OVER!"))) {
            scheduleQuit("event ended");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (stale) {
            toggle();
            return;
        }
        long now = System.currentTimeMillis();
        if (state == State.Quit) return;
        if (now >= deadline) {
            quit("deadline reached");
            return;
        }
        if (quitAt != 0 && now >= quitAt) {
            quit("chest gone " + quitDelay.get() + "s ago");
            return;
        }

        if (mc.world == null) {
            // Title screen, or kicked / failed to connect.
            state = State.Connect;
            if (switchAccount(now)) return;
            if (mc.currentScreen instanceof TitleScreen || mc.currentScreen instanceof DisconnectedScreen) {
                if (mc.currentScreen != lastScreen && mc.currentScreen instanceof DisconnectedScreen screen) logDisconnect(screen);
                lastScreen = mc.currentScreen;
                if (now >= nextActionAt) {
                    nextActionAt = now + RECONNECT_MS;
                    connect();
                }
            }
            return;
        }

        if (!PitUtils.sidebarContains("pit")) {
            if (state != State.JoinPit) {
                state = State.JoinPit;
                nextActionAt = now + 2_000 + ThreadLocalRandom.current().nextInt(2_000);
            }
            if (now >= nextActionAt && mc.getNetworkHandler() != null) {
                nextActionAt = now + JOIN_GAP_MS + ThreadLocalRandom.current().nextInt(3_000);
                // Limbo ignores /play; go through the lobby every third try.
                ChatUtils.sendPlayerMsg(++joins % 3 == 0 ? "/lobby" : joinCommand.get(), false);
            }
            return;
        }

        if (state != State.Wait) {
            state = State.Wait;
            joins = 0;
            nextActionAt = now + 40_000 + ThreadLocalRandom.current().nextInt(40_000);
            prepare();
        }

        watchChest(now);
        if (antiAfk.get() && chest == null && !announced && now >= nextActionAt) {
            nextActionAt = now + 40_000 + ThreadLocalRandom.current().nextInt(40_000);
            if (mc.player.isOnGround()) mc.player.jump();
            mc.player.setYaw(mc.player.getYaw() + (float) ThreadLocalRandom.current().nextDouble(-25, 25));
        }
    }

    /** Care Package on; AutoLogout off, since the package is a crowd. */
    private void prepare() {
        AutoLogout logout = Modules.get().get(AutoLogout.class);
        if (logout != null && logout.isActive()) logout.toggle();
        CarePackage carePackage = Modules.get().get(CarePackage.class);
        if (!armed && carePackage != null && !carePackage.isActive()) carePackage.toggle();
        armed = true;
        info("In Pit. Care Package is on.");
    }

    /** Remember the package chest Care Package locks on to; once it has been missing a while, schedule the quit. */
    private void watchChest(long now) {
        CarePackage carePackage = Modules.get().get(CarePackage.class);
        BlockPos target = carePackage == null ? null : carePackage.targetBlock;
        if (target != null && mc.world.getBlockState(target).getBlock() instanceof ChestBlock) {
            chest = target.toImmutable();
            missing = 0;
            if (quitAt != 0) {
                quitAt = 0;
                info("Chest is back, not quitting yet.");
            }
            return;
        }
        if (chest == null) return;
        if (!mc.world.isChunkLoaded(chest.getX() >> 4, chest.getZ() >> 4)) return;
        if (mc.world.getBlockState(chest).getBlock() instanceof ChestBlock) {
            missing = 0;
            return;
        }
        if (++missing == GONE_TICKS) scheduleQuit("chest gone");
    }

    private void scheduleQuit(String why) {
        if (quitAt != 0 || !isActive()) return;
        quitAt = System.currentTimeMillis() + quitDelay.get() * 1000L;
        info("%s, quitting in %ds.", why, quitDelay.get());
    }

    private void connect() {
        String address = server.get().trim();
        ServerInfo info = null;
        ServerList list = new ServerList(mc);
        list.loadFile();
        for (int i = 0; i < list.size(); i++) {
            String saved = list.get(i).address.toLowerCase(Locale.ROOT);
            if (saved.equals(address.toLowerCase(Locale.ROOT)) || saved.equals(address.toLowerCase(Locale.ROOT).replaceFirst("^mc\\.", ""))) {
                info = list.get(i);
                break;
            }
        }
        if (info == null) info = new ServerInfo("Hypixel", address, ServerInfo.ServerType.OTHER);
        LOG.info("care-package-farm: connecting to {}", info.address);
        ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(info.address), info, false, null);
    }

    /**
     * Logs in to {@link #account} through In-Game Account Switcher's own screens, the way it is done by hand:
     * account list, search, select, Login, Crypt password, confirm. True until the session is that account,
     * so nothing joins on the account the launcher started with.
     */
    private boolean switchAccount(long now) {
        boolean done = account.isEmpty() || mc.getSession().getUsername().equalsIgnoreCase(account);
        if (done) {
            if (switchStarted != 0) {
                switchStarted = 0;
                LOG.info("care-package-farm: logged in as {}", mc.getSession().getUsername());
                if (!(mc.currentScreen instanceof TitleScreen)) mc.setScreen(new TitleScreen());
                nextActionAt = now + 1_000;
                return true;
            }
            return false;
        }
        if (switchStarted == 0) {
            switchStarted = now;
            LOG.info("care-package-farm: switching from {} to {}", mc.getSession().getUsername(), account);
        }
        if (now - switchStarted > 90_000) {
            quit("could not log in as " + account);
            return true;
        }
        if (now < nextSwitchAt) return true;
        nextSwitchAt = now + 1_000;

        Screen screen = mc.currentScreen;
        String name = screen == null ? "" : screen.getClass().getName();
        if (screen instanceof TitleScreen || screen instanceof DisconnectedScreen) {
            try {
                mc.setScreen((Screen) Class.forName("ru.vidtu.ias.screen.AccountScreen").getConstructor(Screen.class).newInstance(screen));
            } catch (ReflectiveOperationException e) {
                LOG.error("care-package-farm: can't open In-Game Account Switcher", e);
                quit("In-Game Account Switcher is missing");
            }
        } else if (name.endsWith(".AccountScreen")) {
            pickAccount(screen);
        } else if (name.endsWith(".LoginPopupScreen")) {
            enterPassword(screen);
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void pickAccount(Screen screen) {
        for (Element child : screen.children()) {
            if (child instanceof TextFieldWidget search && !search.getText().equals(account)) {
                search.setText(account);
                return;
            }
        }
        for (Element child : screen.children()) {
            if (!(child instanceof AlwaysSelectedEntryListWidget<?> list)) continue;
            Object pick = null;
            for (Object entry : list.children()) {
                if (entry instanceof AlwaysSelectedEntryListWidget.Entry<?> e && e.getNarration().getString().toLowerCase(Locale.ROOT).contains(account.toLowerCase(Locale.ROOT))) {
                    pick = entry;
                    break;
                }
            }
            // The search already filtered the list down to it.
            if (pick == null && list.children().size() == 1) pick = list.children().getFirst();
            if (pick == null) return;
            if (list.getSelectedOrNull() != pick) ((AlwaysSelectedEntryListWidget) list).setSelected((AlwaysSelectedEntryListWidget.Entry) pick);
        }
        ButtonWidget login = button(screen, "ias.accounts.login");
        if (login != null && login.active) login.onPress(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
    }

    private void enterPassword(Screen screen) {
        TextFieldWidget box = null;
        for (Element child : screen.children()) {
            if (child instanceof TextFieldWidget field) box = field;
        }
        // No box: still "Decrypting tokens..." or past it.
        if (box == null || screen == passwordScreen) return;
        box.setText(cryptPassword);
        ButtonWidget confirm = null;
        for (Element child : screen.children()) {
            if (child instanceof ButtonWidget button && button.active && !isKey(button.getMessage(), "gui.cancel") && !isKey(button.getMessage(), "gui.back")) confirm = button;
        }
        if (confirm == null) return;
        passwordScreen = screen;
        confirm.onPress(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
    }

    private static ButtonWidget button(Screen screen, String key) {
        for (Element child : screen.children()) {
            if (child instanceof ButtonWidget button && isKey(button.getMessage(), key)) return button;
        }
        return null;
    }

    private static boolean isKey(Text text, String key) {
        return text.getContent() instanceof TranslatableTextContent translatable && translatable.getKey().equals(key);
    }

    private void logDisconnect(DisconnectedScreen screen) {
        StringBuilder text = new StringBuilder(screen.getTitle().getString());
        for (Element child : screen.children()) {
            if (child instanceof ClickableWidget widget && !(widget instanceof ButtonWidget)) text.append(" | ").append(widget.getMessage().getString());
        }
        LOG.info("care-package-farm: disconnected: {}", text);
        // Retrying a banned account only hammers the server until the deadline.
        if (text.toString().toLowerCase(Locale.ROOT).contains("banned")) quit("account is banned");
    }

    private void quit(String why) {
        state = State.Quit;
        LOG.info("care-package-farm: quitting ({})", why);
        // The launcher script reads this, so a ban or a failed login stops it instead of relaunching.
        try {
            JsonObject result = new JsonObject();
            result.addProperty("reason", why);
            Files.writeString(SESSION.resolveSibling("result.json"), result.toString());
        } catch (Exception e) {
            LOG.error("care-package-farm: can't write result", e);
        }
        // Saved off, so the next launch only farms if the script asks for it.
        toggle();
        mc.scheduleStop();
    }

    @Override
    public String getInfoString() {
        if (state == null) return null;
        if (quitAt != 0) return "quit in " + Math.max(0, (quitAt - System.currentTimeMillis()) / 1000) + "s";
        if (state == State.Wait && chest != null) return "chest";
        return state.name().toLowerCase(Locale.ROOT);
    }
}
