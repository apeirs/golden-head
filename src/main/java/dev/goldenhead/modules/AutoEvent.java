package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.*;
import dev.goldenhead.utils.EventTracker.PitEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Plays every Pit event. Detects the running event (chat + boss bar), switches on the right Pit modules for it
 * and drives the event-specific objective itself; anything combat is Auto Fight (sprint + hit, random interval).
 * Modules it switched on are switched off again when the event ends. See AUTO_EVENT.md for the per-event plan.
 */
public class AutoEvent extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEvents = settings.createGroup("Events");

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Say in chat (client-side) which event was detected and what it is doing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> minors = sgEvents.add(new BoolSetting.Builder()
        .name("minor-events")
        .description("Play minor events (Dragon Egg, Care Package, Cake, KOTH, KOTL, 2x, Bounty, Quick Maths).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> majors = sgEvents.add(new BoolSetting.Builder()
        .name("major-events")
        .description("Play major events (TDM, Beast, Rage Pit, Robbery, Raffle, Pizza, Squads, Spire, Blockhead).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> auctionPeek = sgEvents.add(new BoolSetting.Builder()
        .name("auction-peek")
        .description("Open the Auction menu once and print what's in it (never bids: bids spend gold).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> raffleDeposit = sgEvents.add(new IntSetting.Builder()
        .name("raffle-deposit-at")
        .description("Deposit raffle tickets once you carry this many (10+ shows your count to everyone).")
        .defaultValue(5)
        .range(1, 64)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> collectRange = sgEvents.add(new IntSetting.Builder()
        .name("collect-range")
        .description("How far to look for tickets, nuggets, villagers, banners and event structures.")
        .defaultValue(64)
        .range(16, 160)
        .sliderRange(16, 128)
        .build()
    );

    // Modules this one may drive. Only the ones it switched on itself are switched off again.
    private static final List<Class<? extends Module>> MANAGED = List.of(
        AutoFight.class, DragonEgg.class, CarePackage.class, GiantCake.class, QuickMaths.class,
        TeamDeathmatch.class, Beast.class, AutoGoldenHead.class);

    private static final Set<Block> PORTALS = Set.of(Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY);
    private static final Set<Block> RAFFLE_BOX = Set.of(Blocks.NOTE_BLOCK, Blocks.JUKEBOX);
    private static final Set<Block> KOTH_BLOCKS = Set.of(Blocks.DIAMOND_BLOCK);
    private static final Set<Block> KOTL_BLOCKS = Set.of(Blocks.GREEN_TERRACOTTA, Blocks.LIME_TERRACOTTA);
    private static final Set<Block> BANNERS = new HashSet<>();

    private final EventTracker tracker = new EventTracker();
    private final Set<Module> switchedOn = new HashSet<>();
    private final Mover mover = new Mover();
    private final Map<Block, Set<BlockPos>> fresh = new HashMap<>();
    private final Map<BlockPos, Long> bannerSkip = new HashMap<>();

    private PitEvent event;
    private long nextClick;
    private int scanTimer;
    private String status = "";

    // Zones found for KOTH / KOTL.
    private Box standZone, fightZone;

    // Spire
    private boolean touchedPortal, inSpire;

    // Squads
    private DyeColor ourColor;
    private BlockPos capturing;
    private DyeColor colorOnArrival;
    private long arrivedAt;

    // Auction
    private String auctionCommand;
    private long auctionOpenedAt;
    private boolean auctionDumped;

    public AutoEvent() {
        super(MeteorPitAddon.PIT, "auto-event", "Detects the running Pit event and plays it: objectives, clicks, collecting, and sprint-hit combat.");
    }

    @Override
    public void onActivate() {
        if (BANNERS.isEmpty()) for (Block b : Registries.BLOCK) if (b instanceof AbstractBannerBlock) BANNERS.add(b);
        event = null;
        resetEventState();
    }

    @Override
    public void onDeactivate() {
        mover.release();
        AutoFight fight = Modules.get().get(AutoFight.class);
        fight.paused = false;
        fight.holdZone = null;
        fight.extraFilter = null;
        for (Module m : switchedOn) if (m.isActive()) m.toggle();
        switchedOn.clear();
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        tracker.reset();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent e) {
        Text message = e.getMessage();
        String line = PitUtils.clean(message);
        tracker.onChat(line);

        // Auction: the announcement is clickable; remember its command to open the menu.
        if (line.toUpperCase(Locale.ROOT).contains("AUCTION")) {
            String cmd = findRunCommand(message);
            if (cmd != null) auctionCommand = cmd;
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent e) {
        boolean wasAir = e.oldState == null || e.oldState.isAir();
        if (wasAir && !e.newState.isAir()) fresh.computeIfAbsent(e.newState.getBlock(), b -> new HashSet<>()).add(e.pos.toImmutable());
        else if (e.newState.isAir()) {
            Set<BlockPos> set = e.oldState == null ? null : fresh.get(e.oldState.getBlock());
            if (set != null) set.remove(e.pos);
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        // In Spire, entering the portal (and every death inside) teleports you.
        if (e.packet instanceof PlayerPositionLookS2CPacket && event == PitEvent.SPIRE && touchedPortal) inSpire = true;
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;

        tracker.tickBossBar();
        PitEvent now = tracker.current();
        if (now != null && !(now.major ? majors.get() : minors.get())) now = null;
        if (now != event) {
            if (announce.get()) info(now == null ? "Event over." : "Playing " + now.name().replace('_', ' ').toLowerCase(Locale.ROOT) + ".");
            event = now;
            resetEventState();
        }

        Set<Class<? extends Module>> want = wanted(event);
        for (Class<? extends Module> c : MANAGED) ensure(c, want.contains(c));

        AutoFight fight = Modules.get().get(AutoFight.class);
        fight.holdZone = null;
        fight.extraFilter = null;

        boolean driving = event == null ? prepare() : play(event, fight);
        fight.paused = driving;
        if (!driving) mover.stop();
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        auctionDump();
    }

    // ---- What each event needs ----

    private Set<Class<? extends Module>> wanted(PitEvent e) {
        Set<Class<? extends Module>> set = new HashSet<>();
        if (minors.get()) set.add(QuickMaths.class); // passive: only reacts to the question line
        if (e == null) return set;

        switch (e) {
            case DRAGON_EGG -> set.add(DragonEgg.class);
            case CARE_PACKAGE -> set.add(CarePackage.class);
            case GIANT_CAKE -> set.add(GiantCake.class);
            case QUICK_MATHS, AUCTION -> {
            }
            case TEAM_DEATHMATCH -> set.addAll(List.of(AutoFight.class, TeamDeathmatch.class, AutoGoldenHead.class));
            case BEAST -> set.addAll(List.of(AutoFight.class, Beast.class, AutoGoldenHead.class));
            default -> set.addAll(List.of(AutoFight.class, AutoGoldenHead.class));
        }
        return set;
    }

    private void ensure(Class<? extends Module> klass, boolean on) {
        Module m = Modules.get().get(klass);
        if (m == null) return;
        if (on && !m.isActive()) {
            m.toggle();
            switchedOn.add(m);
        } else if (!on && m.isActive() && switchedOn.remove(m)) m.toggle();
    }

    /** No event running: get ready for a major that is about to start. Returns true while moving. */
    private boolean prepare() {
        if (tracker.pending() == PitEvent.SPIRE && majors.get() && tracker.pendingInMs() < 70_000) {
            BlockPos portal = spirePortal();
            if (portal != null) {
                status = "waiting at Spire portal";
                return moveTo(portal, 1, Vec3d.ofCenter(portal));
            }
        }
        status = "";
        return false;
    }

    /** One tick of the current event. Returns true while Auto Event drives movement (Auto Fight paused). */
    private boolean play(PitEvent e, AutoFight fight) {
        switch (e) {
            case KING_OF_THE_HILL -> {
                zones(KOTH_BLOCKS, 6, false);
                fight.holdZone = standZone;
                fight.extraFilter = inZone(fightZone);
                status = standZone == null ? "looking for the hill" : "holding the hill";
                return false;
            }
            case KING_OF_THE_LADDER -> {
                zones(KOTL_BLOCKS, 10, true);
                fight.holdZone = standZone;
                fight.extraFilter = inZone(fightZone);
                status = standZone == null ? "looking for the ladder" : "holding the top";
                return false;
            }
            case ROBBERY -> {
                return collect(Items.GOLD_NUGGET, fight, "grabbing gold");
            }
            case RAFFLE -> {
                return raffle(fight);
            }
            case PIZZA -> {
                return pizza(fight);
            }
            case SQUADS -> {
                return squads(fight);
            }
            case SPIRE -> {
                if (inSpire) {
                    status = "climbing the Spire";
                    return false;
                }
                BlockPos portal = spirePortal();
                if (portal == null) {
                    status = "looking for the Spire portal";
                    return false;
                }
                if (mc.player.getBlockPos().isWithinDistance(portal, 1.5)) touchedPortal = true;
                status = "entering the Spire";
                return moveTo(portal, 0, Vec3d.ofCenter(portal));
            }
            case AUCTION -> {
                auctionPeek();
                return false;
            }
            default -> {
                status = e == PitEvent.DRAGON_EGG || e == PitEvent.CARE_PACKAGE || e == PitEvent.GIANT_CAKE ? "clicking" : "fighting";
                return false;
            }
        }
    }

    // ---- KOTH / KOTL ----

    private void zones(Set<Block> blocks, int minSize, boolean topOnly) {
        if (--scanTimer > 0 && standZone != null) return;
        scanTimer = 40;

        List<BlockPos> cluster = new ArrayList<>();
        for (Block b : blocks) cluster.addAll(fresh.getOrDefault(b, Set.of()));
        if (cluster.size() < minSize) cluster = BlockScan.largestCluster(BlockScan.find(blocks, collectRange.get()));
        else cluster = BlockScan.largestCluster(cluster);
        if (cluster.size() < minSize) {
            standZone = fightZone = null;
            return;
        }

        Box all = BlockScan.bounds(cluster);
        List<BlockPos> top = cluster;
        if (topOnly) {
            int maxY = (int) all.maxY - 1;
            top = cluster.stream().filter(p -> p.getY() == maxY).toList();
        }
        Box topBox = BlockScan.bounds(top);
        standZone = new Box(topBox.minX, topBox.maxY, topBox.minZ, topBox.maxX, topBox.maxY + 2, topBox.maxZ);
        fightZone = all.expand(3, 3, 3);
    }

    private static Predicate<PlayerEntity> inZone(Box zone) {
        return zone == null ? null : p -> zone.contains(p.getEntityPos());
    }

    // ---- Robbery / Raffle: walk over items ----

    private boolean collect(Item item, AutoFight fight, String what) {
        ItemEntity drop = nearestItem(item);
        // Fight first if someone is right on us; otherwise go pick it up.
        if (drop == null || fightingClose(fight)) {
            status = "fighting";
            return false;
        }
        status = what;
        return moveTo(drop.getBlockPos(), 0, drop.getEntityPos());
    }

    private boolean raffle(AutoFight fight) {
        int tickets = InvUtils.find(Items.NAME_TAG).count();
        ItemEntity drop = nearestItem(Items.NAME_TAG);

        if (tickets > 0 && (tickets >= raffleDeposit.get() || drop == null)) {
            BlockPos box = raffleBox();
            if (box != null) {
                status = "depositing " + tickets + " tickets";
                if (!holdInMainHand(s -> s.isOf(Items.NAME_TAG))) return true;
                if (inReach(Vec3d.ofCenter(box), 4)) {
                    mover.stop();
                    mover.lookAt(Vec3d.ofCenter(box), 0.8);
                    if (clickReady()) Clicker.block(box, false, true, true);
                    return true;
                }
                return moveTo(box, 2, Vec3d.ofCenter(box));
            }
        }
        return collect(Items.NAME_TAG, fight, "collecting tickets");
    }

    private BlockPos raffleBox() {
        Vec3d spawn = SpawnArea.get();
        Vec3d ref = spawn != null ? spawn : mc.player.getEntityPos();
        return BlockScan.find(RAFFLE_BOX, collectRange.get()).stream()
            .filter(p -> spawn == null || !SpawnArea.isInSpawn(Vec3d.ofCenter(p)))
            .min(Comparator.comparingDouble(p -> horizontalDistSq(Vec3d.ofCenter(p), ref)))
            .orElse(null);
    }

    // ---- Pizza ----

    private boolean pizza(AutoFight fight) {
        Predicate<ItemStack> isPizza = s -> !s.isEmpty() && PitUtils.clean(s.getName()).contains("pizza");
        boolean hasPizza = InvUtils.find(isPizza).found();

        if (hasPizza) {
            Entity customer = nearestEntity(en -> en instanceof VillagerEntity v && v.isAlive() && !SpawnArea.isInSpawn(v));
            if (customer != null && !fightingClose(fight)) {
                status = "delivering pizza";
                if (!holdInMainHand(isPizza)) return true;
                return clickEntityOrApproach(customer, false, true);
            }
        }

        // Out of pizzas (or nobody to sell to): back to the stand to deposit cash and refill.
        Entity standLabel = nearestEntity(en -> !(en instanceof PlayerEntity) && label(en).contains("pizza") && !SpawnArea.isInSpawn(en));
        if (standLabel == null || fightingClose(fight)) {
            status = "fighting";
            return false;
        }
        status = hasPizza ? "at the stand" : "refilling pizzas";
        Entity npc = nearestEntityTo(standLabel.getEntityPos(), 3, en -> en instanceof LivingEntity && !(en instanceof PlayerEntity) && !(en instanceof ArmorStandEntity));
        if (npc != null) return clickEntityOrApproach(npc, true, true);

        BlockPos below = standLabel.getBlockPos();
        for (int i = 0; i < 5 && mc.world.getBlockState(below).isAir(); i++) below = below.down();
        if (mc.world.getBlockState(below).isAir()) return false;
        if (inReach(Vec3d.ofCenter(below), 4)) {
            mover.stop();
            mover.lookAt(Vec3d.ofCenter(below), 0.8);
            if (clickReady()) Clicker.block(below, true, true, true);
            return true;
        }
        return moveTo(below, 2, Vec3d.ofCenter(below));
    }

    // ---- Squads ----

    private boolean squads(AutoFight fight) {
        long now = System.currentTimeMillis();

        // Holding a banner: stand there (fight anyone who comes close) until it turns our color.
        if (capturing != null) {
            DyeColor color = bannerColor(capturing);
            if (color == null) capturing = null;
            else if (colorOnArrival != null && color != colorOnArrival && now - arrivedAt > 1000) {
                if (ourColor == null) ourColor = color; // first capture tells us our squad color
                capturing = null;
            } else if (now - arrivedAt > 9000) {
                bannerSkip.put(capturing, now + 20_000); // not flipping: contested or not capturable
                capturing = null;
            } else {
                Box near = new Box(capturing).expand(2, 1, 2);
                fight.holdZone = near;
                fight.extraFilter = inZone(near.expand(4, 2, 4));
                status = "capturing banner";
                return false;
            }
        }

        BlockPos target = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : BlockScan.find(BANNERS, collectRange.get())) {
            DyeColor c = bannerColor(p);
            Long skip = bannerSkip.get(p);
            if (c == null || c == ourColor || (skip != null && skip > now) || SpawnArea.isInSpawn(Vec3d.ofCenter(p))) continue;
            double d = mc.player.getBlockPos().getSquaredDistance(p);
            if (d < best) {
                best = d;
                target = p;
            }
        }
        if (target == null || fightingClose(fight)) {
            status = "fighting";
            return false;
        }

        if (mc.player.getBlockPos().isWithinDistance(target, 2.5)) {
            capturing = target;
            colorOnArrival = bannerColor(target);
            arrivedAt = now;
            return false;
        }
        status = "going to banner";
        return moveTo(target, 1, Vec3d.ofCenter(target));
    }

    private DyeColor bannerColor(BlockPos pos) {
        return mc.world.getBlockState(pos).getBlock() instanceof AbstractBannerBlock banner ? banner.getColor() : null;
    }

    // ---- Spire ----

    private BlockPos spirePortal() {
        Vec3d spawn = SpawnArea.get();
        Vec3d ref = spawn != null ? spawn : mc.player.getEntityPos();
        return BlockScan.find(PORTALS, collectRange.get()).stream()
            .min(Comparator.comparingDouble(p -> horizontalDistSq(Vec3d.ofCenter(p), ref)))
            .orElse(null);
    }

    // ---- Auction: look, never bid ----

    private void auctionPeek() {
        status = "auction (no bidding)";
        // Auction Worth opens the menu itself and prices the item. Don't race it for the same screen.
        AuctionWorth worth = Modules.get().get(AuctionWorth.class);
        if (worth != null && worth.isActive()) return;
        if (!auctionPeek.get() || auctionDumped || auctionCommand == null || auctionOpenedAt != 0) return;
        ChatUtils.sendPlayerMsg(auctionCommand.startsWith("/") ? auctionCommand : "/" + auctionCommand, false);
        auctionOpenedAt = System.currentTimeMillis();
    }

    private void auctionDump() {
        if (auctionOpenedAt == 0 || auctionDumped || mc.player == null) return;
        if (System.currentTimeMillis() - auctionOpenedAt > 5000) {
            auctionDumped = true;
            return;
        }
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        int containerSlots = handler.slots.size() - 36;
        boolean any = false;
        info("Auction menu \"%s\":", screen.getTitle().getString());
        for (int i = 0; i < containerSlots; i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            any = true;
            info("  slot %d: %s \"%s\"", i, Registries.ITEM.getId(s.getItem()).getPath(), s.getName().getString());
        }
        if (!any) return; // contents not here yet
        auctionDumped = true;
        mc.player.closeHandledScreen();
    }

    private static String findRunCommand(Text text) {
        List<String> found = new ArrayList<>();
        text.visit((style, part) -> {
            if (style.getClickEvent() instanceof ClickEvent.RunCommand run) found.add(run.command());
            return Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return found.isEmpty() ? null : found.getFirst();
    }

    // ---- Helpers ----

    private boolean moveTo(BlockPos goal, int range, Vec3d look) {
        mover.tick(goal, range, look);
        return true;
    }

    private boolean clickEntityOrApproach(Entity entity, boolean left, boolean right) {
        Vec3d c = entity.getBoundingBox().getCenter();
        if (inReach(c, 3)) {
            mover.stop();
            mover.lookAt(c, 0.8);
            if (clickReady()) Clicker.entity(entity, left, right, true);
            return true;
        }
        return moveTo(entity.getBlockPos(), 1, c);
    }

    private boolean holdInMainHand(Predicate<ItemStack> what) {
        if (what.test(mc.player.getMainHandStack())) return true;
        FindItemResult hotbar = InvUtils.findInHotbar(what);
        if (hotbar.found()) {
            InvUtils.swap(hotbar.slot(), false);
            return true;
        }
        FindItemResult inv = InvUtils.find(what);
        if (inv.found()) InvUtils.move().from(inv.slot()).toHotbar(8);
        return false; // next tick it's in the hotbar
    }

    private boolean clickReady() {
        long now = System.currentTimeMillis();
        if (now < nextClick) return false;
        nextClick = now + ThreadLocalRandom.current().nextInt(20, 101);
        return true;
    }

    private boolean inReach(Vec3d point, double reach) {
        return mc.player.getEyePos().distanceTo(point) <= reach;
    }

    private boolean fightingClose(AutoFight fight) {
        PlayerEntity t = fight.getTarget();
        return t != null && mc.player.distanceTo(t) < 5;
    }

    private ItemEntity nearestItem(Item item) {
        return (ItemEntity) nearestEntity(en -> en instanceof ItemEntity ie && ie.getStack().isOf(item) && !SpawnArea.isInSpawn(en));
    }

    private Entity nearestEntity(Predicate<Entity> filter) {
        Entity best = null;
        double bestD = collectRange.get() * collectRange.get();
        for (Entity en : mc.world.getEntities()) {
            if (!filter.test(en)) continue;
            double d = mc.player.squaredDistanceTo(en);
            if (d < bestD) {
                bestD = d;
                best = en;
            }
        }
        return best;
    }

    private Entity nearestEntityTo(Vec3d pos, double radius, Predicate<Entity> filter) {
        Entity best = null;
        double bestD = radius * radius;
        for (Entity en : mc.world.getEntities()) {
            if (!filter.test(en)) continue;
            double d = en.getEntityPos().squaredDistanceTo(pos);
            if (d < bestD) {
                bestD = d;
                best = en;
            }
        }
        return best;
    }

    private static String label(Entity en) {
        Text t = en instanceof DisplayEntity.TextDisplayEntity d ? ((dev.goldenhead.mixin.TextDisplayAccessor) d).goldenhead$getText() : en.getCustomName();
        return t == null ? "" : PitUtils.clean(t);
    }

    private static double horizontalDistSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private void resetEventState() {
        fresh.clear();
        bannerSkip.clear();
        standZone = fightZone = null;
        scanTimer = 0;
        touchedPortal = inSpire = false;
        capturing = null;
        auctionOpenedAt = 0;
        auctionDumped = false;
        mover.stop();
    }

    @Override
    public String getInfoString() {
        if (event == null) return tracker.pending() != null ? tracker.pending().name().toLowerCase(Locale.ROOT) + " soon" : null;
        return status.isEmpty() ? event.name().toLowerCase(Locale.ROOT) : status;
    }
}
