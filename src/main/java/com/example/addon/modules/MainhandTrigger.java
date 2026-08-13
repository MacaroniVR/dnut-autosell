package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class MainhandTrigger extends Module {
    // Hard floor on ticks between fires, so the loop can never spam even if cooldown is set to 0.
    private static final int MIN_GAP_TICKS = 10;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<List<Item>> whitelist = sgGeneral.add(new ItemListSetting.Builder()
        .name("item-whitelist")
        .description("Items that will trigger the command when held in the mainhand.")
        .build()
    );

    private final Setting<Integer> minAmount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-amount")
        .description("Minimum mainhand stack count required to trigger.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("Server command to send when triggered. Use {count} for the stack count.")
        .defaultValue("/say triggered")
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Minimum ticks between command fires (20 ticks = 1 second).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> antiDetection = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-detection")
        .description("Adds random timing jitter so fires aren't perfectly regular. Harder for automation detection to spot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> jitter = sgGeneral.add(new IntSetting.Builder()
        .name("jitter")
        .description("Max random ticks added to fire delay and cooldown when anti-detection is on.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .visible(antiDetection::get)
        .build()
    );

    // Previous tick's condition state, for detecting when the condition first becomes true.
    private boolean wasMet = false;

    // Ticks remaining before the command is allowed to fire again.
    private int cooldownTicks = 0;

    // Random pre-fire delay (ticks) applied after the edge, when anti-detection is on.
    private int fireDelay = 0;

    public MainhandTrigger() {
        super(AddonTemplate.CATEGORY, "auto-seller", "Fires a server command once when a whitelisted mainhand item reaches a minimum count. Made by CardBerry.");
    }

    @Override
    public void onActivate() {
        cooldownTicks = 0;
        wasMet = false;
        fireDelay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (cooldownTicks > 0) cooldownTicks--;
        if (fireDelay > 0) fireDelay--;

        ItemStack stack = mc.player.getMainHandItem();
        boolean conditionMet = !stack.isEmpty()
            && whitelist.get().contains(stack.getItem())
            && stack.getCount() >= minAmount.get();

        if (conditionMet) {
            // On first entering the condition, roll a randomized pre-fire delay so the very first
            // fire isn't the same tick the item appears. Re-fires while held are paced by cooldown.
            if (!wasMet) {
                fireDelay = antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0;
            }

            // Fire whenever both the cooldown and the initial jitter delay are clear. This keeps
            // retrying while the item is still held (e.g. a failed sell, or a fresh stack picked up
            // right after firing), instead of only firing on the false->true edge.
            if (cooldownTicks == 0 && fireDelay == 0) {
                fire(stack.getCount());
                // Base cooldown plus jitter padding, floored so fires can never spam.
                int gap = cooldown.get() + (antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0);
                cooldownTicks = Math.max(gap, MIN_GAP_TICKS);
            }
        }
        wasMet = conditionMet;
    }

    private void fire(int count) {
        String cmd = command.get().replace("{count}", Integer.toString(count));
        if (cmd.isBlank()) return;

        // Server commands must be sent with a leading slash so the server parses them.
        if (!cmd.startsWith("/")) cmd = "/" + cmd;

        ChatUtils.sendPlayerMsg(cmd);
    }
}
