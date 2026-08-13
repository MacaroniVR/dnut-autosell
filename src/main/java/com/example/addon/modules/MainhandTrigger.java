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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
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

    // Random pre-fire delay (ticks) applied after the edge, when anti-detection is on.
    private int fireDelay = 0;

    public MainhandTrigger() {
        super(AddonTemplate.CATEGORY, "mainhand-trigger", "Fires a server command once when a whitelisted mainhand item reaches a minimum count. Made by CardBerry.");
    }

    @Override
    public void onActivate() {
        awaitingConfirm = false;
        cooldownTicks = 0;
        pending = false;
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

        // Edge: only request a fire on the false->true flip, not every tick it stays held.
        if (conditionMet && !wasMet) {
            pending = true;
            // Randomized pre-fire delay so the command doesn't fire the same tick the condition flips.
            fireDelay = antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0;
        }
        wasMet = conditionMet;

        // Fire the single pending request once both the cooldown and the jitter delay have cleared.
        if (pending && cooldownTicks == 0 && fireDelay == 0) {
            fire(stack.getCount());
            // Base cooldown plus random padding, so the interval between fires isn't perfectly regular.
            cooldownTicks = cooldown.get() + (antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0);
            pending = false;
        }

        // Watch for the confirmation screen and press Yes once it appears.
        if (awaitingConfirm && autoConfirm.get() && pressYes()) {
            awaitingConfirm = false;
        }
    }

    private boolean pressYes() {
        Screen screen = mc.gui.screen();
        if (screen == null) return false;

        // Collect the screen's buttons in order. Confirm screens lay these out as [No, Yes],
        // so the second button is Yes. This avoids matching on the label text, which can carry
        // color/formatting that breaks a plain "Yes" string comparison.
        List<Button> buttons = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof Button button) buttons.add(button);
        }
        if (buttons.isEmpty()) return false;

        KeyEvent enter = new KeyEvent(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_ENTER, 0);

        // Prefer the button whose visible text contains "y" (Yes has one, No never does). This
        // survives color/formatting on the label, which broke the earlier exact "Yes" match.
        // If none matches, fall back to the second button, since confirm screens lay out [No, Yes].
        Button target = null;
        for (Button b : buttons) {
            if (b.getMessage().getString().toLowerCase().contains("y")) {
                target = b;
                break;
            }
        }
        if (target == null && buttons.size() >= 2) target = buttons.get(1);
        if (target == null) return false;

        target.onPress(enter);
        return true;
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
