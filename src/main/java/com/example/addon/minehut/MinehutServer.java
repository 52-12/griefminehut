package com.example.addon.minehut;

import java.util.List;

public class MinehutServer {
    public String name;
    public String motd;
    public String icon;
    public Integer maxPlayers;
    public boolean connectable;
    public boolean visibility;
    public boolean usingCosmetics;
    public List<String> allCategories;
    public PlayerData playerData;
    public StaticInfo staticInfo;
    public String author;
    public String authorRank;
    public String versionMin;
    public String versionMax;
    public List<PluginInfo> minehutPlugins;

    public int getPlayerCount() {
        return playerData != null ? playerData.playerCount : 0;
    }

    public PlanTier getPlanTier() {
        return staticInfo != null ? PlanTier.from(staticInfo.rawPlan) : PlanTier.Custom;
    }

    public static class PlayerData {
        public int playerCount;
        public long timeNoPlayers;
    }

    public static class StaticInfo {
        public String _id;
        public String serverPlan;
        public String rawPlan;
        public long serviceStartDate;
        public String platform;
        public Integer planMaxPlayers;
        public Integer planRam;
        public boolean alwaysOnline;
        public List<String> connectedServers;
    }

    public static class PluginInfo {
        public String name;
        public boolean enabled;
    }
}
