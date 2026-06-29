package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.google.gson.*;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MinehutScanModule extends Module {

    private enum State {
        WAITING_FOR_LOBBY,   // poll hotbar for any lobby item
        TRY_SLOTS,           // try right-clicking hotbar slots one-by-one until a GUI opens
        WAIT_COMPASS_GUI,    // wait for the server-browser AbstractContainerScreen to open
        FIND_RANDOM,         // click "Join a Random Server" button
        WAIT_SERVER_JOIN,    // wait for GameJoinedEvent after clicking random
        CLICK_BARRIER,       // on sub-server — wait for ANY screen, click first non-air slot
        WAIT_SETTLED,        // wait for world to load
        RUN_PLUGINS,         // send /plugins + /pl
        WAIT_PLUGINS,
        RUN_HELP,            // send /?
        WAIT_HELP,
        COLLECT_PLAYERS,
        RETURN,              // send /lobby
        WAIT_RETURN,
        SAVE_AND_REPEAT
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks between GUI interactions.")
        .defaultValue(20).min(5).max(200).build()
    );

    private final Setting<Integer> cmdTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("command-timeout")
        .description("Ticks of chat silence before assuming command output finished.")
        .defaultValue(80).min(20).max(400).build()
    );

    private final Setting<Integer> maxServers = sgGeneral.add(new IntSetting.Builder()
        .name("max-servers")
        .description("Max servers to scan (0 = unlimited).")
        .defaultValue(0).min(0).max(10000).build()
    );

    // -1 means auto-detect; 0-8 means fixed slot for the server-browser item.
    private final Setting<Integer> compassSlotOverride = sgGeneral.add(new IntSetting.Builder()
        .name("lobby-item-slot")
        .description("Hotbar slot (0-8) that opens the server browser. -1 = try all slots.")
        .defaultValue(-1).min(-1).max(8).build()
    );

    // ── Runtime state ──────────────────────────────────────────────────────────

    private State state;
    private int ticks;
    private int scanned;

    // Which hotbar slot we are currently trying in TRY_SLOTS.
    private int trySlotsIndex;
    // All non-empty hotbar slot indices found in the lobby.
    private final List<Integer> lobbySlots = new ArrayList<>();

    private final List<String> pluginLines   = new ArrayList<>();
    private final List<String> helpLines     = new ArrayList<>();
    private final LinkedHashSet<String> players = new LinkedHashSet<>();
    private String serverName = "unknown";

    private boolean collectingPlugins;
    private boolean collectingHelp;
    private int pluginSilenceTicks;
    private int helpSilenceTicks;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private JsonArray db;
    private Path dbPath;

    // ── Constructor ────────────────────────────────────────────────────────────

    public MinehutScanModule() {
        super(AddonTemplate.CATEGORY, "minehut-scanner",
            "Scans Minehut servers: lobby item → random server → barrier → /plugins → /? → /lobby → repeat.");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        dbPath = mc.gameDirectory.toPath().resolve("minehut-scanner-db.json");
        loadDb();
        scanned = 0;
        transitionTo(State.WAITING_FOR_LOBBY);
        info("MinehutScanner active. Scanning hotbar for lobby items...");
    }

    @Override
    public void onDeactivate() {
        collectingPlugins = false;
        collectingHelp = false;
        saveDb();
        info("Stopped. " + scanned + " server(s) saved to " + dbPath.getFileName());
    }

    // ── Events ─────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        ticks++;
        if (collectingPlugins) pluginSilenceTicks++;
        if (collectingHelp)    helpSilenceTicks++;

        switch (state) {
            case WAITING_FOR_LOBBY -> tickWaitingForLobby();
            case TRY_SLOTS         -> tickTrySlots();
            case WAIT_COMPASS_GUI  -> tickWaitCompassGui();
            case FIND_RANDOM       -> tickFindRandom();
            case WAIT_SERVER_JOIN  -> tickWaitServerJoin();
            case CLICK_BARRIER     -> tickClickBarrier();
            case WAIT_SETTLED      -> tickWaitSettled();
            case RUN_PLUGINS       -> tickRunPlugins();
            case WAIT_PLUGINS      -> tickWaitPlugins();
            case RUN_HELP          -> tickRunHelp();
            case WAIT_HELP         -> tickWaitHelp();
            case COLLECT_PLAYERS   -> tickCollectPlayers();
            case RETURN            -> tickReturn();
            case WAIT_RETURN       -> tickWaitReturn();
            case SAVE_AND_REPEAT   -> tickSaveAndRepeat();
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        AddonTemplate.LOG.info("[MinehutScanner] GameJoinedEvent fired — current state={}", state);
        if (state == State.WAIT_RETURN || state == State.SAVE_AND_REPEAT) {
            transitionTo(State.SAVE_AND_REPEAT);
        } else if (state == State.WAIT_SERVER_JOIN
                || state == State.TRY_SLOTS
                || state == State.WAIT_COMPASS_GUI
                || state == State.FIND_RANDOM) {
            info("Joined sub-server. Waiting for barrier GUI...");
            transitionTo(State.CLICK_BARRIER);
        } else if (state != State.WAITING_FOR_LOBBY) {
            AddonTemplate.LOG.info("[MinehutScanner] Unexpected join in state={} — going to CLICK_BARRIER", state);
            transitionTo(State.CLICK_BARRIER);
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!collectingPlugins && !collectingHelp) return;

        String msg = null;
        if (event.packet instanceof ClientboundSystemChatPacket p) {
            msg = p.content().getString();
        } else if (event.packet instanceof ClientboundDisguisedChatPacket p) {
            msg = p.message().getString();
        } else if (event.packet instanceof ClientboundPlayerChatPacket p) {
            var unsigned = p.unsignedContent();
            msg = unsigned != null ? unsigned.getString() : p.body().content();
        }

        if (msg == null || msg.isBlank()) return;
        AddonTemplate.LOG.info("[MinehutScanner] Chat ({} {}) captured: {}",
            collectingPlugins ? "PLUGINS" : "", collectingHelp ? "HELP" : "", msg.strip());
        if (collectingPlugins) { pluginLines.add(msg.strip()); pluginSilenceTicks = 0; }
        if (collectingHelp)    { helpLines.add(msg.strip());   helpSilenceTicks   = 0; }
    }

    // ── State handlers ─────────────────────────────────────────────────────────

    private void tickWaitingForLobby() {
        // ── Every tick: check if the server browser GUI is already open ─────────
        // Minehut may auto-open it when you join the lobby, or it may have been
        // left open from a previous iteration.
        if (mc.screen instanceof AbstractContainerScreen<?> scr) {
            for (Slot slot : scr.getMenu().slots) {
                String name = slot.getItem().getHoverName().getString().toLowerCase();
                if (name.contains("random") || name.contains("join a random")) {
                    AddonTemplate.LOG.info("[MinehutScanner] Server browser already open! Jumping to FIND_RANDOM.");
                    info("Server browser open — jumping to Find Random.");
                    transitionTo(State.FIND_RANDOM);
                    return;
                }
            }
        }

        // ── After 2 seconds: detect if we're stuck on a sub-server ───────────────
        // Sub-server hotbar: "Right click to continue" in slot 2 (index 2)
        // with NO PlayerHead in slot 1 (the lobby PlayerHead disappears after ~2s).
        if (ticks > 40) {
            ItemStack hotbarSlot1 = mc.player.getInventory().getItem(1);
            ItemStack hotbarSlot2 = mc.player.getInventory().getItem(2);
            boolean hasLobbyHead = !hotbarSlot1.isEmpty()
                && hotbarSlot1.getItem().getClass().getSimpleName().equals("PlayerHeadItem");
            boolean hasBarrier = !hotbarSlot2.isEmpty()
                && hotbarSlot2.getHoverName().getString().toLowerCase().contains("continue");

            if (hasBarrier && !hasLobbyHead) {
                AddonTemplate.LOG.info("[MinehutScanner] Sub-server detected: barrier in slot 2, no lobby PlayerHead. Going to CLICK_BARRIER.");
                info("Detected sub-server — clicking barrier.");
                transitionTo(State.CLICK_BARRIER);
                return;
            }
        }

        // ── Every tick: check slot 0 for the "Find a Server" compass ────────────
        // The compass appears very briefly in slot 0 when joining the hub.
        ItemStack slot0now = mc.player.getInventory().getItem(0);
        if (!slot0now.isEmpty()) {
            String n0 = slot0now.getHoverName().getString().toLowerCase();
            AddonTemplate.LOG.info("[MinehutScanner] Slot 0 is non-empty: {}/{}", slot0now.getItem().getClass().getSimpleName(), slot0now.getHoverName().getString());
            if (n0.contains("find") || n0.contains("server") || n0.contains("browse")
                    || n0.contains("play") || n0.contains("compass") || n0.contains("random")) {
                AddonTemplate.LOG.info("[MinehutScanner] 'Find a Server' compass found in slot 0!");
                info("Found server browser compass in slot 0: " + slot0now.getHoverName().getString());
                lobbySlots.clear();
                lobbySlots.add(0);
                trySlotsIndex = 0;
                transitionTo(State.TRY_SLOTS);
                return;
            }
        }

        // ── Every 20 ticks: dump hotbar ───────────────────────────────────────────
        if (ticks % 20 != 0) return;

        StringBuilder hotbar = new StringBuilder("[MinehutScanner] Hotbar:");
        lobbySlots.clear();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            String entry;
            if (stack.isEmpty()) {
                entry = "EMPTY";
            } else {
                entry = stack.getItem().getClass().getSimpleName()
                        + "/\"" + stack.getHoverName().getString() + "\"";
                lobbySlots.add(i);
            }
            hotbar.append(" [").append(i).append(":").append(entry).append("]");
        }
        AddonTemplate.LOG.info(hotbar.toString());
        AddonTemplate.LOG.info("[MinehutScanner] WAITING_FOR_LOBBY tick={} nonEmptySlots={}", ticks, lobbySlots);

        // Override: user specified a slot manually in settings.
        if (compassSlotOverride.get() >= 0) {
            int ov = compassSlotOverride.get();
            if (!mc.player.getInventory().getItem(ov).isEmpty()) {
                AddonTemplate.LOG.info("[MinehutScanner] Using override slot {}", ov);
                lobbySlots.clear();
                lobbySlots.add(ov);
                trySlotsIndex = 0;
                transitionTo(State.TRY_SLOTS);
                return;
            }
        }

        // Fallback every 5 seconds: try clicking the PlayerHead in slot 1.
        // Clicking it opens a Minehut ad (BookViewScreen). WAIT_COMPASS_GUI will
        // close that ad and wait to see if the server browser appears afterward.
        if (ticks % 100 == 0 && ticks > 0) {
            ItemStack slot1 = mc.player.getInventory().getItem(1);
            if (!slot1.isEmpty() && slot1.getItem().getClass().getSimpleName().equals("PlayerHeadItem")) {
                AddonTemplate.LOG.info("[MinehutScanner] Retry: clicking PlayerHead in slot 1 (hoping server browser opens after ad)");
                info("Trying PlayerHead in slot 1 to open server browser...");
                lobbySlots.clear();
                lobbySlots.add(1);
                trySlotsIndex = 0;
                transitionTo(State.TRY_SLOTS);
                return;
            }
            info("Waiting for server browser. Open 'Find a Server' in Minehut manually if needed.");
        }
    }

    private void tickTrySlots() {
        // Wait a bit before each attempt.
        if (ticks < actionDelay.get()) return;

        // If a GUI already opened (e.g. from a previous click), go straight to FIND_RANDOM.
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            AddonTemplate.LOG.info("[MinehutScanner] Server browser GUI already open. Proceeding to FIND_RANDOM.");
            transitionTo(State.FIND_RANDOM);
            return;
        }

        // Decide which slot to try.
        if (compassSlotOverride.get() >= 0) {
            // Fixed slot override.
            int slot = compassSlotOverride.get();
            AddonTemplate.LOG.info("[MinehutScanner] TRY_SLOTS: clicking override slot {}", slot);
            clickHotbarSlot(slot);
            transitionTo(State.WAIT_COMPASS_GUI);
            return;
        }

        if (trySlotsIndex >= lobbySlots.size()) {
            AddonTemplate.LOG.warn("[MinehutScanner] Tried all {} lobby slots, none opened a GUI. Retrying from lobby.", lobbySlots.size());
            info("No slot opened a server browser. Retrying...");
            transitionTo(State.WAITING_FOR_LOBBY);
            return;
        }

        int slot = lobbySlots.get(trySlotsIndex);
        ItemStack stack = mc.player.getInventory().getItem(slot);
        AddonTemplate.LOG.info("[MinehutScanner] TRY_SLOTS: trying slot {} ({}) index {}/{}",
            slot, stack.getHoverName().getString(), trySlotsIndex + 1, lobbySlots.size());
        info("Trying slot " + slot + ": " + stack.getHoverName().getString());
        clickHotbarSlot(slot);
        trySlotsIndex++;
        transitionTo(State.WAIT_COMPASS_GUI);
    }

    private void tickWaitCompassGui() {
        String screenClass = mc.screen == null ? "null" : mc.screen.getClass().getSimpleName();
        if (ticks % 5 == 0) {
            AddonTemplate.LOG.info("[MinehutScanner] WAIT_COMPASS_GUI tick={} screen={}", ticks, screenClass);
        }

        if (mc.screen instanceof AbstractContainerScreen<?>) {
            AddonTemplate.LOG.info("[MinehutScanner] Server browser opened: {}", screenClass);
            info("Server browser opened: " + screenClass);
            transitionTo(State.FIND_RANDOM);
            return;
        }

        // Give it 2 seconds to open. If nothing, try next slot.
        if (ticks > 40) {
            AddonTemplate.LOG.info("[MinehutScanner] Slot didn't open GUI after 40 ticks. screen={}", screenClass);
            // Close any non-container screen that might have opened.
            if (mc.screen != null) {
                AddonTemplate.LOG.info("[MinehutScanner] Closing unexpected screen: {}", mc.screen.getClass().getName());
                mc.setScreen(null);
            }
            transitionTo(State.TRY_SLOTS);
        }
    }

    private void tickFindRandom() {
        if (ticks < actionDelay.get()) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> scr)) {
            String screenClass = mc.screen == null ? "null" : mc.screen.getClass().getSimpleName();
            AddonTemplate.LOG.warn("[MinehutScanner] FIND_RANDOM: lost GUI. screen={}", screenClass);
            if (ticks > actionDelay.get() * 4) transitionTo(State.WAITING_FOR_LOBBY);
            return;
        }

        // Log all slots on first check.
        if (ticks == actionDelay.get()) {
            StringBuilder sb = new StringBuilder("[MinehutScanner] Server browser slots:");
            for (Slot s : scr.getMenu().slots) {
                if (!s.getItem().isEmpty())
                    sb.append(" [").append(s.index).append(":")
                      .append(s.getItem().getHoverName().getString()).append("]");
            }
            AddonTemplate.LOG.info(sb.toString());
        }

        for (Slot slot : scr.getMenu().slots) {
            String name = slot.getItem().getHoverName().getString().toLowerCase();
            if (name.contains("random") || name.contains("join") || name.contains("play")) {
                AddonTemplate.LOG.info("[MinehutScanner] Found random button: slot={} name={}",
                    slot.index, slot.getItem().getHoverName().getString());
                info("Clicking: " + slot.getItem().getHoverName().getString());
                containerClick(scr.getMenu(), slot.index, 0);
                transitionTo(State.WAIT_SERVER_JOIN);
                return;
            }
        }

        if (ticks > actionDelay.get() * 6) {
            AddonTemplate.LOG.warn("[MinehutScanner] No random button found. Closing GUI.");
            info("No 'random' button found. Closing...");
            mc.setScreen(null);
            transitionTo(State.WAITING_FOR_LOBBY);
        }
    }

    private void tickWaitServerJoin() {
        if (ticks % 20 == 0) {
            AddonTemplate.LOG.info("[MinehutScanner] WAIT_SERVER_JOIN tick={}", ticks);
        }
        // Allow up to 30 seconds — some Minehut servers are slow to start.
        if (ticks > 600) {
            AddonTemplate.LOG.warn("[MinehutScanner] Timed out waiting for server join (600 ticks)");
            transitionTo(State.WAITING_FOR_LOBBY);
        }
    }

    private void tickClickBarrier() {
        // The barrier "Right click to continue" is a HOTBAR ITEM (slot 3), not a GUI.
        // Log the full hotbar every 5 ticks so we can track when it appears.
        if (ticks % 5 == 0) {
            StringBuilder hotbar = new StringBuilder("[MinehutScanner] CLICK_BARRIER tick=" + ticks + " hotbar:");
            for (int i = 0; i < 9; i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                hotbar.append(" [").append(i).append(":")
                      .append(s.isEmpty() ? "AIR" : s.getItem().getClass().getSimpleName() + "/\"" + s.getHoverName().getString() + "\"")
                      .append("]");
            }
            AddonTemplate.LOG.info(hotbar.toString());
        }

        // Priority 1: check hotbar index 2 (displayed as UI slot 3 — user-confirmed barrier location).
        ItemStack slot2 = mc.player.getInventory().getItem(2);
        if (!slot2.isEmpty()) {
            AddonTemplate.LOG.info("[MinehutScanner] Slot index 2 (UI slot 3) has item: {} — right-clicking as barrier",
                slot2.getHoverName().getString());
            info("Right-clicking barrier (UI slot 3 / index 2): " + slot2.getHoverName().getString());
            mc.player.getInventory().selected = 2;
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            transitionTo(State.WAIT_SETTLED);
            return;
        }

        // Priority 2: scan all hotbar slots for an item named "continue" or a barrier block.
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().toLowerCase();
            if (stack.is(Items.BARRIER) || name.contains("continue")) {
                AddonTemplate.LOG.info("[MinehutScanner] Found barrier-like item in slot {}: {}",
                    i, stack.getHoverName().getString());
                info("Right-clicking barrier (slot " + i + "): " + stack.getHoverName().getString());
                mc.player.getInventory().selected = i;
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                transitionTo(State.WAIT_SETTLED);
                return;
            }
        }

        // Allow up to 15 seconds for the barrier to appear in the hotbar.
        if (ticks > 300) {
            AddonTemplate.LOG.warn("[MinehutScanner] Barrier never appeared in hotbar after 300 ticks. Proceeding anyway.");
            info("Barrier not found after 15s. Proceeding...");
            transitionTo(State.WAIT_SETTLED);
        }
    }

    private void tickWaitSettled() {
        // Log hotbar every 10 ticks to track inventory changes post-BungeeCord.
        if (ticks % 10 == 0) {
            StringBuilder hotbar = new StringBuilder("[MinehutScanner] WAIT_SETTLED tick=" + ticks + " hotbar:");
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                hotbar.append(" [").append(i).append(":")
                      .append(stack.isEmpty() ? "AIR" : "\"" + stack.getHoverName().getString() + "\"")
                      .append("]");
            }
            AddonTemplate.LOG.info(hotbar.toString());
        }

        // Wait at least 3 seconds (60 ticks) before checking lobby items.
        // BungeeCord transfers can briefly leave old lobby inventory visible.
        if (ticks < 60) return;

        // If a lobby PlayerHead is present, we ended up back in the lobby.
        if (findLobbySlot() >= 0) {
            AddonTemplate.LOG.info("[MinehutScanner] Lobby item detected in WAIT_SETTLED at tick={} — returning to lobby state", ticks);
            transitionTo(State.WAITING_FOR_LOBBY);
            return;
        }

        serverName = resolveServerName();
        AddonTemplate.LOG.info("[MinehutScanner] Settled on server '{}' at tick={}", serverName, ticks);
        pluginLines.clear(); helpLines.clear(); players.clear();
        transitionTo(State.RUN_PLUGINS);
    }

    private void tickRunPlugins() {
        if (ticks < 5) return;
        AddonTemplate.LOG.info("[MinehutScanner] Sending /plugins and /pl");
        pluginSilenceTicks = 0;
        collectingPlugins = true;
        mc.player.connection.sendCommand("plugins");
        mc.player.connection.sendCommand("pl");
        transitionTo(State.WAIT_PLUGINS);
    }

    private void tickWaitPlugins() {
        if (ticks % 20 == 0) {
            AddonTemplate.LOG.info("[MinehutScanner] WAIT_PLUGINS tick={} silence={} lines={}", ticks, pluginSilenceTicks, pluginLines.size());
        }
        if (pluginSilenceTicks >= cmdTimeout.get()) {
            AddonTemplate.LOG.info("[MinehutScanner] Plugin collection done: {} lines", pluginLines.size());
            collectingPlugins = false;
            transitionTo(State.RUN_HELP);
        }
    }

    private void tickRunHelp() {
        if (ticks < 5) return;
        AddonTemplate.LOG.info("[MinehutScanner] Sending /?");
        helpSilenceTicks = 0;
        collectingHelp = true;
        mc.player.connection.sendCommand("?");
        transitionTo(State.WAIT_HELP);
    }

    private void tickWaitHelp() {
        if (ticks % 20 == 0) {
            AddonTemplate.LOG.info("[MinehutScanner] WAIT_HELP tick={} silence={} lines={}", ticks, helpSilenceTicks, helpLines.size());
        }
        if (helpSilenceTicks >= cmdTimeout.get()) {
            AddonTemplate.LOG.info("[MinehutScanner] Help collection done: {} lines", helpLines.size());
            collectingHelp = false;
            transitionTo(State.COLLECT_PLAYERS);
        }
    }

    private void tickCollectPlayers() {
        if (ticks < 5) return;
        if (mc.getConnection() != null) {
            mc.getConnection().getOnlinePlayers().forEach(p -> {
                try {
                    String name = p.getProfile().name();
                    if (name != null && !name.isBlank()) players.add(name);
                } catch (Exception ignored) {}
            });
        }
        AddonTemplate.LOG.info("[MinehutScanner] Collected {} players: {}", players.size(), players);
        transitionTo(State.RETURN);
    }

    private void tickReturn() {
        if (ticks < 5) return;
        AddonTemplate.LOG.info("[MinehutScanner] Sending /lobby");
        mc.player.connection.sendCommand("lobby");
        transitionTo(State.WAIT_RETURN);
    }

    private void tickWaitReturn() {
        if (ticks % 40 == 0) {
            AddonTemplate.LOG.info("[MinehutScanner] WAIT_RETURN tick={}", ticks);
        }
        if (ticks == 300) {
            AddonTemplate.LOG.info("[MinehutScanner] Sending /hub fallback");
            mc.player.connection.sendCommand("hub");
        } else if (ticks > 600) {
            AddonTemplate.LOG.error("[MinehutScanner] Failed to return to lobby. Stopping.");
            info("Could not return to lobby. Stopping.");
            toggle();
        }
    }

    private void tickSaveAndRepeat() {
        scanned++;
        AddonTemplate.LOG.info("[MinehutScanner] Saving #{}: name={} plugins={} help={} players={}",
            scanned, serverName, pluginLines.size(), helpLines.size(), players.size());
        saveEntry();
        info("Saved #" + scanned + " — \"" + serverName + "\""
            + "  plugins=" + pluginLines.size()
            + "  players=" + players.size());
        if (maxServers.get() > 0 && scanned >= maxServers.get()) {
            info("Reached limit of " + maxServers.get() + ". Stopping.");
            toggle();
            return;
        }
        transitionTo(State.WAITING_FOR_LOBBY);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void transitionTo(State next) {
        AddonTemplate.LOG.info("[MinehutScanner] {} → {}", state, next);
        state = next;
        ticks = 0;
    }

    /** Click a hotbar slot (select it and right-use). */
    private void clickHotbarSlot(int slot) {
        mc.player.getInventory().selected = slot;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        AddonTemplate.LOG.info("[MinehutScanner] Right-used hotbar slot {}: {}",
            slot, mc.player.getInventory().getItem(slot).getHoverName().getString());
    }

    /** Click a slot inside an already-open container menu. */
    private void containerClick(AbstractContainerMenu menu, int slotIndex, int button) {
        mc.gameMode.handleContainerInput(
            menu.containerId, slotIndex, button, ContainerInput.PICKUP, mc.player
        );
    }

    /**
     * Returns the first hotbar slot that looks like a Minehut lobby item:
     * - Any PlayerHead (Minehut uses these for server icons/buttons)
     * - Any compass or item with server-browser keywords
     */
    private int findLobbySlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String itemClass = stack.getItem().getClass().getSimpleName();
            String name = stack.getHoverName().getString().toLowerCase();
            // PlayerHeadItem is the Minehut lobby UI item in current versions.
            if (itemClass.equals("PlayerHeadItem")) return i;
            // Keywords for compass/server-browser items.
            if (stack.is(Items.COMPASS)
                    || name.contains("find")
                    || name.contains("server")
                    || name.contains("navigate")
                    || name.contains("lobby")
                    || name.contains("minehut")
                    || name.contains("join")
                    || name.contains("play")
                    || name.contains("random")) {
                return i;
            }
        }
        return -1;
    }

    private String resolveServerName() {
        try {
            if (mc.getConnection() != null) {
                String brand = mc.getConnection().serverBrand();
                if (brand != null && !brand.isBlank()) return brand.trim();
            }
        } catch (Exception ignored) {}
        return "server-" + (scanned + 1);
    }

    // ── JSON persistence ───────────────────────────────────────────────────────

    private void saveEntry() {
        JsonObject entry = new JsonObject();
        entry.addProperty("scan_number", scanned);
        entry.addProperty("name", serverName);
        entry.addProperty("timestamp",
            DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()));
        JsonArray pluginsArr = new JsonArray();
        pluginLines.forEach(pluginsArr::add);
        entry.add("plugins", pluginsArr);
        JsonArray helpArr = new JsonArray();
        helpLines.forEach(helpArr::add);
        entry.add("help_output", helpArr);
        JsonArray playersArr = new JsonArray();
        players.forEach(playersArr::add);
        entry.add("players", playersArr);
        db.add(entry);
        saveDb();
    }

    private void loadDb() {
        db = new JsonArray();
        if (Files.exists(dbPath)) {
            try (Reader r = Files.newBufferedReader(dbPath)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el.isJsonArray()) {
                    db = el.getAsJsonArray();
                    info("Loaded existing DB: " + db.size() + " entries.");
                }
            } catch (Exception e) {
                AddonTemplate.LOG.warn("Could not read scanner DB: {}", e.getMessage());
            }
        }
    }

    private void saveDb() {
        try (Writer w = Files.newBufferedWriter(dbPath)) {
            gson.toJson(db, w);
        } catch (Exception e) {
            AddonTemplate.LOG.error("Could not save scanner DB: {}", e.getMessage());
        }
    }
}
