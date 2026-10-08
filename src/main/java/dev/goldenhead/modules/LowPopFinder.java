package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.PitUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hops Pit lobbies until one has at most {@link #maxPlayers} real players.
 * <p>
 * Measured 2026-10-06 (latest.log, one-second stamps) while hopping as fast as the server allowed:
 * {@code /lobby} is back in the main lobby the same second or the next, and
 * "SERVER FOUND! Sending to mega..." is followed by the Pit spawn in that same second.
 * Accepted {@code /play pit} lines were 6 seconds apart. The next one, about 5 seconds after the
 * last accept, was rejected with "Please don't spam the command!" and did not transfer.
 * The tab list is already in on that join second, so the count is taken once it holds still
 * and {@code /lobby} goes out immediately. The next join waits out {@code join-gap-ms} from
 * the previous join send, which the lobby trip counts toward. The old {@code command-gap-ms}
 * was a pause after arriving in the lobby; a saved 2000 there would send the next join too soon.
 */
public class LowPopFinder extends Module {
    private enum State { Check, Joining, Measuring, Leaving, Cooldown }

    /** A count that has not changed for this long has finished the join burst. */
    private static final long STEADY_MS = 400;
    private static final Pattern SENDING_TO = Pattern.compile("Sending to (\\S+)");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    private final Setting<Integer> maxPlayers = sgGeneral.add(new IntSetting.Builder()
        .name("max-players")
        .description("Stop in the first Pit lobby with this many real players or fewer (you included).")
        .defaultValue(15)
        .range(1, 200)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<String> joinCommand = sgGeneral.add(new StringSetting.Builder()
        .name("join-command")
        .description("Command that sends you to a Pit lobby.")
        .defaultValue("/play pit")
        .build()
    );

    private final Setting<String> leaveCommand = sgGeneral.add(new StringSetting.Builder()
        .name("leave-command")
        .description("Command that takes you out of Pit.")
        .defaultValue("/lobby")
        .build()
    );

    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Give up after this many joins. 0 = never.")
        .defaultValue(30)
        .range(0, 500)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<String> pitKeyword = sgAdvanced.add(new StringSetting.Builder()
        .name("pit-sidebar-keyword")
        .description("You're in Pit when the sidebar contains this.")
        .defaultValue("pit")
        .build()
    );

    private final Setting<Integer> settleMs = sgAdvanced.add(new IntSetting.Builder()
        .name("settle-ms")
        .description("Longest to wait for the tab list after arriving. A count that has held still for 0.4s is used sooner. A count of 1 waits out this cap so a tab list that is still loading is not treated as an empty lobby.")
        .defaultValue(1500)
        .range(0, 15000)
        .sliderRange(0, 4000)
        .build()
    );

    private final Setting<Integer> joinGapMs = sgAdvanced.add(new IntSetting.Builder()
        .name("join-gap-ms")
        .description("Minimum time between join commands. Travel back to the lobby counts toward it. Joins closer than about 6s were rejected.")
        .defaultValue(6500)
        .range(0, 20000)
        .sliderRange(0, 12000)
        .build()
    );

    private final Setting<Integer> timeoutMs = sgAdvanced.add(new IntSetting.Builder()
        .name("timeout-ms")
        .description("If a join or leave hasn't happened after this long, try again.")
        .defaultValue(10000)
        .range(2000, 60000)
        .sliderRange(2000, 30000)
        .build()
    );

    private State state;
    private long stateSince;
    private long nextJoinAt;
    private int attempts;
    private int lastCount = -1;
    private int seenCount = -1;
    private long countSince;
    private boolean joinAccepted;
    private String server;
    private ClientWorld lastWorld;

    public LowPopFinder() {
        super(MeteorPitAddon.PIT, "low-pop-finder", "Hops Pit lobbies (join, count, /lobby, repeat) until one has few enough players.");
    }

    @Override
    public void onActivate() {
        attempts = 0;
        lastCount = -1;
        joinAccepted = false;
        server = null;
        nextJoinAt = 0;
        lastWorld = mc.world;
        set(State.Check);
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (state == null) return;
        String raw = event.getMessage().getString();
        String line = Formatting.strip(raw);
        if (line == null) line = raw;
        String lower = line.toLowerCase(Locale.ROOT);

        if (lower.contains("don't spam the command") || lower.contains("sending commands too fast")) {
            // Only a join that has not landed yet can be the one Hypixel just refused.
            if (state == State.Joining || state == State.Cooldown || state == State.Check) {
                nextJoinAt = System.currentTimeMillis() + Math.max(0, joinGapMs.get());
                joinAccepted = false;
                info("Join rejected for command spam. Waiting %dms.", joinGapMs.get());
                if (state == State.Joining) set(State.Cooldown);
            }
            return;
        }

        Matcher matcher = SENDING_TO.matcher(line);
        if (!matcher.find()) return;
        String name = matcher.group(1);
        if (name.endsWith("!")) name = name.substring(0, name.length() - 1);
        server = name;
        joinAccepted = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return; // mid server switch
        long now = System.currentTimeMillis();
        long inState = now - stateSince;

        boolean worldChanged = mc.world != lastWorld;
        lastWorld = mc.world;
        boolean inPit = PitUtils.sidebarContains(pitKeyword.get().toLowerCase(Locale.ROOT).trim());

        switch (state) {
            case Check -> {
                if (inPit) set(State.Measuring);
                else join(now);
            }
            case Joining -> {
                if (inPit) set(State.Measuring);
                else if (inState > timeoutMs.get()) join(now);
            }
            case Measuring -> {
                if (worldChanged) set(State.Measuring); // moved again: the tab list is a new one
                else if (!inPit) set(State.Check);
                else if (countReady(now, inState)) measure();
            }
            case Leaving -> {
                if (!inPit) set(State.Cooldown);
                else if (inState > timeoutMs.get()) set(State.Measuring); // leave never happened; count and try again
            }
            case Cooldown -> {
                if (inPit) set(State.Measuring); // a rejected send can still be the one that transfers
                else if (now >= nextJoinAt) join(now);
            }
        }
    }

    /** True once the tab count has held still, or the cap has passed. A lone player waits out the cap. */
    private boolean countReady(long now, long inState) {
        int count = PitUtils.realPlayerCount();
        if (count != seenCount) {
            seenCount = count;
            countSince = now;
        }
        int cap = Math.max(0, settleMs.get());
        if (inState >= cap) return true;
        if (now - countSince < Math.min(STEADY_MS, cap)) return false;
        return count > 1;
    }

    private void measure() {
        lastCount = seenCount >= 0 ? seenCount : PitUtils.realPlayerCount();
        String where = server == null ? "this lobby" : server;
        if (lastCount <= maxPlayers.get()) {
            info("Found %s with %d players (limit %d) after %d joins.", where, lastCount, maxPlayers.get(), attempts);
            mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1f));
            toggle();
            return;
        }

        info("%d players on %s, over %d. Trying another lobby.", lastCount, where, maxPlayers.get());
        ChatUtils.sendPlayerMsg(leaveCommand.get(), false);
        set(State.Leaving);
    }

    private void join(long now) {
        if (maxAttempts.get() > 0 && attempts >= maxAttempts.get()) {
            warning("No lobby with %d or fewer players after %d joins. Stopping.", maxPlayers.get(), attempts);
            toggle();
            return;
        }
        if (now < nextJoinAt) {
            set(State.Cooldown);
            return;
        }
        attempts++;
        server = null;
        joinAccepted = false;
        ChatUtils.sendPlayerMsg(joinCommand.get(), false);
        nextJoinAt = now + Math.max(0, joinGapMs.get());
        set(State.Joining);
    }

    private void set(State next) {
        state = next;
        stateSince = System.currentTimeMillis();
        if (next == State.Measuring) {
            seenCount = -1;
            countSince = stateSince;
        }
    }

    @Override
    public String getInfoString() {
        if (state == null) return null;
        String base = lastCount >= 0 ? state.name() + " " + attempts + " (" + lastCount + ")" : state.name() + " " + attempts;
        if (state == State.Cooldown) {
            long left = Math.max(0, nextJoinAt - System.currentTimeMillis());
            return base + " " + (left + 999) / 1000 + "s";
        }
        if (server != null && (state == State.Measuring || state == State.Leaving || state == State.Joining)) {
            return base + " " + server;
        }
        return base;
    }
}
