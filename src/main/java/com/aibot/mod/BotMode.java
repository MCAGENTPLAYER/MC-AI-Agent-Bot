package com.aibot.mod;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BotMode {
    private static final AtomicBoolean active = new AtomicBoolean(false);
    private static final AtomicLong toggleTime = new AtomicLong(0);

    public static boolean isActive() {
        return active.get();
    }

    public static void toggle() {
        boolean newValue = !active.get();
        active.set(newValue);
        toggleTime.set(System.currentTimeMillis());
        AiBotMod.LOGGER.info("[BotMode] Bot mode: {}", newValue ? "ON" : "OFF");
    }

    public static void setActive(boolean value) {
        active.set(value);
        toggleTime.set(System.currentTimeMillis());
    }

    public static long getToggleTime() {
        return toggleTime.get();
    }
}
