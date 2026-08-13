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
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class MainhandTrigger extends Module {
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

    private final Setting<Boolean> autoConfirm = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-confirm")
        .description("After firing, watch for a Yes/No confirmation screen and press Yes.")
        .defaultValue(true)
        .build()
    );

    // Armed after firing; while true we watch each tick for a confirm screen and press Yes.
    private boolean awaitingConfirm = false;

    // A single queued fire request, waiting for the cooldown to clear. Never stacks.
    private boolean pending = false;

    // Previous tick's condition state, for detecting the false->true edge.
    private boolean wasMet = false;

    // Ticks remaining before the command is allowed to fire again.
    private int cooldownTicks = 0;

    public MainhandTrigger() {
        super(AddonTemplate.CATEGORY, "mainhand-trigger", "Fires a server command once when a whitelisted mainhand item reaches a minimum count.");
    }

    @Override
    public void onActivate() {
        awaitingConfirm = false;
        cooldownTicks = 0;
        pending = false;
        wasMet = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (cooldownTicks > 0) cooldownTicks--;

        ItemStack stack = mc.player.getMainHandItem();
        boolean conditionMet = !stack.isEmpty()
            && whitelist.get().contains(stack.getItem())
            && stack.getCount() >= minAmount.get();

        // Edge: only request a fire on the false->true flip, not every tick it stays held.
        if (conditionMet && !wasMet) pending = true;
        wasMet = conditionMet;

        // Fire the single pending request once the cooldown is clear.
        if (pending && cooldownTicks == 0) {
            fire(stack.getCount());
            cooldownTicks = cooldown.get();
            pending = false;
        }

        // Watch for the confirmation screen and press Yes once it appears.
        if (awaitingConfirm && autoConfirm.get() && pressYes()) {
            awaitingConfirm = false;
        }
    }

    private boolean pressYes() {
        Screen screen = mc.screen;
        if (screen == null) return false;

        for (var child : screen.children()) {
            if (child instanceof Button button
                && button.getMessage().getString().equalsIgnoreCase("Yes")) {
                button.onPress();
                return true;
            }
        }
        return false;
    }

    private void fire(int count) {
        String cmd = command.get().replace("{count}", Integer.toString(count));
        if (cmd.isBlank()) return;

        // Server commands must be sent with a leading slash so the server parses them.
        if (!cmd.startsWith("/")) cmd = "/" + cmd;

        ChatUtils.sendPlayerMsg(cmd);

        if (autoConfirm.get()) awaitingConfirm = true;
    }
}
