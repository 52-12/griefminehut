package com.griefminehut.addon.minehut;

import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class MinehutApi {
    private static final String URL = "https://api.minehut.com/servers";

    private static volatile List<MinehutServer> cache;
    private static volatile long fetchedAt;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);

    public static List<MinehutServer> getCache() {
        return cache;
    }

    public static boolean isFresh(int ttlSeconds) {
        return cache != null && ttlSeconds > 0 && (System.currentTimeMillis() - fetchedAt) < ttlSeconds * 1000L;
    }

    public static void fetchAsync(Consumer<List<MinehutServer>> onSuccess, Consumer<String> onError) {
        if (!inFlight.compareAndSet(false, true)) return;
        MeteorExecutor.execute(() -> {
            try {
                ServersResponse response = Http.get(URL).sendJson(ServersResponse.class);
                List<MinehutServer> servers = response != null ? response.servers : null;
                if (servers == null || servers.isEmpty()) {
                    MinecraftClient.getInstance().execute(() -> onError.accept("Minehut API returned no servers."));
                    return;
                }
                cache = servers;
                fetchedAt = System.currentTimeMillis();
                List<MinehutServer> result = servers;
                MinecraftClient.getInstance().execute(() -> onSuccess.accept(result));
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> onError.accept("Failed to fetch Minehut servers: " + e.getMessage()));
            } finally {
                inFlight.set(false);
            }
        });
    }
}
