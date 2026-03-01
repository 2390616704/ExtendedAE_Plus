package com.extendedae_plus.util.uploadPattern;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.util.inv.FilteredInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;
import com.extendedae_plus.init.ModNetwork;
import com.extendedae_plus.mixin.ae2.accessor.PatternEncodingTermMenuAccessor;
import com.extendedae_plus.network.provider.ProvidersListS2CPacket;
import com.extendedae_plus.util.PatternProviderDataUtil;
import com.extendedae_plus.util.PatternTerminalUtil;
import com.extendedae_plus.util.wireless.WirelessTerminalLocator;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import de.mari_023.ae2wtlib.wut.WTDefinition;
import de.mari_023.ae2wtlib.wut.WUTHandler;
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
    private static final String PENDING_DATA_KEY = "eap_ctrlq_pending_provider_upload_id";
    private static final String PENDING_STACK_KEY = "eap_ctrlq_pending_provider_upload_stack";

    private ProviderUploadUtil() {}

    /**
     * 发送消息给玩家
     *
     * @param player 玩家
     * @param message 消息内容
     */
    private static void sendMessage(ServerPlayer player, String message) {
        // 静默:不再向玩家左下角发送任何提示信息
        // 如需恢复,取消下面注释即可:
        // if (player != null) {
        //     player.sendSystemMessage(Component.literal(message));
        // }
        // 如果玩家为null,静默忽略(用于测试环境)
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
     * 缓存 Ctrl+Q 生成的待上传样板(不放入玩家背包)。
     */
    public static String beginPendingCtrlQUpload(ServerPlayer player, ItemStack pattern) {
        if (player == null || pattern == null || pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)) {
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

    public static boolean hasPendingCtrlQPattern(ServerPlayer player) {
        if (player == null) return false;
        String id = player.getPersistentData().getString(PENDING_DATA_KEY);
        if (id == null || id.isBlank()) return false;
        return !getPendingCtrlQPattern(player).isEmpty();
    }

    /**
     * 将 pending Ctrl+Q 样板上传到玩家网络中的目标 provider(负数索引 ID)。
     */
    public static boolean uploadPendingCtrlQPattern(ServerPlayer player, long providerId) {
        if (player == null) return false;
        ItemStack pending = getPendingCtrlQPattern(player);
        if (pending.isEmpty()) return false;

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

    /**
     * 列出玩家无线终端网络中的可用 provider,顺序与负数索引上传保持一致。
     */
    public static List<PatternContainer> listAvailableProvidersFromPlayerNetwork(ServerPlayer player) {
        IGrid grid = findPlayerGrid(player);
        return PatternTerminalUtil.listAvailableProvidersFromGrid(grid);
    }

    /**
     * 打开 provider 选择界面,供 Ctrl+Q 使用
     */
    public static boolean openProviderSelectionForPlayerNetwork(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        IGrid grid = findPlayerGrid(player);
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

    private static ItemStack getPendingCtrlQPattern(ServerPlayer player) {
        if (player == null) return ItemStack.EMPTY;
        String id = player.getPersistentData().getString(PENDING_DATA_KEY);
        if (id == null || id.isBlank()) return ItemStack.EMPTY;

        CompoundTag data = player.getPersistentData();
        if (!data.contains(PENDING_STACK_KEY)) return ItemStack.EMPTY;
        CompoundTag stackTag = data.getCompound(PENDING_STACK_KEY);
        ItemStack stack = ItemStack.of(stackTag);
        if (stack.isEmpty() || !PatternDetailsHelper.isEncodedPattern(stack)) {
            clearPendingCtrlQUpload(player);
            return ItemStack.EMPTY;
        }
        return stack;
    }

    private static ItemStack insertPatternIntoProviderFromPlayerNetwork(ServerPlayer player, ItemStack pattern, long providerId) {
        if (player == null || pattern == null || pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)) {
            return pattern == null ? ItemStack.EMPTY : pattern;
        }

        int index = decodeProviderIndex(providerId);
        if (index < 0) return pattern;

        List<PatternContainer> providers = listAvailableProvidersFromPlayerNetwork(player);
        if (index >= providers.size()) return pattern;

        PatternContainer target = providers.get(index);
        if (target == null) return pattern;

        ItemStack remain = pattern.copy();
        for (PatternContainer container : buildSameNameTryList(providers, target)) {
            InternalInventory inv = container.getTerminalPatternInventory();
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

    private static int decodeProviderIndex(long providerId) {
        if (providerId >= 0) return -1;
        long idx = -1L - providerId;
        if (idx > Integer.MAX_VALUE) return -1;
        return (int) idx;
    }

    private static List<PatternContainer> buildSameNameTryList(List<PatternContainer> all, PatternContainer target) {
        String targetName = PatternProviderDataUtil.getProviderDisplayName(target);
        List<PatternContainer> tryList = new java.util.ArrayList<>();
        tryList.add(target);
        for (PatternContainer container : all) {
            if (container == null || container == target) continue;
            String name = PatternProviderDataUtil.getProviderDisplayName(container);
            if (name != null && name.equals(targetName)) {
                tryList.add(container);
            }
        }
        return tryList;
    }

    private static IGrid findPlayerGrid(ServerPlayer player) {
        WirelessTerminalLocator.LocatedTerminal located = WirelessTerminalLocator.find(player);
        ItemStack terminal = located.stack;
        if (terminal.isEmpty()) {
            return null;
        }

        String curiosSlotId = located.getCuriosSlotId();
        int curiosIndex = located.getCuriosIndex();

        if (curiosSlotId != null && curiosIndex >= 0) {
            try {
                String current = WUTHandler.getCurrentTerminal(terminal);
                WTDefinition def = WUTHandler.wirelessTerminals.get(current);
                if (def != null) {
                    WTMenuHost wtHost = def.wTMenuHostFactory().create(player, null, terminal, (p, sub) -> {});
                    if (wtHost != null) {
                        IGridNode node = wtHost.getActionableNode();
                        if (node != null) {
                            return node.getGrid();
                        }
                    }
                }
            } catch (Exception ignored) {
                return null;
            }
        } else {
            WirelessTerminalItem wt = terminal.getItem() instanceof WirelessTerminalItem t ? t : null;
            if (wt != null) {
                return wt.getLinkedGrid(terminal, player.serverLevel(), player);
            }
        }

        return null;
    }

    /**
     * ExtendedAE兼容的样板过滤器
     * 使用AE2的PatternDetailsHelper进行样板验证
     */
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
