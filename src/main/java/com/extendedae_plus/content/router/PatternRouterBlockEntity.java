package com.extendedae_plus.content.router;

import appeng.api.networking.*;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.core.definitions.AEItems;
import appeng.core.settings.TickRates;
import appeng.menu.locator.MenuLocators;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import com.extendedae_plus.init.ModBlockEntities;
import com.extendedae_plus.menu.PatternRouterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 样板输入总线方块实体
 *
 * 参考 ME接口 设计：
 * 1. 玩家将编码样板放入存储槽
 * 2. 定期 tick 检查每个样板
 * 3. 根据样板名称后缀找到匹配的供应器
 * 4. 将样板插入供应器（使用正确的 API）
 * 5. 如果没有匹配的供应器，保留在槽中并提示玩家
 */
public class PatternRouterBlockEntity extends BlockEntity implements IInWorldGridNodeHost, InternalInventoryHost, MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternRouterBlockEntity.class);

    // 核心组件
    private final IManagedGridNode managedNode;
    private final AppEngInternalInventory patternStorage; // 存储待插入的样板（9个槽位）
    private PatternInsertionManager insertionManager;

    public PatternRouterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PATTERN_ROUTER_BE.get(), pos, state);

        // 创建样板存储
        this.patternStorage = new AppEngInternalInventory(this, 9, 1);

        // 创建网格节点
        this.managedNode = GridHelper.createManagedNode(this, NodeListener.INSTANCE);
        this.managedNode.setIdlePowerUsage(1.0);
        this.managedNode.setInWorldNode(true);
        this.managedNode.setFlags(GridFlags.REQUIRE_CHANNEL);
        this.managedNode.setTagName("pattern_input_bus");

        // 注册 Ticker 服务
        this.managedNode.addService(IGridTickable.class, new Ticker());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        System.out.println("[PatternRouterBlockEntity] onLoad() called, isClientSide: " + (this.level != null && this.level.isClientSide));
        if (this.level != null && !this.level.isClientSide) {
            System.out.println("[PatternRouterBlockEntity] Server side onLoad");
            GridHelper.onFirstTick(this, be -> {
                System.out.println("[PatternRouterBlockEntity] First tick, creating grid node");
                be.managedNode.create(be.getLevel(), be.getBlockPos());
                be.insertionManager = new PatternInsertionManager();
                System.out.println("[PatternRouterBlockEntity] Initialization complete");
            });
        }
    }

    /**
     * Ticker - 定期尝试插入样板
     */
    private class Ticker implements IGridTickable {
        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            System.out.println("[PatternRouterBlockEntity.Ticker] getTickingRequest() called, hasWorkToDo: " + hasWorkToDo());
            return new TickingRequest(TickRates.Interface, !hasWorkToDo(), false);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            System.out.println("[PatternRouterBlockEntity.Ticker] tickingRequest() called");
            if (!managedNode.isActive()) {
                System.out.println("[PatternRouterBlockEntity.Ticker] Node not active, sleeping");
                return TickRateModulation.SLEEP;
            }

            boolean didWork = tryInsertPatterns();
            System.out.println("[PatternRouterBlockEntity.Ticker] didWork: " + didWork + ", hasWork: " + hasWorkToDo());
            return hasWorkToDo() ?
                (didWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER) :
                TickRateModulation.SLEEP;
        }
    }

    /**
     * 检查是否有待处理的样板
     */
    private boolean hasWorkToDo() {
        for (ItemStack stack : patternStorage) {
            if (!stack.isEmpty() && isEncodedPattern(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试插入所有样板
     */
    private boolean tryInsertPatterns() {
        if (insertionManager == null || !managedNode.isActive()) {
            return false;
        }

        var grid = managedNode.getGrid();
        if (grid == null) {
            return false;
        }

        boolean didWork = false;

        // 遍历所有槽位
        for (int slot = 0; slot < patternStorage.size(); slot++) {
            ItemStack patternStack = patternStorage.getStackInSlot(slot);

            if (patternStack.isEmpty() || !isEncodedPattern(patternStack)) {
                continue;
            }

            // 尝试插入
            boolean success = insertionManager.insertPattern(patternStack, grid, null, worldPosition);

            if (success) {
                // 插入成功，从槽位移除
                patternStorage.setItemDirect(slot, ItemStack.EMPTY);
                didWork = true;
                break; // 每 tick 只处理一个，避免卡顿
            }
        }

        return didWork;
    }

    /**
     * 检查是否是编码样板
     */
    private boolean isEncodedPattern(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // 检查是否是 AE2 的编码样板
        return stack.getItem() == AEItems.PROCESSING_PATTERN.asItem() ||
               stack.getItem() == AEItems.CRAFTING_PATTERN.asItem();
    }

    // ========== IInWorldGridNodeHost 实现 ==========

    @Override
    public @Nullable IGridNode getGridNode(@Nullable Direction dir) {
        return managedNode == null ? null : managedNode.getNode();
    }

    public IManagedGridNode getMainNode() {
        return this.managedNode;
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.managedNode != null) {
            this.managedNode.destroy();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.managedNode != null) {
            this.managedNode.destroy();
        }
    }

    // ========== InternalInventoryHost 实现 ==========

    @Override
    public void saveChanges() {
        setChanged();
    }

    @Override
    public boolean isClientSide() {
        return level == null || level.isClientSide();
    }

    @Override
    public void onChangeInventory(appeng.api.inventories.InternalInventory inv, int slot) {
        // 当样板存储变化时，唤醒 ticker
        if (this.managedNode.isActive()) {
            this.managedNode.ifPresent((grid, node) -> {
                grid.getTickManager().wakeDevice(node);
            });
        }
    }

    // ========== NBT保存/加载 ==========

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.managedNode != null) {
            this.managedNode.saveToNBT(tag);
        }
        this.patternStorage.writeToNBT(tag, "patterns");
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (this.managedNode != null) {
            this.managedNode.loadFromNBT(tag);
        }
        this.patternStorage.readFromNBT(tag, "patterns");
    }

    // ========== 网格节点监听器 ==========

    enum NodeListener implements IGridNodeListener<PatternRouterBlockEntity> {
        INSTANCE;

        @Override
        public void onSaveChanges(PatternRouterBlockEntity host, IGridNode node) {
            host.setChanged();
        }
    }

    // ========== Getter方法 ==========

    public AppEngInternalInventory getPatternStorage() {
        return patternStorage;
    }

    // ========== MenuProvider 实现 ==========

    @Override
    public Component getDisplayName() {
        System.out.println("[PatternRouterBlockEntity] getDisplayName() called");
        return Component.translatable("block.extendedae_plus.pattern_router");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        System.out.println("[PatternRouterBlockEntity] createMenu() called for player: " + (player == null ? "null" : player.getName().getString()));
        System.out.println("[PatternRouterBlockEntity] patternStorage size: " + this.patternStorage.size());
        PatternRouterMenu menu = new PatternRouterMenu(containerId, playerInventory, this);
        System.out.println("[PatternRouterBlockEntity] Menu created successfully");
        return menu;
    }
}
