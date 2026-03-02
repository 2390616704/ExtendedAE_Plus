package com.extendedae_plus.util;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方完整信息
 *
 * <p>包含配方的所有输入材料（带数量）和输出物品/流体</p>
 */
public class RecipeInfo {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeInfo.class);

    private final Recipe<?> recipe;
    private final boolean isCraftingRecipe;
    private final List<List<GenericStack>> inputs;  // 每个槽位的所有可能材料（物品或流体，包含数量）
    private final List<GenericStack> outputs;       // 输出材料（物品或流体，包含数量）

    public RecipeInfo(
        Recipe<?> recipe,
        boolean isCraftingRecipe,
        List<List<GenericStack>> inputs,
        List<GenericStack> outputs
    ) {
        this.recipe = recipe;
        this.isCraftingRecipe = isCraftingRecipe;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    /**
     * 获取原始配方对象
     */
    public Recipe<?> getRecipe() {
        return recipe;
    }

    /**
     * 是否为工作台配方
     */
    public boolean isCraftingRecipe() {
        return isCraftingRecipe;
    }

    /**
     * 获取输入材料列表
     * 
     * @return 每个槽位的所有可能材料列表（物品或流体，包含数量）
     */
    public List<List<GenericStack>> getInputs() {
        return inputs;
    }

    /**
     * 获取输出材料列表
     * 
     * @return 输出材料列表（物品或流体，包含数量）
     */
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    /**
     * 应用 JEI 书签优先级选择最佳输入材料
     *
     * <p>修复说明：按类型分组选择，同时保留物品和流体，避免数据丢失</p>
     *
     * @param bookmarkPriorities 书签优先级映射（物品 -> 优先级，数值越小优先级越高）
     * @return 选择的材料列表（包含物品和流体，转换为 ItemStack 用于网络传输）
     */
    public List<ItemStack> selectBestInputs(java.util.Map<net.minecraft.world.item.Item, Integer> bookmarkPriorities) {
        List<ItemStack> selected = new ArrayList<>();

        LOGGER.info("[RecipeInfo] ========== 开始选择输入材料 ==========");
        LOGGER.info("[RecipeInfo] 配方: {}, 槽位数量: {}", recipe.getId(), inputs.size());

        int slotIndex = 0;
        for (List<GenericStack> slotOptions : inputs) {
            if (slotOptions == null || slotOptions.isEmpty()) {
                LOGGER.info("[RecipeInfo] 槽位 {} - 空槽位，跳过", slotIndex);
                slotIndex++;
                continue;
            }

            LOGGER.info("[RecipeInfo] 槽位 {} - 总共 {} 个选项", slotIndex, slotOptions.size());

            // 按类型分组：分离物品和流体
            List<GenericStack> items = new ArrayList<>();
            List<GenericStack> fluids = new ArrayList<>();

            for (GenericStack stack : slotOptions) {
                if (stack.what() instanceof AEItemKey itemKey) {
                    items.add(stack);
                    LOGGER.info("[RecipeInfo]   - 物品: {} x{}", itemKey.getItem(), stack.amount());
                } else if (stack.what() instanceof AEFluidKey fluidKey) {
                    fluids.add(stack);
                    LOGGER.info("[RecipeInfo]   - 流体: {} x{}", fluidKey.getFluid(), stack.amount());
                }
            }

            LOGGER.info("[RecipeInfo] 槽位 {} - 物品数: {}, 流体数: {}", slotIndex, items.size(), fluids.size());

            // 从物品中选择优先级最高的（如果有）
            if (!items.isEmpty()) {
                GenericStack bestItem = selectBestByPriority(items, bookmarkPriorities);
                ItemStack itemStack = toItemStack(bestItem);
                selected.add(itemStack);
                LOGGER.info("[RecipeInfo] 槽位 {} - 选中物品: {}", slotIndex, itemStack);
            }

            // 添加流体（如果有）
            if (!fluids.isEmpty()) {
                GenericStack fluidStack = fluids.get(0);
                ItemStack wrappedFluid = toItemStack(fluidStack);
                selected.add(wrappedFluid);
                LOGGER.info("[RecipeInfo] 槽位 {} - 选中流体: {} (包装为ItemStack)", slotIndex, fluidStack.what());
            }

            slotIndex++;
        }

        LOGGER.info("[RecipeInfo] ========== 选择完成，共 {} 个材料 ==========", selected.size());
        for (int i = 0; i < selected.size(); i++) {
            ItemStack stack = selected.get(i);
            LOGGER.info("[RecipeInfo] 结果[{}]: {}", i, stack);
        }

        return selected;
    }

    /**
     * 从选项列表中根据优先级选择最佳材料
     */
    private GenericStack selectBestByPriority(List<GenericStack> options, java.util.Map<net.minecraft.world.item.Item, Integer> priorities) {
        GenericStack best = options.get(0);
        int bestPriority = getPriority(best, priorities);

        LOGGER.info("[RecipeInfo]   > 优先级选择: {} 个选项", options.size());
        LOGGER.info("[RecipeInfo]   > 初始最佳: {}, 优先级: {}", best.what(), bestPriority);

        for (int i = 1; i < options.size(); i++) {
            GenericStack option = options.get(i);
            int priority = getPriority(option, priorities);

            LOGGER.info("[RecipeInfo]   > 选项{}: {}, 优先级: {}", i, option.what(), priority);

            if (priority < bestPriority) {
                best = option;
                bestPriority = priority;
                LOGGER.info("[RecipeInfo]   > 更新最佳选择: {}", best.what());
            }
        }

        LOGGER.info("[RecipeInfo]   > 最终选择: {}, 优先级: {}", best.what(), bestPriority);
        return best;
    }

    /**
     * 获取材料的优先级
     */
    private int getPriority(GenericStack stack, java.util.Map<net.minecraft.world.item.Item, Integer> priorities) {
        if (stack.what() instanceof AEItemKey itemKey) {
            int priority = priorities.getOrDefault(itemKey.getItem(), Integer.MAX_VALUE);
            LOGGER.trace("[RecipeInfo]     getPriority({}) = {}", itemKey.getItem(), priority);
            return priority;
        }
        // 流体没有书签优先级，返回默认值
        LOGGER.trace("[RecipeInfo]     getPriority(fluid) = Integer.MAX_VALUE");
        return Integer.MAX_VALUE;
    }

    /**
     * 将 GenericStack 转换为 ItemStack
     *
     * <p>物品直接转换，流体会被包装成 GenericStack.wrapInItemStack</p>
     */
    private ItemStack toItemStack(GenericStack stack) {
        if (stack.what() instanceof AEItemKey itemKey) {
            ItemStack result = itemKey.toStack((int) stack.amount());
            LOGGER.info("[RecipeInfo]     toItemStack(物品): {} -> {}", itemKey.getItem(), result);
            return result;
        } else if (stack.what() instanceof AEFluidKey fluidKey) {
            // 流体需要包装成特殊的 ItemStack
            ItemStack result = GenericStack.wrapInItemStack(stack);
            LOGGER.info("[RecipeInfo]     toItemStack(流体): {} -> 包装ItemStack {}", fluidKey.getFluid(), result);
            return result;
        }
        LOGGER.warn("[RecipeInfo]     toItemStack: 未知类型 {}", stack.what());
        return ItemStack.EMPTY;
    }
}
