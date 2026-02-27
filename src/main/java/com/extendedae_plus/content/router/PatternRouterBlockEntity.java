package com.extendedae_plus.content.router;

import appeng.api.networking.*;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.core.definitions.AEItems;
import appeng.core.settings.TickRates;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import com.extendedae_plus.init.ModBlockEntities;
import com.extendedae_plus.util.uploadPattern.PatternRouterUploadUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 样板路由器方块实体
 *
 * 功能：
 * 1. 通过漏斗输入编码样板
 * 2. 定期 tick 检查样板
 * 3. 根据样板名称后缀找到匹配的供应器
 * 4. 将样板插入供应器
 * 5. 如果没有匹配的供应器，保留在存储中
 */
public class PatternRouterBlockEntity extends BlockEntity implements IInWorldGridNodeHost, InternalInventoryHost {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternRouterBlockEntity.class);

    // 核心组件
    private final IManagedGridNode managedNode;
    private final AppEngInternalInventory patternStorage; // 存储待插入的样板（9个槽位）
    
    // 新增：用于检测样板数量变化
    private int[] lastPatternCounts = new int[9];
    private long lastCheckTime = 0;
    private static final long CHECK_INTERVAL = 20; // 20 ticks = 1 second
    
    // 新增：用于跟踪未成功发送的样板
    private List<ItemStack> unsuccessfulPatterns = new ArrayList<>();

    // ItemHandler 包装器 - 允许漏斗输入
    private final LazyOptional<IItemHandler> itemHandlerCap;

    private class PatternRouterItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return patternStorage.size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return patternStorage.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }

            // 只接受编码样板
            if (!isEncodedPattern(stack)) {
                return stack;
            }

            ItemStack copy = stack.copy();
            copy.setCount(1);

            ItemStack existing = patternStorage.getStackInSlot(slot);
            if (existing.isEmpty()) {
                if (!simulate) {
                    patternStorage.setItemDirect(slot, copy);
                    setChanged();
                    // 唤醒 ticker 处理新样板
                    if (managedNode.isActive()) {
                        managedNode.ifPresent((grid, node) -> {
                            grid.getTickManager().wakeDevice(node);
                        });
                    }
                }
                // 返回多余的物品
                ItemStack remaining = stack.copy();
                remaining.shrink(1);
                return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
            }

            return stack; // 槽位已满，返回原物品
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack stack = patternStorage.getStackInSlot(slot);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }

            int toExtract = Math.min(amount, stack.getCount());
            if (!simulate) {
                ItemStack copy = stack.copy();
                copy.setCount(toExtract);
                ItemStack remaining = stack.copy();
                remaining.shrink(toExtract);
                patternStorage.setItemDirect(slot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
                setChanged();
                return copy;
            }

            ItemStack copy = stack.copy();
            copy.setCount(toExtract);
            return copy;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1; // 每个槽位只能放一个样板
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return isEncodedPattern(stack);
        }
    }


    public PatternRouterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PATTERN_ROUTER_BE.get(), pos, state);

        // 创建样板存储
        this.patternStorage = new AppEngInternalInventory(this, 9, 1);

        // 创建 ItemHandler 包装器，允许漏斗输入
        this.itemHandlerCap = LazyOptional.of(() -> new PatternRouterItemHandler());

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
        if (this.level != null && !this.level.isClientSide) {
            GridHelper.onFirstTick(this, be -> {
                be.managedNode.create(be.getLevel(), be.getBlockPos());
            });
        }
    }

    /**
     * Ticker - 定期尝试插入样板
     */
    private class Ticker implements IGridTickable {
        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(TickRates.Interface, !hasWorkToDo(), false);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!managedNode.isActive()) {
                return TickRateModulation.SLEEP;
            }

            // 1. 每秒检查样板数量变化
            boolean patternChanged = checkPatternQuantityChanges();
            
            // 2. 如果样板数量有变化或需要工作，检查相邻容器
            if (patternChanged || hasWorkToDo()) {
                checkAdjacentContainers();
            }
            
            // 3. 尝试插入样板
            boolean didWork = tryInsertPatterns();
            
            // 4. 如果没有更多工作，汇总未成功发送的样板
            if (!hasWorkToDo() && !unsuccessfulPatterns.isEmpty()) {
                summarizeUnsuccessfulPatterns();
            }
            
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
        if (!managedNode.isActive()) {
            return false;
        }

        boolean didWork = false;

        // 遍历所有槽位
        for (int slot = 0; slot < patternStorage.size(); slot++) {
            ItemStack patternStack = patternStorage.getStackInSlot(slot);

            if (patternStack.isEmpty() || !isEncodedPattern(patternStack)) {
                continue;
            }

            // 尝试使用PatternRouterUploadUtil上传样板
            boolean success = PatternRouterUploadUtil.uploadPatternToMatchedProviders(this, patternStack);

            if (success) {
                // 插入成功，从槽位移除
                patternStorage.setItemDirect(slot, ItemStack.EMPTY);
                didWork = true;
                // 从失败列表中移除（如果存在）
                unsuccessfulPatterns.removeIf(stack -> ItemStack.matches(stack, patternStack));
                break; // 每 tick 只处理一个，避免卡顿
            } else {
                // 插入失败，添加到失败列表（如果尚未存在）
                boolean alreadyExists = false;
                for (ItemStack failed : unsuccessfulPatterns) {
                    if (ItemStack.matches(failed, patternStack)) {
                        alreadyExists = true;
                        break;
                    }
                }
                if (!alreadyExists) {
                    unsuccessfulPatterns.add(patternStack.copy());
                }
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
    
    /**
     * 检查相邻容器并提取编码样板
     */
    private void checkAdjacentContainers() {
        if (level == null || level.isClientSide) {
            return;
        }
        
        for (Direction direction : Direction.values()) {
            BlockPos adjacentPos = worldPosition.relative(direction);
            BlockEntity adjacentBE = level.getBlockEntity(adjacentPos);
            if (adjacentBE != null) {
                // 检查是否有物品处理能力（容器）
                var cap = adjacentBE.getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite()).orElse(null);
                if (cap != null) {
                    extractPatternsFromContainer(cap);
                }
            }
        }
    }
    
    /**
     * 从容器中提取编码样板
     */
    private void extractPatternsFromContainer(IItemHandler container) {
        if (container == null) {
            return;
        }
        
        // 遍历容器所有槽位
        for (int slot = 0; slot < container.getSlots(); slot++) {
            ItemStack stack = container.getStackInSlot(slot);
            if (isEncodedPattern(stack)) {
                // 尝试将样板转移到路由器存储中
                ItemStack extracted = container.extractItem(slot, 1, true); // 模拟提取
                if (!extracted.isEmpty() && isEncodedPattern(extracted)) {
                    // 实际提取
                    ItemStack actuallyExtracted = container.extractItem(slot, 1, false);
                    if (!actuallyExtracted.isEmpty()) {
                        // 尝试放入路由器存储
                        boolean stored = false;
                        for (int routerSlot = 0; routerSlot < patternStorage.size(); routerSlot++) {
                            if (patternStorage.getStackInSlot(routerSlot).isEmpty()) {
                                patternStorage.setItemDirect(routerSlot, actuallyExtracted);
                                stored = true;
                                setChanged();
                                break;
                            }
                        }
                        
                        if (!stored) {
                            // 路由器存储已满，放回容器
                            ItemStack remaining = container.insertItem(slot, actuallyExtracted, false);
                            if (!remaining.isEmpty()) {
                                // 无法放回，掉落物品
                                if (level != null) {
                                    net.minecraft.world.entity.item.ItemEntity itemEntity = new net.minecraft.world.entity.item.ItemEntity(
                                        level,
                                        worldPosition.getX() + 0.5,
                                        worldPosition.getY() + 1.0,
                                        worldPosition.getZ() + 0.5,
                                        remaining
                                    );
                                    level.addFreshEntity(itemEntity);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 检查样板数量是否发生变化
     */
    private boolean checkPatternQuantityChanges() {
        if (level == null) {
            return false;
        }
        
        long currentTime = level.getGameTime();
        if (currentTime - lastCheckTime < CHECK_INTERVAL) {
            return false;
        }
        
        lastCheckTime = currentTime;
        boolean hasChanges = false;
        
        // 检查每个槽位的样板数量变化
        for (int i = 0; i < patternStorage.size(); i++) {
            ItemStack currentStack = patternStorage.getStackInSlot(i);
            int currentCount = currentStack.isEmpty() ? 0 : 1; // 样板不堆叠，所以是0或1
            
            if (currentCount != lastPatternCounts[i]) {
                hasChanges = true;
                lastPatternCounts[i] = currentCount;
            }
        }
        
        return hasChanges;
    }
    
    /**
     * 汇总未成功发送的样板
     */
    private void summarizeUnsuccessfulPatterns() {
        if (unsuccessfulPatterns.isEmpty() || level == null || level.isClientSide) {
            return;
        }
        
        // 向附近玩家发送汇总信息
        for (net.minecraft.world.entity.player.Player player : level.players()) {
            if (player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) < 64) {
                Component message = Component.literal("[样板路由器] ").withStyle(net.minecraft.ChatFormatting.YELLOW)
                    .append(Component.translatable("message.extendedae_plus.router.summary_start").withStyle(net.minecraft.ChatFormatting.WHITE));
                
                player.displayClientMessage(message, false);
                
                for (ItemStack pattern : unsuccessfulPatterns) {
                    String patternName = pattern.getHoverName().getString();
                    Component patternMsg = Component.literal("  - ").withStyle(net.minecraft.ChatFormatting.GRAY)
                        .append(Component.literal(patternName).withStyle(net.minecraft.ChatFormatting.WHITE));
                    player.displayClientMessage(patternMsg, false);
                }
                
                Component endMsg = Component.literal("[样板路由器] ").withStyle(net.minecraft.ChatFormatting.YELLOW)
                    .append(Component.translatable("message.extendedae_plus.router.summary_end", unsuccessfulPatterns.size()).withStyle(net.minecraft.ChatFormatting.WHITE));
                player.displayClientMessage(endMsg, false);
            }
        }
        
        // 清空列表
        unsuccessfulPatterns.clear();
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
        this.itemHandlerCap.invalidate();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.managedNode != null) {
            this.managedNode.destroy();
        }
        this.itemHandlerCap.invalidate();
    }

    // ========== Capability 支持 ==========

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return this.itemHandlerCap.cast();
        }
        return super.getCapability(cap, side);
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
}
