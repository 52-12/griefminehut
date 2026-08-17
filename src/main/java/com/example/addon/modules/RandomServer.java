package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.minehut.MinehutApi;
import com.example.addon.minehut.MinehutServer;
import com.example.addon.minehut.PlanTier;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class RandomServer extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgFilters = settings.createGroup("Filters");
    private final SettingGroup sgPlans = settings.createGroup("Plans");

    private final Setting<Boolean> onlyConnectable = sgGeneral.add(new BoolSetting.Builder()
        .name("only-connectable")
        .description("Only join servers that report as connectable.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cacheTtl = sgGeneral.add(new IntSetting.Builder()
        .name("cache-ttl")
        .description("How long in seconds to reuse the cached server list before refetching. 0 always refetches.")
        .defaultValue(60)
        .range(0, 600)
        .build()
    );

    private final Setting<Integer> maxRetries = sgGeneral.add(new IntSetting.Builder()
        .name("max-retries")
        .description("How many times to retry with a different server after a failed connection.")
        .defaultValue(3)
        .range(0, 10)
        .build()
    );

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Show a chat message naming the picked server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minPlayers = sgFilters.add(new IntSetting.Builder()
        .name("min-players")
        .description("Only join servers with at least this many players.")
        .defaultValue(0)
        .range(0, 100000)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Integer> maxPlayers = sgFilters.add(new IntSetting.Builder()
        .name("max-players")
        .description("Only join servers with at most this many players.")
        .defaultValue(1000)
        .range(0, 100000)
        .sliderMax(1000)
        .build()
    );

    private final Setting<List<String>> categories = sgFilters.add(new StringListSetting.Builder()
        .name("categories")
        .description("Only join servers matching these categories: pvp, smp, gens, box, farming, rpg, minigames, prison, rolePlay, lifesteal, modded, meme, skyblock, parkour, factions, puzzle, creative, old_pvp.")
        .defaultValue()
        .build()
    );

    private final Setting<Boolean> requireAllCategories = sgFilters.add(new BoolSetting.Builder()
        .name("require-all-categories")
        .description("Require all selected categories instead of any of them.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> allowFree = sgPlans.add(new BoolSetting.Builder()
        .name("allow-free")
        .description("Allow joining Free plan servers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowStandard = sgPlans.add(new BoolSetting.Builder()
        .name("allow-standard")
        .description("Allow joining Standard plan servers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowPro = sgPlans.add(new BoolSetting.Builder()
        .name("allow-pro")
        .description("Allow joining Pro plan servers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowUltimate = sgPlans.add(new BoolSetting.Builder()
        .name("allow-ultimate")
        .description("Allow joining Ultimate plan servers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowExternal = sgPlans.add(new BoolSetting.Builder()
        .name("allow-external")
        .description("Allow joining External plan servers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowCustom = sgPlans.add(new BoolSetting.Builder()
        .name("allow-custom")
        .description("Allow joining servers on custom plans.")
        .defaultValue(true)
        .build()
    );

    private enum Phase {
        IDLE,
        CONNECTING
    }

    private Phase phase = Phase.IDLE;
    private ServerInfo pendingTarget;
    private int attempts;
    private boolean retryPending;
    private final Set<String> triedNames = new HashSet<>();

    public RandomServer() {
        super(AddonTemplate.CATEGORY, "random-server", "Joins a random Minehut server.");
        runInMainMenu = true;
        serialize = false;
    }

    @Override
    public void onActivate() {
        phase = Phase.IDLE;
        attempts = 0;
        triedNames.clear();
        if (!isOnMinehut()) {
            AddonTemplate.LOG.error("Random Server only works on Minehut.");
            error("Random Server only works on Minehut.");
            toggle();
            return;
        }
        if (MinehutApi.isFresh(cacheTtl.get())) {
            selectAndConnect();
        } else {
            MinehutApi.fetchAsync(servers -> selectAndConnect(), message -> {
                AddonTemplate.LOG.error(message);
                error(message);
                toggle();
            });
        }
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        pendingTarget = null;
        attempts = 0;
        retryPending = false;
        triedNames.clear();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (phase != Phase.CONNECTING || pendingTarget == null) return;
        AddonTemplate.LOG.info("Joined {}.", pendingTarget.name);
        info("Joined %s.", pendingTarget.name);
        toggle();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (phase != Phase.CONNECTING) return;
        if (!(event.screen instanceof DisconnectedScreen)) return;
        attempts++;
        if (attempts <= maxRetries.get()) {
            AddonTemplate.LOG.warn("Connection failed, retrying ({}/{}).", attempts, maxRetries.get());
            retryPending = true;
        } else {
            AddonTemplate.LOG.error("Could not join after {} attempts.", attempts);
            toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!retryPending) return;
        retryPending = false;
        selectAndConnect();
    }

    private void selectAndConnect() {
        List<MinehutServer> servers = MinehutApi.getCache();
        if (servers == null || servers.isEmpty()) {
            AddonTemplate.LOG.error("No server list available.");
            error("No server list available.");
            toggle();
            return;
        }
        List<MinehutServer> candidates = new ArrayList<>();
        for (MinehutServer server : servers) {
            if (matches(server)) candidates.add(server);
        }
        if (candidates.isEmpty()) {
            AddonTemplate.LOG.error("No servers matched your filters.");
            error("No servers matched your filters.");
            toggle();
            return;
        }
        MinehutServer pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        triedNames.add(pick.name);
        String host = pick.name.toLowerCase(Locale.ROOT) + ".minehut.gg";
        pendingTarget = new ServerInfo("Minehut - " + pick.name, host, ServerInfo.ServerType.OTHER);
        if (announce.get()) {
            info("Joining %s (%d players).", pick.name, pick.getPlayerCount());
        }
        AddonTemplate.LOG.info("Joining {} ({} players).", pick.name, pick.getPlayerCount());
        phase = Phase.CONNECTING;
        connect();
    }

    private void connect() {
        if (phase != Phase.CONNECTING || pendingTarget == null) return;
        ServerInfo target = pendingTarget;
        AddonTemplate.LOG.info("Connecting to {}.", target.address);
        ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(target.address), target, false, null);
    }

    private boolean matches(MinehutServer server) {
        if (triedNames.contains(server.name)) return false;
        if (onlyConnectable.get() && !server.connectable) return false;
        int players = server.getPlayerCount();
        if (players < minPlayers.get() || players > maxPlayers.get()) return false;
        if (!isPlanAllowed(server.getPlanTier())) return false;
        return categoriesMatch(server);
    }

    private boolean categoriesMatch(MinehutServer server) {
        List<String> selected = categories.get();
        if (selected.isEmpty()) return true;
        if (server.allCategories == null || server.allCategories.isEmpty()) return false;
        if (requireAllCategories.get()) {
            for (String s : selected) {
                boolean found = false;
                for (String c : server.allCategories) {
                    if (c.equalsIgnoreCase(s)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
        for (String s : selected) {
            for (String c : server.allCategories) {
                if (c.equalsIgnoreCase(s)) return true;
            }
        }
        return false;
    }

    private boolean isPlanAllowed(PlanTier tier) {
        return switch (tier) {
            case Free -> allowFree.get();
            case Standard -> allowStandard.get();
            case Pro -> allowPro.get();
            case Ultimate -> allowUltimate.get();
            case External -> allowExternal.get();
            case Custom -> allowCustom.get();
        };
    }

    private boolean isOnMinehut() {
        if (mc.isInSingleplayer() || mc.isConnectedToLocalServer()) return false;
        ServerInfo current = mc.getCurrentServerEntry();
        if (current == null) return false;
        String host = ServerAddress.parse(current.address).getAddress().toLowerCase(Locale.ROOT);
        return host.equals("minehut.com") || host.endsWith(".minehut.com")
            || host.equals("minehut.gg") || host.endsWith(".minehut.gg");
    }
}
