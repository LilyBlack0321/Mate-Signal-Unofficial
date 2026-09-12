package me.shiny.matesignal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiCrafting;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * MateSignal for Minecraft 1.12.2 / Forge 14.23.5.2860.
 * <p>
 * Client-only bridge that watches for notable in-game moments and reports them
 * to the MateEngine desktop avatar as small JSON datagrams on
 * {@code 127.0.0.1:32145}.
 * <p>
 * The event vocabulary is identical to MateSignal 1.1.0 on 1.21.1, so a single
 * MateEngine build understands every ported platform. The implementation is a
 * fresh rewrite against the 1.12.2 API, which predates modern registries,
 * {@code FoodData}, attributes and the 1.13+ GUI widgets.
 */
@Mod(
        modid = MateSignal.MOD_ID,
        name = MateSignal.NAME,
        version = MateSignal.VERSION,
        acceptedMinecraftVersions = "[1.12,1.13)",
        clientSideOnly = true,
        guiFactory = "me.shiny.matesignal.MateSignalGuiFactory",
        acceptableRemoteVersions = "*"
)
public class MateSignal {
    public static final String MOD_ID = "matesignal";
    /** Display name. The mod id above stays "matesignal" so that this port and
     *  the upstream mod share one config file and can be swapped freely. */
    public static final String NAME = "MateSignal Unofficial";
    public static final String VERSION = "@VERSION@";

    /** MateEngine listens here for avatar reaction events. */
    private static final InetSocketAddress TARGET = new InetSocketAddress("127.0.0.1", 32145);

    /** Indices into {@link Config#values}; kept stable for the config screen. */
    static final int OPT_DAY = 0;
    static final int OPT_NIGHT = 1;
    static final int OPT_LOW_HEALTH = 2;
    static final int OPT_LOW_HUNGER = 3;
    static final int OPT_DEATH = 4;
    static final int OPT_RAIN = 5;
    static final int OPT_DROWNING = 6;
    static final int OPT_SLEEP = 7;
    static final int OPT_CRAFTING = 8;
    static final int OPT_EAT = 9;
    static final int OPT_KILL = 10;
    static final int OPT_BIOME = 11;
    static final int OPT_COUNT = 12;

    private static final long BIOME_MSG_COOLDOWN_MS = 60_000L;
    private static final long MOB_DISCOVER_COOLDOWN_MS = 10_000L;
    private static final long LOW_HP_COOLDOWN_MS = 60_000L;
    private static final long BIOME_JOIN_GRACE_MS = 4_000L;
    /**
     * Largest world-time advance that still counts as natural flow. A normal
     * tick advances the day counter by 1; anything beyond this is a jump caused
     * by joining a world, {@code /time set}, or a lag spike and must not be
     * mistaken for a dawn/dusk crossing.
     */
    private static final long MAX_TIME_ADVANCE_PER_TICK = 200L;

    /** Delay between queued test events, so each bubble is readable. */
    private static final long TEST_INTERVAL_MS = 1000L;
    /** 1.12.2 has a fixed 300-tick air supply (no attribute, no getter). */
    private static final int MAX_AIR = 300;

    private static MateSignal instance;

    private final Set<Integer> inside = new HashSet<>();
    private final Set<Integer> scratch = new HashSet<>();

    private Object lastDim = null;

    private boolean wasLowHp = false;
    private boolean wasLowHunger = false;
    private long lastWorldTime = -1L;

    private boolean wasRaining = false;
    private boolean wasDead = false;
    private boolean wasHalfDrowning = false;
    private boolean wasSleeping = false;

    private boolean craftingObserved = false;
    private int lastMenuStateId = -1;
    private boolean lastCarriedNonEmpty = false;
    private long lastCarriedBeganAt = 0L;
    private long lastCraftMsgAt = 0L;
    private long nextMobMsgAt = 0L;

    private int lastFoodLevel = -1;
    private float lastSatLevel = -1.0f;
    private long lastEatMsgAt = 0L;
    private boolean wasUsingFood = false;
    private int lastUseTicks = 0;

    private long lastKillMsgAt = 0L;

    private String lastBiomeId = null;
    private long lastBiomeCheckAt = 0L;
    private long biomeGraceUntil = 0L;
    private long lastBiomeMsgAt = 0L;

    private long lastLowHpMsgAt = 0L;

    private final Map<Integer, Float> lastHp = new HashMap<>();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final Map<Integer, String> lastTypeId = new HashMap<>();
    private final Map<Integer, String> lastTypeName = new HashMap<>();
    private final Map<Integer, Long> deathFlagAt = new HashMap<>();

    private final Random rng = new Random();

    /** Pending test events and the tick at which the next one should be sent. */
    private final java.util.Deque<String> testQueue = new java.util.ArrayDeque<>();
    private long nextTestAt = 0L;

    /** Config screen access to the live handler state. */
    static MateSignal get() {
        return instance;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);

        // Client-only command: protocol testing plus biome name override reload.
        // ClientCommandHandler dispatches locally, so this works in singleplayer
        // and on servers alike (it never reaches the server).
        ClientCommandHandler.instance.registerCommand(new CommandBase() {
            @Override
            public String getName() {
                return "matesignal";
            }

            @Override
            public String getUsage(ICommandSender sender) {
                return "/matesignal test <event|all> | /matesignal list | /matesignal reload";
            }

            @Override
            public int getRequiredPermissionLevel() {
                return 0;
            }

            @Override
            public void execute(MinecraftServer server, ICommandSender sender, String[] args)
                    throws CommandException {
                if (args.length == 0) {
                    notifyPlayer("MateSignal Unofficial: " + getUsage(sender));
                    return;
                }
                String sub = args[0].toLowerCase(Locale.ROOT);

                if ("reload".equals(sub)) {
                    int n = Names.reloadOverrides();
                    notifyPlayer("MateSignal Unofficial: reloaded " + n + " biome name override(s)");
                    return;
                }

                if ("list".equals(sub)) {
                    notifyPlayer("MateSignal events (" + TestPayloads.size() + "): "
                            + String.join(", ", TestPayloads.names()));
                    notifyPlayer("MateSignal Unofficial: usage /matesignal test <event|all>");
                    return;
                }

                if ("test".equals(sub)) {
                    String wanted = args.length > 1
                            ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                            : "all";
                    queueTest(wanted);
                    return;
                }

                // Bare event name is accepted too: /matesignal mob_proximity
                queueTest(sub);
            }
        });
    }

    /**
     * Queues a test event (or all of them) for {@link #tickTestQueue()}.
     * Accepts an exact event name, a unique prefix, or {@code all}.
     */
    private void queueTest(String arg) {
        testQueue.clear();
        nextTestAt = 0L;

        String wanted = arg == null ? "all" : arg.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || wanted.equals("all")) {
            for (String n : TestPayloads.names()) {
                testQueue.add(resolveTest(n));
            }
            notifyPlayer("MateSignal Unofficial: sending all " + testQueue.size()
                    + " events, one per second. Watch MateEngine.");
            return;
        }

        String payload = resolveTest(wanted);
        if (payload != null) {
            testQueue.add(payload);
            notifyPlayer("MateSignal Unofficial: sending '" + wanted + "'");
            return;
        }

        List<String> candidates = TestPayloads.matching(wanted);
        if (candidates.isEmpty()) {
            notifyPlayer("MateSignal Unofficial: unknown event '" + wanted + "'. Try /matesignal list");
            return;
        }
        for (String n : candidates) {
            testQueue.add(resolveTest(n));
        }
        notifyPlayer("MateSignal Unofficial: '" + wanted + "' matches " + candidates.size()
                + " events: " + String.join(", ", candidates));
    }

    /**
     * Renders a test event with the same language-aware name lookups the live
     * detection uses, so the command cannot disagree with normal play.
     */
    private static String resolveTest(String eventName) {
        Minecraft mc = Minecraft.getMinecraft();
        return TestPayloads.resolve(eventName,
                mc == null ? null : mc.world,
                mc == null ? null : mc.player);
    }

    /** Sends queued test events one per second so each bubble is visible. */
    private void tickTestQueue() {
        if (testQueue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextTestAt) {
            return;
        }
        String payload = testQueue.poll();
        if (payload != null) {
            send(payload);
        }
        nextTestAt = now + TEST_INTERVAL_MS;
    }

    /**
     * Reloads the biome name override file. Exposed for the command above and
     * for the config screen so users can pick up edits without restarting.
     */
    static int reloadNameOverrides() {
        return Names.reloadOverrides();
    }

    /**
     * Client tick. 1.12.2 exposes this through the classic event-bus form; the
     * handler runs once per client tick while a world is loaded.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.player;
        World level = mc.world;

        // The test queue is drained before the null checks below, so queued
        // test events are still delivered when no player or world exists. This
        // concerns the queue only: the command that fills it needs a chat
        // screen, which the main menu does not have.
        tickTestQueue();

        if (p == null || level == null) {
            resetTransientState();
            return;
        }

        if (lastDim == null || lastDim != level.provider.getDimensionType()) {
            inside.clear();
            lastWorldTime = -1L;
            lastDim = level.provider.getDimensionType();
            nextMobMsgAt = 0L;
            lastBiomeId = null;
            lastHp.clear();
            lastSeen.clear();
            lastTypeId.clear();
            lastTypeName.clear();
            deathFlagAt.clear();
            biomeGraceUntil = System.currentTimeMillis() + BIOME_JOIN_GRACE_MS;
        }

        tickDayNight(level);
        tickVitals(p);
        tickEnvironment(p, level);
        tickBiome(level, p);
        observeCrafting(mc, p);
        trackAndConfirmKills(level, p);
        tickMobProximity(level, p);
    }

    private void resetTransientState() {
        inside.clear();
        lastWorldTime = -1L;
        wasRaining = false;
        wasDead = false;
        wasHalfDrowning = false;
        wasSleeping = false;
        craftingObserved = false;
        nextMobMsgAt = 0L;
        lastHp.clear();
        lastSeen.clear();
        lastTypeId.clear();
        lastTypeName.clear();
        deathFlagAt.clear();
        wasUsingFood = false;
        lastUseTicks = 0;
        lastBiomeId = null;
        biomeGraceUntil = 0L;
    }

    /**
     * Advances the day/night tracker.
     * <p>
     * Only a tick-consistent time advance counts as a crossing. World time also
     * jumps on world join, {@code /time set}, and dimension travel; treating
     * those as crossings fired bogus "night is coming" messages, so any jump
     * larger than {@link #MAX_TIME_ADVANCE_PER_TICK} is treated as a resync
     * instead of a crossing.
     */
    private void tickDayNight(World level) {
        // getTotalWorldTime() always advances; getWorldTime() freezes at night,
        // so it cannot be used to measure how much time actually elapsed.
        long worldTime = level.getTotalWorldTime();
        long dayTicks = level.getWorldTime() % 24000L;

        if (lastWorldTime >= 0L) {
            long elapsed = worldTime - lastWorldTime;
            if (elapsed >= 1L && elapsed <= MAX_TIME_ADVANCE_PER_TICK) {
                if (Config.get(OPT_DAY) && crossed(dayTicks, 23500L, elapsed)) {
                    send("{\"type\":\"time_day\"}");
                }
                if (Config.get(OPT_NIGHT) && crossed(dayTicks, 12750L, elapsed)) {
                    send("{\"type\":\"time_night\"}");
                }
            }
        }

        lastWorldTime = worldTime;
    }

    /**
     * True when the window {@code (dayNow - elapsed, dayNow]} contains
     * {@code threshold}, i.e. the threshold was passed by natural time flow.
     */
    private static boolean crossed(long dayNow, long threshold, long elapsed) {
        long dayPrev = dayNow - elapsed;
        if (dayPrev >= 0L) {
            return dayPrev < threshold && dayNow >= threshold;
        }
        // The window wrapped past midnight.
        return dayPrev + 24000L < threshold || dayNow >= threshold;
    }

    private void tickVitals(EntityPlayerSP p) {
        ItemStack use = p.getActiveItemStack();
        boolean usingFoodNow = p.isHandActive() && !use.isEmpty() && use.getItem() instanceof ItemFood;
        int useTicksNow = p.getItemInUseCount();

        float hp = p.getHealth();
        boolean lowHp = hp <= 6.0f;
        if (Config.get(OPT_LOW_HEALTH)) {
            long now = System.currentTimeMillis();
            if (lowHp && (!wasLowHp || now - lastLowHpMsgAt >= LOW_HP_COOLDOWN_MS)) {
                send("{\"type\":\"low_health\",\"hp\":" + String.format(Locale.ROOT, "%.1f", hp) + "}");
                lastLowHpMsgAt = now;
            }
        }
        wasLowHp = lowHp;

        int hunger = p.getFoodStats().getFoodLevel();
        boolean lowHung = hunger < 10;
        if (Config.get(OPT_LOW_HUNGER) && lowHung && !wasLowHunger) {
            send("{\"type\":\"low_hunger\",\"hunger\":" + hunger + "}");
        }

        if (Config.get(OPT_EAT)) {
            long now = System.currentTimeMillis();
            if (wasUsingFood && !usingFoodNow && lastUseTicks <= 1) {
                if (now - lastEatMsgAt >= 800L && rng.nextFloat() < 0.30f) {
                    send("{\"type\":\"eat\"}");
                    lastEatMsgAt = now;
                }
            } else if (lastFoodLevel >= 0) {
                boolean incFood = hunger > lastFoodLevel;
                boolean incSat = !incFood && p.getFoodStats().getSaturationLevel() > lastSatLevel + 0.02f;
                if ((incFood || incSat) && now - lastEatMsgAt >= 800L && rng.nextFloat() < 0.30f) {
                    send("{\"type\":\"eat\"}");
                    lastEatMsgAt = now;
                }
            }
        }
        lastFoodLevel = hunger;
        lastSatLevel = p.getFoodStats().getSaturationLevel();
        wasLowHunger = lowHung;
        wasUsingFood = usingFoodNow;
        lastUseTicks = useTicksNow;
    }

    private void tickEnvironment(EntityPlayerSP p, World level) {
        boolean rainingNow = level.isRainingAt(p.getPosition());
        if (Config.get(OPT_RAIN) && rainingNow && !wasRaining) {
            send("{\"type\":\"rain_start\"}");
        }
        wasRaining = rainingNow;

        float hp = p.getHealth();
        boolean deadNow = p.isDead || hp <= 0.0f;
        if (Config.get(OPT_DEATH) && deadNow && !wasDead) {
            send("{\"type\":\"death\"}");
        }
        wasDead = deadNow && hp <= 0.0f;

        int air = p.getAir();
        boolean halfDrownNow = air <= (MAX_AIR / 2) && air < MAX_AIR && p.isInWater();
        if (Config.get(OPT_DROWNING) && halfDrownNow && !wasHalfDrowning) {
            // MateEngine only matches "drowning" / "low_air" / "air_low"; the
            // upstream "drowning_half" name was silently ignored.
            send("{\"type\":\"drowning\",\"air\":" + air + ",\"max\":" + MAX_AIR + "}");
        }
        wasHalfDrowning = air > (int) (MAX_AIR * 0.8f) ? false : halfDrownNow;

        boolean sleepingNow = p.isPlayerSleeping();
        if (Config.get(OPT_SLEEP) && sleepingNow && !wasSleeping) {
            send("{\"type\":\"sleep_start\"}");
        }
        wasSleeping = sleepingNow;
    }

    private void tickBiome(World level, EntityPlayerSP p) {
        if (!Config.get(OPT_BIOME)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBiomeCheckAt < 250L) {
            return;
        }
        lastBiomeCheckAt = now;

        // 1.12.2 biomes cannot be translated: getBiomeName() returns a
        // hard-coded English string, so Names resolves the name itself (built-in
        // vanilla table, the mod's own language entries, then user overrides).
        // The raw name still doubles as the identity used for change detection,
        // so name resolution never affects triggering.
        net.minecraft.world.biome.Biome biome = level.getBiome(p.getPosition());
        String raw = biome.getBiomeName();
        if (raw == null) {
            raw = "unknown";
        }
        String full = raw;

        if (lastBiomeId == null) {
            lastBiomeId = full;
            return;
        }
        if (full.equals(lastBiomeId)) {
            return;
        }
        if (now >= biomeGraceUntil && now - lastBiomeMsgAt >= BIOME_MSG_COOLDOWN_MS) {
            send("{\"type\":\"biome_discovery\",\"biome\":\"" + escape(Names.biome(biome)) + "\"}");
            lastBiomeMsgAt = now;
        }
        lastBiomeId = full;
    }

    private void tickMobProximity(World level, EntityPlayerSP p) {
        int r = Math.max(3, Math.min(64, Config.getRadius()));
        double r2 = (double) r * r;
        double x = p.posX, y = p.posY, z = p.posZ;
        AxisAlignedBB box = new AxisAlignedBB(x - r, y - r, z - r, x + r, y + r, z + r);

        List<Entity> ents = level.getEntitiesWithinAABB(EntityLivingBase.class, box);
        if (ents.isEmpty()) {
            inside.clear();
            return;
        }

        Set<String> allow = new HashSet<>();
        for (String s : Config.getMobs()) {
            if (s != null && !s.trim().isEmpty()) {
                allow.add(s.toLowerCase(Locale.ROOT));
            }
        }

        scratch.clear();
        for (Entity en : ents) {
            if (!isHostile(en)) continue;
            if (!en.isEntityAlive()) continue;

            double d2 = en.getDistanceSq(p);
            if (d2 > r2) continue;

            String typeId = Config.entityId(en);
            String name = Config.entityName(en);
            // MateEngine prints this value directly, so localize it. The raw
            // registry path above stays in use for allowlist matching.
            String display = Names.entity(en, name);

            if (!allow.isEmpty()) {
                String ln = name.toLowerCase(Locale.ROOT);
                String lid = typeId.toLowerCase(Locale.ROOT);
                if (!allow.contains(ln) && !allow.contains(lid)) continue;
            }

            int id = en.getEntityId();
            scratch.add(id);

            if (!inside.contains(id)) {
                inside.add(id);
                long now = System.currentTimeMillis();
                if (now >= nextMobMsgAt) {
                    int dist = (int) Math.floor(Math.sqrt(d2));
                    send("{\"type\":\"mob_proximity\",\"phase\":\"enter\",\"uuid\":\"" + id
                            + "\",\"id\":\"" + typeId + "\",\"name\":\"" + display
                            + "\",\"distance\":" + dist + ",\"ts\":" + now + "}");
                    nextMobMsgAt = now + MOB_DISCOVER_COOLDOWN_MS;
                }
            }
        }

        if (!inside.isEmpty()) {
            inside.retainAll(scratch);
        }
    }

    /** 1.12.2 equivalent of {@code MobCategory.MONSTER}. */
    static boolean isHostile(Entity en) {
        if (en instanceof IMob) {
            return true;
        }
        return en instanceof EntityLivingBase
                && ((EntityLivingBase) en).getCreatureAttribute() == EnumCreatureAttribute.UNDEAD;
    }

    private void trackAndConfirmKills(World level, EntityPlayer p) {
        if (!Config.get(OPT_KILL)) {
            return;
        }
        long now = System.currentTimeMillis();
        double radius = Math.max(16, Math.min(64, Config.getRadius() + 8));
        AxisAlignedBB area = new AxisAlignedBB(
                p.posX - radius, p.posY - radius, p.posZ - radius,
                p.posX + radius, p.posY + radius, p.posZ + radius);

        List<EntityLivingBase> nearby = level.getEntitiesWithinAABB(EntityLivingBase.class, area);

        for (EntityLivingBase le : nearby) {
            if (!isHostile(le)) continue;

            int id = le.getEntityId();
            float hp = le.getHealth();
            Float prev = lastHp.get(id);

            String typeId = Config.entityId(le);
            String name = Config.entityName(le);
            String display = Names.entity(le, name);

            lastTypeId.put(id, typeId);
            lastTypeName.put(id, name);
            lastSeen.put(id, now);

            boolean crossedToDead = prev != null && prev > 0.0f && (hp <= 1.0E-4f || le.isDead);
            if (crossedToDead) {
                deathFlagAt.put(id, now);
            }

            Long flaggedAt = deathFlagAt.get(id);
            if (flaggedAt != null && now - flaggedAt <= 1200L && killedByPlayer(p, le)) {
                if (now - lastKillMsgAt >= 800L && rng.nextFloat() < 0.30f) {
                    send("{\"type\":\"kill_confirm\",\"id\":\"" + typeId + "\",\"name\":\"" + display + "\"}");
                    lastKillMsgAt = now;
                }
                deathFlagAt.remove(id);
            }

            lastHp.put(id, hp);
        }

        if (!lastHp.isEmpty()) {
            Set<Integer> toDrop = new HashSet<>();
            for (Map.Entry<Integer, Float> e : lastHp.entrySet()) {
                int id = e.getKey();
                if (lastSeen.getOrDefault(id, 0L) == now) continue;
                Long flagged = deathFlagAt.get(id);
                if (flagged != null && now - flagged > 1500L) {
                    deathFlagAt.remove(id);
                }
                toDrop.add(id);
            }
            for (Integer id : toDrop) {
                lastHp.remove(id);
                lastSeen.remove(id);
                lastTypeId.remove(id);
                lastTypeName.remove(id);
            }
        }
    }

    private static boolean killedByPlayer(EntityPlayer p, EntityLivingBase victim) {
        DamageSource src = victim.getLastDamageSource();
        if (src == null) {
            return false;
        }
        Entity cause = src.getTrueSource();
        Entity direct = src.getImmediateSource();
        if (cause == p || direct == p) {
            return true;
        }
        // Arrows and thrown potions keep the shooter as the true source.
        return cause == p;
    }

    /**
     * Detects "player finished crafting" from the container state machine: a
     * stack was briefly held on the cursor and released while the menu state
     * advanced. Matches the heuristic used by the modern ports.
     */
    private void observeCrafting(Minecraft mc, EntityPlayer p) {
        GuiScreen scr = mc.currentScreen;
        boolean inCraftingUi = scr instanceof GuiCrafting || scr instanceof GuiInventory;
        if (!Config.get(OPT_CRAFTING) || !inCraftingUi) {
            craftingObserved = false;
            lastMenuStateId = -1;
            lastCarriedNonEmpty = false;
            return;
        }

        if (p.openContainer == null) {
            return;
        }

        int sid = p.openContainer.getNextTransactionID(p.inventory);
        boolean carriedNow = !p.inventory.getItemStack().isEmpty();

        long now = System.currentTimeMillis();
        if (carriedNow && !lastCarriedNonEmpty) {
            lastCarriedBeganAt = now;
        }

        boolean carriedReleasedQuick = !carriedNow && lastCarriedNonEmpty
                && (now - lastCarriedBeganAt) <= 1500L;
        boolean stateChanged = lastMenuStateId != -1 && sid != lastMenuStateId;

        if (!craftingObserved && carriedReleasedQuick && stateChanged
                && now - lastCraftMsgAt > 1000L && rng.nextFloat() < 0.30f) {
            send("{\"type\":\"crafted\"}");
            lastCraftMsgAt = now;
            craftingObserved = true;
        }

        if (sid != lastMenuStateId) {
            craftingObserved = false;
        }

        lastCarriedNonEmpty = carriedNow;
        lastMenuStateId = sid;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void send(String json) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(b, b.length, TARGET);
            s.send(pkt);
        } catch (Exception ignored) {
            // MateEngine is optional; never let a socket error reach the game loop.
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    /** Sends an arbitrary payload; used by the config screen test button. */
    static void sendRaw(String json) {
        send(json);
    }

    static void notifyPlayer(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            mc.player.sendStatusMessage(new TextComponentString(message), true);
        }
    }
}
