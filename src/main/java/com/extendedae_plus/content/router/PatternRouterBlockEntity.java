package com.extendedae_plus.content.router;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.*;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.extendedae_plus.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 样板路由器方块实体
 *
 * 核心功能：
 * 1. 从相邻容器读取样板（通过AdjacentInventoryReader）
 * 2. 作为ICraftingProvider提供样板给AE网络
 * 3. 根据样板后缀路由材料到匹配的供应器（通过RoutingManager）
 */
public class PatternRouterBlockEntity extends BlockEntity
        implements IInWorldGridNodeHost, ICraftingProvider {

    // 核心组件
    private final IManagedGridNode managedNode;
    private AdjacentInventoryReader inventoryReader;
    private RoutingManager routingManager;

    // 样板缓存
    private List<IPatternDetails> cachedPatterns = new ArrayList<>();

    public PatternRouterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PATTERN_ROUTER_BE.get(), pos, state);

        // 创建网格节点
        this.managedNode = GridHelper.createManagedNode(this, NodeListener.INSTANCE);
        this.managedNode.setIdlePowerUsage(1.0); // 设置功耗
        this.managedNode.setInWorldNode(true);
        this.managedNode.setFlags(GridFlags.REQUIRE_CHANNEL); // 需要频道
        this.managedNode.setTagName("pattern_router");

        // 注册为合成提供者
        this.managedNode.addService(ICraftingProvider.class, this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            // 延迟初始化，确保world已加载
            GridHelper.onFirstTick(this, be -> {
                be.managedNode.create(be.getLevel(), be.getBlockPos());
                // 首次加载时创建辅助组件
                be.inventoryReader = new AdjacentInventoryReader(be);
                be.routingManager = new RoutingManager();
                be.updatePatternCache();
            });
        }
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

    // ========== ICraftingProvider 实现 ==========

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        // 返回从相邻容器读取到的样板
        return cachedPatterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        // 检查网格是否可用
        if (this.managedNode == null || !this.managedNode.isActive()) {
            return false;
        }

        IGrid grid = this.managedNode.getGrid();
        if (grid == null) {
            return false;
        }

        // 委托路由管理器处理（阶段3实现）
        if (this.routingManager != null) {
            return routingManager.routePattern(patternDetails, inputHolder, grid, null);
        }

        return false;
    }

    @Override
    public boolean isBusy() {
        // 路由器本身不执行合成，永远不忙
        return false;
    }

    // ========== 样板缓存管理 ==========

    /**
     * 更新样板缓存
     * 从相邻容器读取所有样板
     */
    public void updatePatternCache() {
        if (this.inventoryReader != null && this.level != null) {
            this.cachedPatterns = inventoryReader.readPatterns();
            // 通知AE网络样板列表已更新
            ICraftingProvider.requestUpdate(this.managedNode);
        }
    }

    /**
     * 相邻方块变化时调用
     */
    public void onNeighborChanged() {
        // 清除缓存并重新读取样板
        if (this.inventoryReader != null) {
            this.inventoryReader.invalidateCache();
        }
        updatePatternCache();
    }

    // ========== NBT保存/加载 ==========

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.managedNode != null) {
            this.managedNode.saveToNBT(tag);
        }
        // 未来可能需要保存配置数据
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (this.managedNode != null) {
            this.managedNode.loadFromNBT(tag);
        }
        // 未来可能需要加载配置数据
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

    public AdjacentInventoryReader getInventoryReader() {
        return inventoryReader;
    }

    public RoutingManager getRoutingManager() {
        return routingManager;
    }
}
