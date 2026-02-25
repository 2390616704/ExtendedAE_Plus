package com.extendedae_plus.content.router;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 相邻容器读取器
 *
 * 功能：
 * 1. 从6个方向的相邻容器读取样板
 * 2. 使用Forge ItemHandler能力系统访问容器
 * 3. 缓存容器引用以提高性能
 */
public class AdjacentInventoryReader {

    private final PatternRouterBlockEntity host;

    // 缓存相邻容器引用（用于优化）
    private final Map<Direction, BlockEntity> cachedContainers = new EnumMap<>(Direction.class);

    public AdjacentInventoryReader(PatternRouterBlockEntity host) {
        this.host = host;
    }

    /**
     * 从所有相邻容器读取样板
     *
     * @return 所有找到的有效样板列表
     */
    public List<IPatternDetails> readPatterns() {
        List<IPatternDetails> allPatterns = new ArrayList<>();
        Level level = host.getLevel();
        if (level == null || level.isClientSide()) {
            return allPatterns;
        }

        BlockPos routerPos = host.getBlockPos();

        // 遍历6个方向
        for (Direction dir : Direction.values()) {
            BlockPos adjacentPos = routerPos.relative(dir);
            BlockEntity adjacentBE = level.getBlockEntity(adjacentPos);

            if (adjacentBE != null) {
                // 尝试从该容器读取样板
                readPatternsFromContainer(adjacentBE, dir, allPatterns, level);

                // 缓存容器引用
                cachedContainers.put(dir, adjacentBE);
            } else {
                // 清除已移除的容器缓存
                cachedContainers.remove(dir);
            }
        }

        return allPatterns;
    }

    /**
     * 从单个容器读取样板
     *
     * @param container 容器方块实体
     * @param direction 相对于路由器的方向
     * @param patterns 用于收集样板的列表
     * @param level 世界引用
     */
    private void readPatternsFromContainer(BlockEntity container, Direction direction,
                                          List<IPatternDetails> patterns, Level level) {
        // 尝试获取ItemHandler能力
        var itemHandlerCap = container.getCapability(
                ForgeCapabilities.ITEM_HANDLER,
                direction.getOpposite() // 从容器的角度看，是相反的方向
        );

        itemHandlerCap.ifPresent(handler -> {
            // 遍历容器的所有槽位
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);

                // 只处理非空物品
                if (stack.isEmpty()) {
                    continue;
                }

                // 检查是否为编码样板
                if (PatternDetailsHelper.isEncodedPattern(stack)) {
                    try {
                        // 解码样板
                        IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
                        if (details != null) {
                            patterns.add(details);
                        }
                    } catch (Exception e) {
                        // 忽略解码失败的样板
                        // 可能是损坏的或不支持的样板格式
                    }
                }
            }
        });
    }

    /**
     * 获取缓存的相邻容器
     *
     * @return 方向到容器的映射
     */
    public Map<Direction, BlockEntity> getCachedContainers() {
        return cachedContainers;
    }

    /**
     * 清除缓存
     * 当相邻方块变化时应调用此方法
     */
    public void invalidateCache() {
        cachedContainers.clear();
    }

    /**
     * 检查指定方向是否有容器
     *
     * @param direction 要检查的方向
     * @return 是否存在容器
     */
    public boolean hasContainerInDirection(Direction direction) {
        Level level = host.getLevel();
        if (level == null) {
            return false;
        }

        BlockPos adjacentPos = host.getBlockPos().relative(direction);
        BlockEntity adjacentBE = level.getBlockEntity(adjacentPos);

        if (adjacentBE == null) {
            return false;
        }

        // 检查是否有ItemHandler能力
        var cap = adjacentBE.getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite());
        return cap.isPresent();
    }

    /**
     * 获取所有有容器的方向
     *
     * @return 有容器的方向列表
     */
    public List<Direction> getDirectionsWithContainers() {
        List<Direction> directions = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            if (hasContainerInDirection(dir)) {
                directions.add(dir);
            }
        }
        return directions;
    }
}
