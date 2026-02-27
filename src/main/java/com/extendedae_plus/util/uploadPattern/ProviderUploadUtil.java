package com.extendedae_plus.util.uploadPattern;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.util.inv.FilteredInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;
import com.extendedae_plus.init.ModNetwork;
import com.extendedae_plus.mixin.ae2.accessor.PatternEncodingTermMenuAccessor;
import com.extendedae_plus.network.provider.ProvidersListS2CPacket;
import com.extendedae_plus.util.PatternProviderDataUtil;
import com.extendedae_plus.util.PatternTerminalUtil;
import com.extendedae_plus.util.wireless.WirelessTerminalGridUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pattern provider upload helpers.
 */
public final class ProviderUploadUtil {
    private ProviderUploadUtil() {
    }

    private static final String PENDING_DATA_KEY = "eap_ctrlq_pending_provider_upload_id";
    private static final String PENDING_STACK_KEY = "eap_ctrlq_pending_provider_upload_stack";

    private static void sendMessage(ServerPlayer player, String message) {
        // Intentionally quiet in normal gameplay.
    }

    public static boolean uploadPatternToProvider(ServerPlayer player, int playerSlotIndex, long providerId) {
        PatternAccessTermMenu menu = PatternTerminalUtil.getPatternAccessMenu(player);
        if (menu == null) {
            sendMessage(player, "ExtendedAE Plus: open a pattern access terminal first");
            return false;
        }

        ItemStack playerItem = player.getInventory().getItem(playerSlotIndex);
        if (playerItem.isEmpty() || !PatternDetailsHelper.isEncodedPattern(playerItem)) {
            return false;
        }

        PatternContainer patternContainer = PatternTerminalUtil.getPatternContainerById(menu, providerId);
        if (patternContainer == null) {
            return false;
        }

        InternalInventory patternInventory = patternContainer.getTerminalPatternInventory();
        if (patternInventory == null) {
            return false;
        }

        return insertIntoInventoryAndShrinkPlayerStack(patternInventory, playerItem, () -> {
            if (playerItem.isEmpty()) {
                player.getInventory().setItem(playerSlotIndex, ItemStack.EMPTY);
            }
        });
    }

    public static boolean uploadFromEncodingMenuToProvider(ServerPlayer player, PatternEncodingTermMenu menu, long providerId) {
        if (player == null || menu == null) {
            return false;
        }

        var encodedSlot = ((PatternEncodingTermMenuAccessor) (Object) menu).eap$getEncodedPatternSlot();
        ItemStack stack = encodedSlot.getItem();
        if (stack.isEmpty() || !PatternDetailsHelper.isEncodedPattern(stack)) {
            return false;
        }

        PatternAccessTermMenu accessMenu = PatternTerminalUtil.getPatternAccessMenu(player);
        if (accessMenu == null) {
            return false;
        }

        String targetName = PatternProviderDataUtil.getProviderDisplayName(providerId, accessMenu);
        List<Long> tryIds = new ArrayList<>();
        tryIds.add(providerId);
        try {
            List<Long> all = PatternTerminalUtil.getAllProviderIds(accessMenu);
            for (Long id : all) {
                if (id == null || id == providerId) continue;
                String name = PatternProviderDataUtil.getProviderDisplayName(id, accessMenu);
                if (name != null && name.equals(targetName)) {
                    tryIds.add(id);
                }
            }
        } catch (Throwable ignored) {
        }

        for (Long id : tryIds) {
            PatternContainer c = PatternTerminalUtil.getPatternContainerById(accessMenu, id);
            if (c == null || !c.isVisibleInTerminal()) continue;
            InternalInventory inv = c.getTerminalPatternInventory();
            if (inv == null || inv.size() <= 0) continue;

            if (insertIntoInventoryAndShrinkEncodingSlot(inv, encodedSlot, stack)) {
                return true;
            }
        }
        return false;
    }

    public static boolean uploadFromEncodingMenuToProviderByIndex(ServerPlayer player, PatternEncodingTermMenu menu, int index) {
        if (player == null || menu == null || index < 0) return false;
        List<PatternContainer> list = PatternTerminalUtil.listAvailableProvidersFromGrid(menu);
        if (index >= list.size()) return false;
        PatternContainer container = list.get(index);
        if (container == null) return false;

        var encodedSlot = ((PatternEncodingTermMenuAccessor) (Object) menu).eap$getEncodedPatternSlot();
        ItemStack stack = encodedSlot.getItem();
        if (stack.isEmpty() || !PatternDetailsHelper.isEncodedPattern(stack)) {
            return false;
        }

        List<PatternContainer> tryList = buildSameNameTryList(list, container);
        for (PatternContainer c : tryList) {
            InternalInventory inv = c.getTerminalPatternInventory();
            if (inv == null || inv.size() <= 0) continue;
            if (insertIntoInventoryAndShrinkEncodingSlot(inv, encodedSlot, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Upload player-inventory pattern to a provider on the player's current wireless network.
     * providerId is the same encoded id used by provider selection screen fallback: -1-index.
     */
    public static boolean uploadPatternToProviderFromPlayerNetwork(ServerPlayer player, int playerSlotIndex, long providerId) {
        if (player == null || playerSlotIndex < 0 || playerSlotIndex >= player.getInventory().getContainerSize()) {
            return false;
        }

        ItemStack playerItem = player.getInventory().getItem(playerSlotIndex);
        if (playerItem.isEmpty() || !PatternDetailsHelper.isEncodedPattern(playerItem)) {
            return false;
        }

        var grid = WirelessTerminalGridUtil.findPlayerGrid(player);
        if (grid == null) {
            return false;
        }

        List<PatternContainer> list = PatternTerminalUtil.listAvailableProvidersFromGrid(grid);
        int index = decodeProviderIndex(providerId);
        if (index < 0 || index >= list.size()) {
            return false;
        }

        PatternContainer target = list.get(index);
        if (target == null) {
            return false;
        }

        for (PatternContainer c : buildSameNameTryList(list, target)) {
            InternalInventory inv = c.getTerminalPatternInventory();
            if (inv == null || inv.size() <= 0) continue;

            if (insertIntoInventoryAndShrinkPlayerStack(inv, playerItem, () -> {
                if (playerItem.isEmpty()) {
                    player.getInventory().setItem(playerSlotIndex, ItemStack.EMPTY);
                }
            })) {
                return true;
            }
        }

        return false;
    }

    public static boolean openProviderSelectionForPlayerNetwork(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        var grid = WirelessTerminalGridUtil.findPlayerGrid(player);
        if (grid == null) {
            return false;
        }

        List<PatternContainer> containers = PatternTerminalUtil.listAvailableProvidersFromGrid(grid);
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < containers.size(); i++) {
            PatternContainer c = containers.get(i);
            if (c == null) continue;
            int empty = PatternProviderDataUtil.getAvailableSlots(c);
            if (empty <= 0) continue;
            long encodedId = -1L - i;
            ids.add(encodedId);
            names.add(PatternProviderDataUtil.getProviderDisplayName(c));
            slots.add(empty);
        }

        if (ids.isEmpty()) {
            return false;
        }

        ModNetwork.CHANNEL.sendTo(
            new ProvidersListS2CPacket(ids, names, slots),
            player.connection.connection,
            net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
        return true;
    }

    public static String beginPendingCtrlQUpload(ServerPlayer player, ItemStack pattern) {
        if (player == null || pattern == null || pattern.isEmpty()) {
            return null;
        }

        clearPendingCtrlQUpload(player);
        String id = UUID.randomUUID().toString();
        player.getPersistentData().putString(PENDING_DATA_KEY, id);
        player.getPersistentData().put(PENDING_STACK_KEY, pattern.copy().save(new CompoundTag()));
        return id;
    }

    public static void clearPendingCtrlQUpload(ServerPlayer player) {
        if (player == null) return;

        player.getPersistentData().remove(PENDING_DATA_KEY);
        player.getPersistentData().remove(PENDING_STACK_KEY);
    }

    public static boolean uploadPendingCtrlQPattern(ServerPlayer player, long providerId) {
        if (player == null) {
            return false;
        }
        ItemStack pending = getPendingCtrlQPattern(player);
        if (pending.isEmpty()) {
            return false;
        }

        ItemStack remain = insertPatternIntoProviderFromPlayerNetwork(player, pending, providerId);
        if (remain.getCount() >= pending.getCount()) {
            return false;
        }

        if (remain.isEmpty()) {
            clearPendingCtrlQUpload(player);
        } else {
            player.getPersistentData().put(PENDING_STACK_KEY, remain.save(new CompoundTag()));
        }
        return true;
    }

    private static ItemStack getPendingCtrlQPattern(ServerPlayer player) {
        String id = player.getPersistentData().getString(PENDING_DATA_KEY);
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }

        CompoundTag data = player.getPersistentData();
        if (!data.contains(PENDING_STACK_KEY, 10)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = ItemStack.of(data.getCompound(PENDING_STACK_KEY));
        if (stack.isEmpty() || !PatternDetailsHelper.isEncodedPattern(stack)) {
            clearPendingCtrlQUpload(player);
            return ItemStack.EMPTY;
        }
        return stack;
    }

    private static int decodeProviderIndex(long providerId) {
        if (providerId < 0) {
            long idx = -1L - providerId;
            return idx > Integer.MAX_VALUE ? -1 : (int) idx;
        }
        return -1;
    }

    private static List<PatternContainer> buildSameNameTryList(List<PatternContainer> list, PatternContainer target) {
        String targetName = PatternProviderDataUtil.getProviderDisplayName(target);
        List<PatternContainer> tryList = new ArrayList<>();
        tryList.add(target);
        for (PatternContainer c : list) {
            if (c == null || c == target) continue;
            String name = PatternProviderDataUtil.getProviderDisplayName(c);
            if (name != null && name.equals(targetName)) {
                tryList.add(c);
            }
        }
        return tryList;
    }

    private static ItemStack insertPatternIntoProviderFromPlayerNetwork(ServerPlayer player, ItemStack pattern, long providerId) {
        if (player == null || pattern == null || pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)) {
            return pattern == null ? ItemStack.EMPTY : pattern;
        }

        var grid = WirelessTerminalGridUtil.findPlayerGrid(player);
        if (grid == null) {
            return pattern;
        }

        List<PatternContainer> list = PatternTerminalUtil.listAvailableProvidersFromGrid(grid);
        int index = decodeProviderIndex(providerId);
        if (index < 0 || index >= list.size()) {
            return pattern;
        }

        PatternContainer target = list.get(index);
        if (target == null) {
            return pattern;
        }

        ItemStack remain = pattern.copy();
        for (PatternContainer c : buildSameNameTryList(list, target)) {
            InternalInventory inv = c.getTerminalPatternInventory();
            if (inv == null || inv.size() <= 0) continue;

            ItemStack nextRemain = new FilteredInternalInventory(inv, new ExtendedAEPatternFilter()).addItems(remain.copy());
            if (nextRemain.getCount() < remain.getCount()) {
                remain = nextRemain;
                if (remain.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }

        return remain;
    }

    private static boolean insertIntoInventoryAndShrinkEncodingSlot(InternalInventory targetInventory,
                                                                     net.minecraft.world.inventory.Slot encodedSlot,
                                                                     ItemStack stackInSlot) {
        ItemStack toInsert = stackInSlot.copy();
        ItemStack remain = new FilteredInternalInventory(targetInventory, new ExtendedAEPatternFilter()).addItems(toInsert);
        if (remain.getCount() >= toInsert.getCount()) {
            return false;
        }

        int inserted = toInsert.getCount() - remain.getCount();
        stackInSlot.shrink(inserted);
        encodedSlot.set(stackInSlot.isEmpty() ? ItemStack.EMPTY : stackInSlot);
        return true;
    }

    private static boolean insertIntoInventoryAndShrinkPlayerStack(InternalInventory targetInventory,
                                                                    ItemStack playerStack,
                                                                    Runnable writeBackAfterShrink) {
        ItemStack toInsert = playerStack.copy();
        ItemStack remain = new FilteredInternalInventory(targetInventory, new ExtendedAEPatternFilter()).addItems(toInsert);
        if (remain.getCount() >= toInsert.getCount()) {
            return false;
        }

        int inserted = toInsert.getCount() - remain.getCount();
        playerStack.shrink(inserted);
        writeBackAfterShrink.run();
        return true;
    }

    private static class ExtendedAEPatternFilter implements IAEItemFilter {
        @Override
        public boolean allowExtract(InternalInventory inv, int slot, int amount) {
            return true;
        }

        @Override
        public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
            return !stack.isEmpty() && PatternDetailsHelper.isEncodedPattern(stack);
        }
    }
}
