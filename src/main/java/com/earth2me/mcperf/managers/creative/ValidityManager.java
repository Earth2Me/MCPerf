package com.earth2me.mcperf.managers.creative;

import com.earth2me.mcperf.annotation.ContainsConfig;
import com.earth2me.mcperf.annotation.Service;
import com.earth2me.mcperf.managers.Manager;
import com.earth2me.mcperf.managers.creative.validity.*;
import lombok.Getter;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Stream;

@Service
@ContainsConfig
public final class ValidityManager extends Manager {
    // TODO: Migrate this to new configuration system
    @Getter
    private ValidityConfiguration config;

    private final Map<Class<? extends ItemMeta>, MetaValidator> metaValidators = new HashMap<>();
    private final Validator genericValidator = new GenericValidator();

    {
        registerMetaValidator(new GenericMetaValidator_1_9());
        registerMetaValidator(new PotionMetaValidator());
    }

    public ValidityManager() {
        //noinspection SpellCheckingInspection
        super("MjIbdmFsaWRpdHkK");
    }

    @Override
    public void onConfig(FileConfiguration config) {
        setConfig(new ValidityConfiguration(config));

        super.onConfig(config);
    }

    private void registerMetaValidator(MetaValidator<? extends ItemMeta> validator) {
        metaValidators.put(validator.getMetaType(), validator);
    }

    public Stream<? extends Validator> getAllValidators() {
        Stream<? extends Validator> result = Stream.empty();

        result = Stream.concat(result, metaValidators.values().stream());
        result = Stream.concat(result, Stream.of(genericValidator));

        return result;
    }

    public void setConfig(final ValidityConfiguration config) {
        this.config = config;

        getAllValidators().forEach(v -> v.setConfig(config));
    }

    private void onInvalid(String property, Sender sender, ItemStack itemStack) {
        getLogger().warning(String.format(
                "Found item stack %s:%d x%d with invalid %s for %s",
                itemStack.getType(),
                itemStack.getDurability(),
                itemStack.getAmount(),
                property,
                sender
        ));
    }

    public boolean isValid(ItemStack stack, Sender sender, boolean strict) {
        boolean valid;
        try {
            valid = isValidUnsafe(stack, sender, strict);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "[MCPerf] Exception while validating stack: " + e.getMessage(), e);
            return true;
        }

        if (!valid && strict && sender.getPlayer() != null) {
            Player player = sender.getPlayer();
            if (player.getGameMode() == GameMode.CREATIVE) {
                // TODO: Kick ass
                sendAlert("Caught %s with a modded/forbidden stack of %s", player.getName(), stack.getType().name());
            }
        }

        return valid;
    }

    private boolean isValidUnsafe(final ItemStack stack, final Sender sender, boolean strict) {
        if (stack == null) {
            return true;
        }

        Validator validator = genericValidator;
        ItemMeta meta = stack.getItemMeta();

        if (stack.hasItemMeta()) {
            for (Map.Entry<Class<? extends ItemMeta>, MetaValidator> entry : metaValidators.entrySet()) {
                Class<? extends ItemMeta> key = entry.getKey();
                if (key != ItemMeta.class && key.isInstance(meta)) {
                    if (!entry.getValue().isValid(stack, strict)) {
                        return false;
                    }
                    break;
                }
            }
        }

        return validator.isValid(stack, strict, reason -> onInvalid(reason, sender, stack));
    }

    private void validate(Inventory inventory, Sender sender, boolean strict) {
        try {
            ItemStack[] contents = inventory.getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];

                if (!isValid(stack, sender, strict)) {
                    inventory.clear(i);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isEnabled() {
        return getConfig().isEnabled();
    }

    // Keep type parameter for future use
    private static <T extends Event & Cancellable> void deny(T event) {
        event.setCancelled(true);
    }

    private void performShakedown(Sender sender, InventoryClickEvent event) {
        performShakedown(sender, event.getView().getBottomInventory(), event.getView().getTopInventory(), event.getInventory(), event.getClickedInventory());
    }

    // TODO Use this method
    @SuppressWarnings("unused")
    private void performShakedown(Sender sender, InventoryEvent event) {
        performShakedown(sender, event.getView().getBottomInventory(), event.getView().getTopInventory(), event.getInventory());
    }

    private void performShakedown(Sender sender, Inventory... extra) {
        assert sender != null;
        assert extra != null;

        if (sender.isClean()) {
            return;
        }

        try {
            HumanEntity human = sender.getHuman();
            Stream.Builder<Inventory> builder = Stream.builder();
            if (human != null) {
                builder.add(human.getEnderChest());
                builder.add(human.getInventory());

                InventoryView open = human.getOpenInventory();
                if (open != null) {
                    builder.add(open.getTopInventory());
                    builder.add(open.getBottomInventory());
                }

                if (!isValid(human.getInventory().getItemInMainHand(), sender, false)) {
                    human.getInventory().setItemInMainHand(null);
                }
                if (!isValid(human.getInventory().getItemInOffHand(), sender, false)) {
                    human.getInventory().setItemInOffHand(null);
                }
                if (!isValid(human.getItemOnCursor(), sender, false)) {
                    human.setItemOnCursor(null);
                }
            }

            Stream.concat(Stream.of(extra), builder.build())
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(i -> validate(i, sender, false));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "[MCPerf] Exception while performing shakedown", e);
        } finally {
            sender.setClean(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!isEnabled()) {
            return;
        }

        validate(event.getInventory(), new Sender(event.getPlayer()), false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!isEnabled()) {
            return;
        }

        Sender sender = new Sender(event.getWhoClicked());

        try {
            if (!isValid(event.getCursor(), sender, true)) {
                deny(event);
                event.setCursor(null);
                performShakedown(sender, event);
            }

            if (!isValid(event.getCurrentItem(), sender, true)) {
                deny(event);
                event.setCurrentItem(null);
                performShakedown(sender, event);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!isEnabled()) {
            return;
        }

        try {
            Player player = event.getPlayer();
            Sender sender = new Sender(player);

            if (!isValid(event.getItemDrop().getItemStack(), sender, true)) {
                if (player.getGameMode() == GameMode.CREATIVE) {
                    performShakedown(sender);
                }

                deny(event);
                event.getItemDrop().remove();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (!isEnabled()) {
            return;
        }

        try {
            Player player = event.getPlayer();
            Sender sender = new Sender(player);

            if (!isValid(event.getItem().getItemStack(), sender, true)) {
                if (player.getGameMode() == GameMode.CREATIVE) {
                    performShakedown(sender);
                }

                deny(event);
                event.getItem().remove();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (!isEnabled()) {
            return;
        }

        try {
            Player player = event.getPlayer();
            Sender sender = new Sender(player);
            int[] slots = { event.getNewSlot(), event.getPreviousSlot() };
            PlayerInventory inventory = player.getInventory();

            boolean shakedown = false;

            for (int slot : slots) {
                ItemStack stack = inventory.getItem(slot);

                if (!isValid(stack, sender, true)) {
                    shakedown = true;
                    stack.setType(Material.AIR);
                    inventory.clear(slot);
                }
            }

            if (shakedown && player.getGameMode() == GameMode.CREATIVE) {
                performShakedown(sender);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!isEnabled()) {
            return;
        }

        try {
            if (!event.isCancelled() && !isValid(event.getEntity().getItemStack(), Sender.anon(event), false)) {
                deny(event);
                event.getEntity().remove();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!isEnabled()) {
            return;
        }

        try {
            // Right now this is very similar to a shakedown, but in the future it will likely be less thorough.

            Player player = event.getPlayer();
            Sender sender = new Sender(player);

            validate(player.getInventory(), sender, false);
            validate(player.getEnderChest(), sender, false);

            if (!isValid(player.getInventory().getItemInMainHand(), sender, false)) {
                player.getInventory().setItemInMainHand(null);
            }
            if (!isValid(player.getInventory().getItemInOffHand(), sender, false)) {
                player.getInventory().setItemInOffHand(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!isEnabled()) {
            return;
        }

        Sender sender = Sender.anon(event);

        for (Entity entity : event.getChunk().getEntities()) {
            if (entity.isEmpty()) {
                continue;
            }

            if (entity instanceof Item) {
                Item item = (Item) entity;

                if (!isValid(item.getItemStack(), sender, false)) {
                    try {
                        item.remove();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        for (BlockState tile : event.getChunk().getTileEntities()) {
            if (tile instanceof ItemFrame) {
                ItemFrame frame = (ItemFrame) tile;

                if (!isValid(frame.getItem(), sender, false)) {
                    try {
                        frame.setItem(null);
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            if (tile instanceof Chest) {
                Chest chest = (Chest) tile;
                // Only check the side of the chest that's relevant for this block so we don't check the chest twice.
                validate(chest.getBlockInventory(), sender, false);
                return;
            } else if (tile instanceof InventoryHolder) {
                InventoryHolder holder = (InventoryHolder) tile;
                validate(holder.getInventory(), sender, false);
                return;
            }
        }
    }
}
