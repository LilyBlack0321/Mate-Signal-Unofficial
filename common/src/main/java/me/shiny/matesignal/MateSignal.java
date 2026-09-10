package me.shiny.matesignal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
//#if 1.20.1
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.ForgeRegistries;
//#else
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
//#endif

import com.mojang.brigadier.arguments.StringArgumentType;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * MateSignal for Minecraft 1.20.1 / Forge 47.x.
 * <p>
 * Client-only bridge that watches for notable in-game moments and reports them
 * to the MateEngine desktop avatar as small JSON datagrams on
 * {@code 127.0.0.1:32145}.
 * <p>
 * Behaviour is kept byte-for-byte compatible with MateSignal 1.1.0 (the
 * official 1.21.1 Forge build) so that both can talk to the same MateEngine
 * instance.
 */
@Mod(MateSignal.MOD_ID)
public class MateSignal {
    public static final String MOD_ID = "matesignal";

    /** MateEngine listens here for avatar reaction events. */
    private static final InetSocketAddress TARGET = new InetSocketAddress("127.0.0.1", 32145);

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

    private static final Set<Integer> inside = new HashSet<>();
    private static final Set<Integer> scratch = new HashSet<>();

    private Object lastDim = null;

    private boolean wasLowHp = false;
    private boolean wasLowHunger = false;

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
    private final Deque<String> testQueue = new ArrayDeque<>();
    private long nextTestAt = 0L;

//#if 1.20.1
    public MateSignal() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new MateSignalConfigScreen(parent)));

        // Forge 47 exposes the client tick through the classic event-bus form
        // (TickEvent.ClientTickEvent on the global forge bus); the ".Post"
        // sub-event only exists from 1.21 onwards.
        MinecraftForge.EVENT_BUS.register(this);
    }
//#else
    public MateSignal(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(
                net.neoforged.fml.config.ModConfig.Type.CLIENT, Config.SPEC);
        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (IConfigScreenFactory) (mc, parent) -> new MateSignalConfigScreen(parent));

        // NeoForge has two buses and they are not interchangeable: the mod bus
        // accepts only IModBusEvent subclasses, while gameplay events such as
        // LevelTickEvent.Post live on the game bus.
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }
//#endif

    /**
     * Registers {@code /matesignal}, a client-only command for exercising the
     * protocol on demand. Client commands are dispatched locally, so this works
     * in singleplayer and on servers alike (it never reaches the server, and no
     * server permissions are involved).
     */
//#if 1.20.1
    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
//#else
    private void onRegisterCommands(RegisterClientCommandsEvent event) {
//#endif
        event.getDispatcher().register(
                Commands.literal("matesignal")
                        .then(Commands.literal("test")
                                .executes(ctx -> runTest(ctx.getSource(), "all"))
                                .then(Commands.argument("event", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> {
                                            String rest = builder.getRemainingLowerCase();
                                            for (String n : TestPayloads.names()) {
                                                if (n.startsWith(rest)) builder.suggest(n);
                                            }
                                            builder.suggest("all");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> runTest(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "event")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    reply(ctx.getSource(), "MateSignal events (" + TestPayloads.size() + "): "
                                            + String.join(", ", TestPayloads.names()));
                                    reply(ctx.getSource(), "Usage: /matesignal test <event|all>");
                                    return 1;
                                })));
    }

    private int runTest(CommandSourceStack source, String arg) {
        testQueue.clear();
        nextTestAt = 0L;

        String wanted = arg == null ? "all" : arg.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || wanted.equals("all")) {
            for (String n : TestPayloads.names()) testQueue.add(resolveTest(n));
            reply(source, "MateSignal: sending all " + testQueue.size()
                    + " events, one per second. Watch MateEngine.");
            return testQueue.size();
        }

        String payload = resolveTest(wanted);
        if (payload == null) {
            List<String> candidates = TestPayloads.matching(wanted);
            if (candidates.isEmpty()) {
                reply(source, "MateSignal: unknown event '" + wanted + "'. Try /matesignal list");
                return 0;
            }
            // A prefix that matches several events sends them all, shortest first.
            for (String n : candidates) testQueue.add(resolveTest(n));
            reply(source, "MateSignal: '" + wanted + "' matches " + candidates.size()
                    + " events: " + String.join(", ", candidates));
            return candidates.size();
        }

        testQueue.add(payload);
        reply(source, "MateSignal: sending '" + wanted + "'");
        return 1;
    }

    /**
     * Renders a test event with the same language-aware name lookups the live
     * detection uses, so the command cannot disagree with normal play.
     */
    private String resolveTest(String eventName) {
        Minecraft mc = Minecraft.getInstance();
        return TestPayloads.resolve(eventName, mc == null ? null : mc.player);
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

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

//#if 1.20.1
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
//#else
    private void onLevelTick(LevelTickEvent.Post event) {
//#endif
//#if 1.20.1
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        onClientTick();
//#else
        if (!event.getLevel().isClientSide()) {
            return;
        }
        onClientTick();
//#endif
    }

    private void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;

        // The test command works even outside a world so the avatar can be
        // checked straight from the main menu.
        tickTestQueue();

        if (p == null || level == null) {
            resetTransientState();
            return;
        }

        if (lastDim == null || lastDim != level.dimension()) {
            inside.clear();
            lastWorldTime = -1L;
            lastDim = level.dimension();
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

    private long lastWorldTime = -1L;

    /**
     * Advances the day/night tracker.
     * <p>
     * Only a tick-consistent time advance counts as a crossing. World time also
     * jumps on world join, {@code /time set}, and dimension travel; treating
     * those as crossings fired bogus "night is coming" messages, so any jump
     * larger than {@link #MAX_TIME_ADVANCE_PER_TICK} is treated as a resync
     * instead of a crossing.
     */
    private void tickDayNight(ClientLevel level) {
        // getGameTime() always advances; getDayTime() freezes at night, so it
        // cannot be used to measure how much time actually elapsed.
        long worldTime = level.getGameTime();
        long dayTicks = level.getDayTime() % 24000L;

        if (lastWorldTime >= 0L) {
            long elapsed = worldTime - lastWorldTime;
            if (elapsed >= 1L && elapsed <= MAX_TIME_ADVANCE_PER_TICK) {
                if (Config.DAY_MESSAGE.get() && crossed(dayTicks, 23500L, elapsed)) {
                    send("{\"type\":\"time_day\"}");
                }
                if (Config.NIGHT_MESSAGE.get() && crossed(dayTicks, 12750L, elapsed)) {
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

    private void tickVitals(LocalPlayer p) {
        ItemStack use = p.getUseItem();
//#if 1.20.1
        boolean usingFoodNow = p.isUsingItem() && !use.isEmpty() && use.isEdible();
//#else
        boolean usingFoodNow = p.isUsingItem() && !use.isEmpty()
                && use.has(net.minecraft.core.component.DataComponents.FOOD);
//#endif
        int useTicksNow = p.getUseItemRemainingTicks();

        float hp = p.getHealth();
        boolean lowHp = hp <= 6.0f;
        if (Config.LOW_HEALTH_MESSAGE.get()) {
            long now = System.currentTimeMillis();
            if (lowHp && (!wasLowHp || now - lastLowHpMsgAt >= LOW_HP_COOLDOWN_MS)) {
                send("{\"type\":\"low_health\",\"hp\":" + String.format(Locale.ROOT, "%.1f", hp) + "}");
                lastLowHpMsgAt = now;
            }
        }
        wasLowHp = lowHp;

        FoodData food = p.getFoodData();
        int hunger = food.getFoodLevel();
        boolean lowHung = hunger < 10;
        if (Config.LOW_HUNGER_MESSAGE.get() && lowHung && !wasLowHunger) {
            send("{\"type\":\"low_hunger\",\"hunger\":" + hunger + "}");
        }

        if (Config.EAT_MESSAGE.get()) {
            long now = System.currentTimeMillis();
            if (wasUsingFood && !usingFoodNow && lastUseTicks <= 1) {
                // Finished chewing an item.
                if (now - lastEatMsgAt >= 800L && rng.nextFloat() < 0.30f) {
                    send("{\"type\":\"eat\"}");
                    lastEatMsgAt = now;
                }
            } else if (lastFoodLevel >= 0) {
                // Fallback: food/saturation went up even if the use animation was missed.
                boolean incFood = hunger > lastFoodLevel;
                boolean incSat = !incFood && food.getSaturationLevel() > lastSatLevel + 0.02f;
                if ((incFood || incSat) && now - lastEatMsgAt >= 800L && rng.nextFloat() < 0.30f) {
                    send("{\"type\":\"eat\"}");
                    lastEatMsgAt = now;
                }
            }
        }
        lastFoodLevel = hunger;
        lastSatLevel = food.getSaturationLevel();
        wasLowHunger = lowHung;
        wasUsingFood = usingFoodNow;
        lastUseTicks = useTicksNow;
    }

    private void tickEnvironment(LocalPlayer p, ClientLevel level) {
        boolean rainingNow = level.isRainingAt(p.blockPosition());
        if (Config.RAIN_START_MESSAGE.get() && rainingNow && !wasRaining) {
            send("{\"type\":\"rain_start\"}");
        }
        wasRaining = rainingNow;

        float hp = p.getHealth();
        boolean deadNow = p.isDeadOrDying() || hp <= 0.0f;
        if (Config.DEATH_MESSAGE.get() && deadNow && !wasDead) {
            send("{\"type\":\"death\"}");
        }
        wasDead = deadNow && hp <= 0.0f;

        int maxAir = p.getMaxAirSupply();
        int air = p.getAirSupply();
        boolean halfDrownNow = air <= (maxAir / 2) && air < maxAir && p.isUnderWater();
        if (Config.DROWNING_HALF_MESSAGE.get() && halfDrownNow && !wasHalfDrowning) {
            // MateEngine only matches "drowning" / "low_air" / "air_low"; the
            // upstream "drowning_half" name was silently ignored.
            send("{\"type\":\"drowning\",\"air\":" + air + ",\"max\":" + maxAir + "}");
        }
        wasHalfDrowning = air > (int) (maxAir * 0.8f) ? false : halfDrownNow;

        boolean sleepingNow = p.isSleeping();
        if (Config.SLEEP_MESSAGE.get() && sleepingNow && !wasSleeping) {
            send("{\"type\":\"sleep_start\"}");
        }
        wasSleeping = sleepingNow;
    }

    private void tickBiome(ClientLevel level, LocalPlayer p) {
        if (!Config.BIOME_MESSAGE.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBiomeCheckAt < 250L) {
            return;
        }
        lastBiomeCheckAt = now;

        ResourceLocation key = level.getBiome(p.blockPosition()).unwrapKey()
                .map(ResourceKey::location).orElse(null);
        String full = key == null ? "unknown" : key.toString();

        if (lastBiomeId == null) {
            lastBiomeId = full;
            return;
        }
        if (full.equals(lastBiomeId)) {
            return;
        }
        if (now >= biomeGraceUntil && now - lastBiomeMsgAt >= BIOME_MSG_COOLDOWN_MS) {
            // Localized name, so MateEngine never mixes languages mid-sentence.
            String display = key == null ? "unknown" : Names.biome(key);
            send("{\"type\":\"biome_discovery\",\"biome\":\"" + display + "\"}");
            lastBiomeMsgAt = now;
        }
        lastBiomeId = full;
    }

    private void tickMobProximity(ClientLevel level, LocalPlayer p) {
        int r = Math.max(3, Math.min(64, Config.RADIUS.get()));
        double r2 = (double) r * r;
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        AABB box = new AABB(x - r, y - r, z - r, x + r, y + r, z + r);

        //#if 1.20.1
        List<Entity> ents = level.getEntities(null, box);
//#else
        List<Entity> ents = level.getEntities((Entity) null, box);
//#endif
        if (ents.isEmpty()) {
            inside.clear();
            return;
        }

        Set<String> allow = new HashSet<>();
        List<? extends String> cfg = Config.MOBS.get();
        if (cfg != null) {
            for (String s : cfg) {
                if (s != null && !s.isBlank()) {
                    allow.add(s.toLowerCase(Locale.ROOT));
                }
            }
        }

        scratch.clear();
        for (Entity en : ents) {
            EntityType<?> type = en.getType();
            if (type.getCategory() != MobCategory.MONSTER) continue;
            if (!en.isAlive()) continue;

            double d2 = en.distanceToSqr(p);
            if (d2 > r2) continue;

            //#if 1.20.1
            ResourceLocation rid = ForgeRegistries.ENTITY_TYPES.getKey(type);
//#else
            ResourceLocation rid = BuiltInRegistries.ENTITY_TYPE.getKey(type);
//#endif
            if (rid == null) continue;
            String typeId = rid.toString();
            String name = rid.getPath();
            // MateEngine prints this value directly, so localize it.
            String display = Names.entity(type, name);

            if (!allow.isEmpty()) {
                String ln = name.toLowerCase(Locale.ROOT);
                String lid = typeId.toLowerCase(Locale.ROOT);
                if (!allow.contains(ln) && !allow.contains(lid)) continue;
            }

            int id = en.getId();
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

    /**
     * Watches monster health around the player and reports a confirmed kill once
     * the dying entity is verifiably attributed to the local player.
     */
    private void trackAndConfirmKills(Level level, Player p) {
        if (!Config.KILL_MESSAGE.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        double radius = Math.max(16, Math.min(64, Config.RADIUS.get() + 8));
        AABB area = new AABB(
                p.getX() - radius, p.getY() - radius, p.getZ() - radius,
                p.getX() + radius, p.getY() + radius, p.getZ() + radius);

        List<LivingEntity> nearby = level.getEntitiesOfClass(
                LivingEntity.class, area, le -> le.getType().getCategory() == MobCategory.MONSTER);

        for (LivingEntity le : nearby) {
            int id = le.getId();
            float hp = le.getHealth();
            Float prev = lastHp.get(id);

            //#if 1.20.1
            ResourceLocation rid = ForgeRegistries.ENTITY_TYPES.getKey(le.getType());
//#else
            ResourceLocation rid = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType());
//#endif
            String typeId = rid == null ? "unknown" : rid.toString();
            String name = rid == null ? "unknown" : Names.entity(le.getType(), rid.getPath());

            lastTypeId.put(id, typeId);
            lastTypeName.put(id, name);
            lastSeen.put(id, now);

            boolean crossedToDead = prev != null && prev > 0.0f && (hp <= 1.0E-4f || le.isDeadOrDying());
            if (crossedToDead) {
                deathFlagAt.put(id, now);
            }

            Long flaggedAt = deathFlagAt.get(id);
            if (flaggedAt != null && now - flaggedAt <= 1200L && killedByPlayer(p, le)) {
                if (now - lastKillMsgAt >= 800L && rng.nextFloat() < 0.30f) {
                    send("{\"type\":\"kill_confirm\",\"id\":\"" + typeId + "\",\"name\":\"" + name + "\"}");
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

    private static boolean killedByPlayer(Player p, LivingEntity victim) {
        if (victim.getKillCredit() == p) {
            return true;
        }
        DamageSource src = victim.getLastDamageSource();
        if (src == null) {
            return false;
        }
        Entity cause = src.getEntity();
        Entity direct = src.getDirectEntity();
        if (cause == p || direct == p) {
            return true;
        }
        return direct instanceof Projectile proj && proj.getOwner() == p;
    }

    /**
     * Detects "player finished crafting" from the container state machine:
     * a stack was briefly held on the cursor and released while the menu state
     * advanced. This is heuristic by design, matching upstream behaviour.
     */
    private void observeCrafting(Minecraft mc, Player p) {
        Screen scr = mc.screen;
        boolean inCraftingUi = scr instanceof CraftingScreen || scr instanceof InventoryScreen;
        if (!Config.CRAFTING_MESSAGE.get() || !inCraftingUi) {
            craftingObserved = false;
            lastMenuStateId = -1;
            lastCarriedNonEmpty = false;
            return;
        }

        AbstractContainerMenu menu = p.containerMenu;
        if (menu == null) {
            return;
        }

        int sid = menu.getStateId();
        boolean carriedNow = !menu.getCarried().isEmpty();

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

    private static void send(String json) {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(b, b.length, TARGET);
            s.send(pkt);
        } catch (Exception ignored) {
            // MateEngine is optional; never let a socket error reach the game loop.
        }
    }
}
