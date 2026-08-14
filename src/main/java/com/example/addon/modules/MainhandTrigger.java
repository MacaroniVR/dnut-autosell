package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

public class MainhandTrigger extends Module {
    // Hard floor on ticks between fires, so the loop can never spam even if cooldown is set to 0.
    private static final int MIN_GAP_TICKS = 10;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgDiag = this.settings.createGroup("Diagnostic");

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
        .defaultValue("/ah sell 10k")
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
        .sliderRange(1, 100)
        .visible(backupSell::get)
        .build()
    );

    private final Setting<Boolean> readAhFormat = sgDiag.add(new BoolSetting.Builder()
        .name("read-ah-format")
        .description("TEMP: runs /ah <first whitelisted item> and dumps the first slot's lore to chat so the price format can be read. Turns itself off after one run.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minCommandGap = sgGeneral.add(new IntSetting.Builder()
        .name("min-command-gap")
        .description("Minimum ticks between ANY two commands the mod sends, to respect the server's rate limit (20 ticks = 1s). Commands sent sooner are queued.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> smartPricing = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-smart-pricing")
        .description("Before selling, read the item's lowest AH price and sell at that price. Overrides the number in the command.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> undercut = sgGeneral.add(new BoolSetting.Builder()
        .name("undercut-by-1")
        .description("Sell for 1 less than the lowest listed price instead of matching it.")
        .defaultValue(false)
        .visible(smartPricing::get)
        .build()
    );

    private final Setting<Boolean> chatBackup = sgGeneral.add(new BoolSetting.Builder()
        .name("backup-on-full")
        .description("Jump straight to the backup sell when the server says your AH is full.")
        .defaultValue(false)
        .visible(backupSell::get)
        .build()
    );

    private final Setting<String> fullMessage = sgGeneral.add(new StringSetting.Builder()
        .name("full-message")
        .description("The chat text that means your AH is full (case-insensitive, partial match).")
        .defaultValue("too many listed items")
        .visible(() -> backupSell.get() && chatBackup.get())
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

    // Diagnostic (read-ah-format) state: 0 = idle, 1 = /ah sent, waiting for container.
    private int diagPhase = 0;
    private int diagTimeout = 0;

    // Global command throttle: every command the mod sends is queued here and drained one at a
    // time, never faster than min-command-gap ticks apart, so we can't trip the server rate limit.
    private final java.util.ArrayDeque<String> commandQueue = new java.util.ArrayDeque<>();
    private int ticksSinceCommand = 999; // large so the first command can send immediately

    // Set by the chat listener when the server says the AH is full; the tick loop jumps to backup.
    private boolean ahFullSignal = false;

    // Smart-pricing state: 0 = idle, 1 = /ah <item> queued, waiting to read the price and sell.
    private int pricePhase = 0;
    private int priceTimeout = 0;
    private int priceCount = 0; // stack count captured when pricing began, for {count} in the command

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
        diagPhase = 0;
        diagTimeout = 0;
        ahFullSignal = false;
        pricePhase = 0;
        priceTimeout = 0;
        priceCount = 0;
        commandQueue.clear();
        ticksSinceCommand = 999;
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        if (!backupSell.get() || !chatBackup.get()) return;

        String text = event.getMessage().getString().toLowerCase();
        if (text.contains(fullMessage.get().toLowerCase())) {
            ahFullSignal = true;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Global command throttle — runs every tick regardless of phase so queued commands from
        // any feature get sent with proper spacing.
        drainCommandQueue();

        if (cooldownTicks > 0) cooldownTicks--;
        if (fireDelay > 0) fireDelay--;

        // Diagnostic AH-format reader runs independently of the sell logic.
        if (readAhFormat.get() || diagPhase != 0) {
            tickDiag();
            return;
        }

        // If a backup /sell flow is in progress, handle only that until it finishes.
        if (backupPhase != 0) {
            tickBackup();
            return;
        }

        // Server said the AH is full: skip retrying the sell command and go straight to backup.
        if (ahFullSignal) {
            ahFullSignal = false;
            if (backupSell.get() && chatBackup.get()) {
                startBackup();
                return;
            }
        }

        // If a smart-pricing lookup is in progress, handle only that until it finishes.
        if (pricePhase != 0) {
            tickPricing();
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

            if (smartPricing.get()) {
                // Smart pricing: look up the AH price first, then sell. Pacing is handled inside.
                startPricing(stack);
            } else {
                fire(stack.getCount());
            }
            // Base cooldown plus jitter padding, floored so fires can never spam.
            int gap = cooldown.get() + (antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0);
            cooldownTicks = Math.max(gap, MIN_GAP_TICKS);
        }

        wasMet = true;
    }

    // ----- Temporary AH-format reader -----
    // Runs /ah <first whitelisted item>, waits for the container, and dumps the first slot's
    // name + lore lines (raw getString) to chat so the price format can be identified.
    private void tickDiag() {
        if (diagPhase == 0) {
            if (!readAhFormat.get()) return;

            Item first = whitelist.get().isEmpty() ? null : whitelist.get().get(0);
            if (first == null) {
                ChatUtils.warning("AutoSeller: whitelist is empty — add an item to read its AH format.");
                readAhFormat.set(false);
                return;
            }

            String name = BuiltInRegistries.ITEM.getKey(first).getPath();
            ChatUtils.info("AutoSeller: searching /ah %s ...", name);
            sendCommand("/ah " + name);
            diagPhase = 1;
            diagTimeout = 100; // 5s
            return;
        }

        if (diagTimeout > 0) diagTimeout--;
        if (diagTimeout == 0) {
            ChatUtils.warning("AutoSeller: AH container didn't open in time.");
            finishDiag();
            return;
        }

        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) return;

        var menu = mc.player.containerMenu;
        Slot firstItem = null;
        for (Slot slot : menu.slots) {
            // First filled slot that isn't the player inventory = the top-left AH listing.
            if (slot.hasItem() && !(slot.container instanceof Inventory)) {
                firstItem = slot;
                break;
            }
        }

        if (firstItem == null) {
            ChatUtils.warning("AutoSeller: no item found in the AH container's first slots.");
            finishDiag();
            return;
        }

        ItemStack is = firstItem.getItem();
        ChatUtils.info("=== AutoSeller AH read: %s ===", is.getHoverName().getString());

        ItemLore lore = is.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            ChatUtils.info("(no LORE component — price may be in the vanilla tooltip)");
        } else {
            int i = 0;
            for (Component line : lore.lines()) {
                ChatUtils.info("lore[%d]: %s", i++, line.getString());
            }
        }

        finishDiag();
    }

    private void finishDiag() {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.gui.screen().onClose();
        diagPhase = 0;
        diagTimeout = 0;
        readAhFormat.set(false); // one-shot: turn the toggle back off
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

    // ----- Auto Smart Pricing -----
    private void startPricing(ItemStack stack) {
        Item item = stack.getItem();
        priceCount = stack.getCount();
        sendCommand("/ah " + ahName(item));
        pricePhase = 1;
        priceTimeout = 100; // 5s
    }

    private void tickPricing() {
        if (priceTimeout > 0) priceTimeout--;
        if (priceTimeout == 0) {
            // Couldn't read a price in time — close any GUI and fall back to the plain command.
            if (mc.gui.screen() instanceof AbstractContainerScreen<?>) mc.gui.screen().onClose();
            fire(priceCount);
            pricePhase = 0;
            return;
        }

        if (pricePhase == 1) {
            // Wait for the AH container to open.
            if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) return;

            // Read the first listing (first filled non-inventory slot) and parse its price.
            long price = -1;
            var menu = mc.player.containerMenu;
            for (Slot slot : menu.slots) {
                if (slot.hasItem() && !(slot.container instanceof Inventory)) {
                    ItemLore lore = slot.getItem().get(DataComponents.LORE);
                    if (lore != null) {
                        for (Component line : lore.lines()) {
                            long p = parsePrice(line.getString());
                            if (p >= 0) { price = p; break; }
                        }
                    }
                    break;
                }
            }

            // Done reading — close the AH.
            mc.gui.screen().onClose();

            if (price < 0) {
                // No price found (empty AH for this item, or format changed) — use the plain command.
                fire(priceCount);
                pricePhase = 0;
                return;
            }

            if (undercut.get()) price -= 1;
            if (price < 1) price = 1; // never go to zero/negative

            // Enqueue the sell; the global throttle guarantees it won't fire too soon after /ah.
            sendCommand("/ah sell " + price);
            pricePhase = 0;
            // Space the next cycle's /ah lookup too.
            int gap = cooldown.get() + (antiDetection.get() ? Utils.random(0, jitter.get() + 1) : 0);
            cooldownTicks = Math.max(gap, MIN_GAP_TICKS);
        }
    }

    // Queues a command for throttled sending. The tick loop drains the queue, never sending two
    // commands closer than min-command-gap ticks apart.
    private void sendCommand(String raw) {
        String cmd = raw;
        if (cmd == null || cmd.isBlank()) return;

        // Server commands must be sent with a leading slash so the server parses them.
        if (!cmd.startsWith("/")) cmd = "/" + cmd;

        commandQueue.add(cmd);
    }

    // Called every tick: advances the timer and sends the next queued command once the gap allows.
    private void drainCommandQueue() {
        if (ticksSinceCommand < 100_000) ticksSinceCommand++;

        if (commandQueue.isEmpty()) return;
        if (ticksSinceCommand < minCommandGap.get()) return;

        String cmd = commandQueue.poll();
        ChatUtils.sendPlayerMsg(cmd);
        ticksSinceCommand = 0;
    }

    // Parses an AH price string like "$ 3.9K", "$ 100", "2.5M" into a whole number of coins.
    // Suffixes: K=1e3, M=1e6, B=1e9, T=1e12. Returns -1 if nothing parseable is found.
    private long parsePrice(String text) {
        if (text == null) return -1;

        // Keep only digits, dot, and suffix letters; drop "$", spaces, colours, commas.
        StringBuilder sb = new StringBuilder();
        char suffix = 0;
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                sb.append(c);
            } else {
                char u = Character.toUpperCase(c);
                if (u == 'K' || u == 'M' || u == 'B' || u == 'T') {
                    suffix = u;
                    break; // suffix comes right after the number
                }
            }
        }

        if (sb.length() == 0) return -1;

        double value;
        try {
            value = Double.parseDouble(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }

        switch (suffix) {
            case 'K' -> value *= 1_000d;
            case 'M' -> value *= 1_000_000d;
            case 'B' -> value *= 1_000_000_000d;
            case 'T' -> value *= 1_000_000_000_000d;
            default -> { }
        }

        return (long) value;
    }

    // The searchable AH name for an item, e.g. minecraft:tuff -> "tuff".
    private String ahName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
