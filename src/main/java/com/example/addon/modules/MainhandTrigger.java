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
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
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

    private final Setting<Boolean> backupSell = sgGeneral.add(new BoolSetting.Builder()
        .name("backup-sell")
        .description("If the command keeps failing (item stays in hand), fall back to /sell and deposit the item into the container.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> failThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("fail-threshold")
        .description("How many failed fires (item still held afterward) before the backup /sell kicks in.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .visible(backupSell::get)
        .build()
    );

    private final Setting<String> backupCommand = sgGeneral.add(new StringSetting.Builder()
        .name("backup-command")
        .description("Command that opens the sell container for the backup flow.")
        .defaultValue("/sell")
        .visible(backupSell::get)
        .build()
    );

    private final Setting<Integer> maxStacks = sgGeneral.add(new IntSetting.Builder()
        .name("max-stacks")
        .description("Max whitelisted stacks to deposit from your inventory per backup trip, one at a time.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 36)
        .visible(backupSell::get)
        .build()
    );

    // Previous tick's condition state, for detecting when the condition first becomes true.
    private boolean wasMet = false;

    // Ticks remaining before the command is allowed to fire again.
    private int cooldownTicks = 0;

    // Random pre-fire delay (ticks) applied after the edge, when anti-detection is on.
    private int fireDelay = 0;

    // How many times we've fired with the stack still present afterward (a "failed" sell).
    private int failCount = 0;

    // Backup-sell phase: 0 = inactive, 1 = ensuring container open, 2 = depositing (paced),
    // 3 = settle before close.
    private int backupPhase = 0;

    // Safety timeout (ticks) for the backup flow, so a container that never opens can't hang it.
    private int backupTimeout = 0;

    // Short settle delay (ticks) between the last deposit and closing the container.
    private int backupSettle = 0;

    // How many stacks we've deposited so far this backup trip.
    private int depositedCount = 0;

    // Randomized delay (ticks) between individual deposits, so clicks aren't a robotic burst.
    private int depositDelay = 0;

    public MainhandTrigger() {
        super(AddonTemplate.CATEGORY, "auto-seller", "Fires a server command once when a whitelisted mainhand item reaches a minimum count. Made by CardBerry.");
    }

    @Override
    public void onActivate() {
        cooldownTicks = 0;
        wasMet = false;
        fireDelay = 0;
        failCount = 0;
        backupPhase = 0;
        backupTimeout = 0;
        backupSettle = 0;
        depositedCount = 0;
        depositDelay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (cooldownTicks > 0) cooldownTicks--;
        if (fireDelay > 0) fireDelay--;

        // If a backup /sell flow is in progress, handle only that until it finishes.
        if (backupPhase != 0) {
            tickBackup();
            return;
        }

        ItemStack stack = mc.player.getMainHandItem();
        boolean conditionMet = !stack.isEmpty()
            && whitelist.get().contains(stack.getItem())
            && stack.getCount() >= minAmount.get();

        // Condition cleared => the sell worked. Reset the fail counter.
        if (!conditionMet) {
            failCount = 0;
            wasMet = false;
            return;
        }

        // On first entering the condition, roll a randomized pre-fire delay so the very first
        // fire isn't the same tick the item appears. Re-fires while held are paced by cooldown.
        if (!wasMet) {
            fireDelay = antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0;
        }

        // Fire whenever both the cooldown and the initial jitter delay are clear. This keeps
        // retrying while the item is still held (a failed sell, or a fresh stack picked up right
        // after firing), instead of only firing on the false->true edge.
        if (cooldownTicks == 0 && fireDelay == 0) {
            // We're about to fire while the item is still here. If we've already fired at least
            // once this streak (wasMet), the previous fire didn't clear it: count a failure.
            if (wasMet) {
                failCount++;

                // Enough failures in a row => switch to the backup /sell container flow.
                if (backupSell.get() && failCount >= failThreshold.get()) {
                    startBackup();
                    wasMet = true;
                    return;
                }
            }

            fire(stack.getCount());
            // Base cooldown plus jitter padding, floored so fires can never spam.
            int gap = cooldown.get() + (antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0);
            cooldownTicks = Math.max(gap, MIN_GAP_TICKS);
        }

        wasMet = true;
    }

    private void startBackup() {
        sendCommand(backupCommand.get());
        backupPhase = 1;          // waiting for the container to open
        backupTimeout = 200;      // 10s overall safety timeout
        backupSettle = 0;
        depositedCount = 0;
        depositDelay = 0;
        failCount = 0;
    }

    private void tickBackup() {
        if (backupTimeout > 0) backupTimeout--;

        // Overall safety timeout: if the whole flow stalls, close anything open and bail.
        if (backupTimeout == 0) {
            if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.gui.screen().onClose();
            endBackup();
            return;
        }

        boolean containerOpen = mc.gui.screen() instanceof AbstractContainerScreen<?>;

        switch (backupPhase) {
            case 1 -> {
                // Wait for the sell container to open, then start depositing.
                if (containerOpen) backupPhase = 2;
            }
            case 2 -> {
                // If the container closed (some servers rebuild it after each deposit) and we still
                // have more to deposit, reopen it and wait again.
                if (!containerOpen) {
                    if (depositedCount < maxStacks.get() && hasWhitelistedInInventory()) {
                        sendCommand(backupCommand.get());
                        backupPhase = 1;
                    } else {
                        endBackup();
                    }
                    return;
                }

                // Pace between deposits so it's not a robotic burst of clicks.
                if (depositDelay > 0) {
                    depositDelay--;
                    return;
                }

                // Stop if we've hit the cap.
                if (depositedCount >= maxStacks.get()) {
                    backupPhase = 3;
                    backupSettle = 3;
                    return;
                }

                // Deposit one whitelisted stack.
                Slot target = findWhitelistedSlot();
                if (target == null) {
                    // Nothing left to deposit — move on to closing.
                    backupPhase = 3;
                    backupSettle = 3;
                    return;
                }

                InvUtils.shiftClick().slotId(target.index);
                depositedCount++;

                // Randomized gap before the next deposit when anti-detection is on; small floor otherwise.
                depositDelay = antiDetection.get() ? Utils.random(2, jitter.get() + 3) : 2;
            }
            case 3 -> {
                // Settle so the server registers the final deposit before we close.
                if (backupSettle > 0) {
                    backupSettle--;
                    return;
                }
                if (containerOpen) mc.gui.screen().onClose();
                endBackup();
            }
        }
    }

    // Ends the backup flow and returns to normal firing after a paced gap.
    private void endBackup() {
        backupPhase = 0;
        cooldownTicks = Math.max(cooldown.get(), MIN_GAP_TICKS);
    }

    // Finds a slot in the player-inventory portion of the open container holding a whitelisted item.
    // Checking slot.container instanceof Inventory avoids re-grabbing items already in the sell area.
    private Slot findWhitelistedSlot() {
        var menu = mc.player.containerMenu;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory
                && slot.hasItem()
                && whitelist.get().contains(slot.getItem().getItem())) {
                return slot;
            }
        }
        return null;
    }

    // Whether the player still has any whitelisted item in their inventory (used to decide whether
    // reopening the container is worthwhile).
    private boolean hasWhitelistedInInventory() {
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && whitelist.get().contains(stack.getItem())) return true;
        }
        return false;
    }

    private void fire(int count) {
        sendCommand(command.get().replace("{count}", Integer.toString(count)));
    }

    private void sendCommand(String raw) {
        String cmd = raw;
        if (cmd == null || cmd.isBlank()) return;

        // Server commands must be sent with a leading slash so the server parses them.
        if (!cmd.startsWith("/")) cmd = "/" + cmd;

        ChatUtils.sendPlayerMsg(cmd);
    }
}
