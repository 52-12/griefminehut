package com.example.addon.minehut;

import java.util.Locale;

public enum PlanTier {
    Free,
    Standard,
    Pro,
    Ultimate,
    External,
    Custom;

    public static PlanTier from(String rawPlan) {
        if (rawPlan == null) return Custom;
        String plan = rawPlan.toUpperCase(Locale.ROOT);
        switch (plan) {
            case "FREE": return Free;
            case "MONTHLY_2GB":
            case "YEARLY_2GB": return Standard;
            case "MONTHLY_6GB":
            case "YEARLY_6GB": return Pro;
            case "MONTHLY_10GB":
            case "YEARLY_10GB": return Ultimate;
            case "EXTERNAL": return External;
            default: return Custom;
        }
    }
}
