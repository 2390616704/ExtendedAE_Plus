package com.extendedae_plus.util;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.extendedae_plus.integration.jei.JeiRuntimeProxy;
import com.extendedae_plus.util.uploadPattern.RecipeTypeNameConfig;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 配方查找工具类
 *
 * <p>使用 JEI API 根据物品查找相关配方,返回包含完整数量信息的 RecipeInfo</p>
 */
public class RecipeFinderUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("ExtendedAE Plus - RecipeFinder");

    /**
     * 根据JEI物品或流体查找相关配方(仅搜索以该物品/流体为输出的配方)
     *
     * @param ingredient JEI物品或流体
     * @return 相关配方信息列表(包含完整的输入输出数量)
     */
    public static List<RecipeInfo> findRecipesByIngredient(ITypedIngredient<?> ingredient) {
        // 获取 JEI Runtime
        IJeiRuntime jeiRuntime = JeiRuntimeProxy.get();
        if (jeiRuntime == null) {
            LOGGER.warn("[RecipeFinder] JEI Runtime not available");
            return List.of();
        }

        IJeiHelpers jeiHelpers = jeiRuntime.getJeiHelpers();
        IRecipeManager recipeManager = jeiRuntime.getRecipeManager();
        IFocusFactory focusFactory = jeiHelpers.getFocusFactory();

        // 创建输出焦点(OUTPUT role)
        IFocus<?> outputFocus = focusFactory.createFocus(
            RecipeIngredientRole.OUTPUT,
            ingredient
        );

        List<RecipeInfo> results = new ArrayList<>();

        // 查找工作台配方
        try {
            IRecipeCategory<CraftingRecipe> craftingCategory = recipeManager.getRecipeCategory(RecipeTypes.CRAFTING);

            recipeManager.createRecipeLookup(RecipeTypes.CRAFTING)
                .limitFocus(List.of(outputFocus))
                .get()
                .forEach(recipe -> {
                    // 创建配方布局以获取完整信息
                    Optional<IRecipeLayoutDrawable<CraftingRecipe>> layoutOpt =
                        recipeManager.createRecipeLayoutDrawable(
                            craftingCategory,
                            recipe,
                            focusFactory.getEmptyFocusGroup()
                        );

                    layoutOpt.ifPresent(layout -> {
                        RecipeInfo info = extractRecipeInfo(recipe, layout, true);
                        if (info != null) {
                            results.add(info);
                        }
                    });
                });
        } catch (Exception e) {
            LOGGER.warn("[RecipeFinder] Error searching crafting recipes: {}", e.getMessage());
        }

        // 查找其他所有配方类型(排除工作台配方)
        try {
            jeiHelpers.getAllRecipeTypes().forEach(recipeType -> {
                // 跳过工作台配方(已经处理过)
                if (recipeType.equals(RecipeTypes.CRAFTING)) {
                    return;
                }

                try {
                    @SuppressWarnings("unchecked")
                    IRecipeCategory<Recipe<?>> category = (IRecipeCategory<Recipe<?>>) recipeManager.getRecipeCategory(recipeType);

                    recipeManager.createRecipeLookup(recipeType)
                        .limitFocus(List.of(outputFocus))
                        .get()
                        .forEach(recipe -> {
                            if (recipe instanceof Recipe<?> rawRecipe) {
                                // 创建配方布局以获取完整信息
                                Optional<IRecipeLayoutDrawable<Recipe<?>>> layoutOpt =
                                    recipeManager.createRecipeLayoutDrawable(
                                        category,
                                        rawRecipe,
                                        focusFactory.getEmptyFocusGroup()
                                    );

                                layoutOpt.ifPresent(layout -> {
                                    RecipeInfo info = extractRecipeInfo(rawRecipe, layout, false);
                                    if (info != null) {
                                        results.add(info);
                                    }
                                });
                            }
                        });
                } catch (Exception e) {
                    // 某些配方类型可能不支持,静默忽略
                }
            });
        } catch (Exception e) {
            LOGGER.warn("[RecipeFinder] Error searching other recipe types: {}", e.getMessage());
        }

        // 记录日志
        String ingredientDesc;
        if (ingredient.getType() == VanillaTypes.ITEM_STACK) {
            ingredientDesc = ((ItemStack) ingredient.getIngredient()).getDescriptionId();
        } else if (ingredient.getType() == ForgeTypes.FLUID_STACK) {
            FluidStack fluidStack = (FluidStack) ingredient.getIngredient();
            ingredientDesc = fluidStack.getFluid().toString();
        } else {
            ingredientDesc = ingredient.toString();
        }

        LOGGER.debug("[RecipeFinder] Found {} recipes for output: {}", results.size(), ingredientDesc);

        return results;
    }

    /**
     * 从配方中提取完整的配方信息（混合方案）
     *
     * <p><strong>混合提取策略（更准确）：</strong></p>
     * <ul>
     *   <li><strong>物品输入/输出</strong>：从 {@link Recipe} 对象直接获取（最准确）</li>
     *   <li><strong>流体输入/输出</strong>：从 JEI 配方布局提取（Recipe API 不支持流体）</li>
     * </ul>
     *
     * @param recipe 原始配方对象（用于物品信息）
     * @param layout JEI 配方布局（仅用于流体信息）
     * @param isCrafting 是否为工作台配方
     * @return 配方信息，如果提取失败返回 null
     */
    private static <T> RecipeInfo extractRecipeInfo(
        Recipe<?> recipe,
        IRecipeLayoutDrawable<T> layout,
        boolean isCrafting
    ) {
        try {
            // ========== 第一步：从 Recipe 对象提取物品输入（最准确） ==========
            List<List<GenericStack>> inputs = new ArrayList<>();

            for (net.minecraft.world.item.crafting.Ingredient ingredient : recipe.getIngredients()) {
                List<GenericStack> slotStacks = new ArrayList<>();

                // 将 Ingredient 的所有可能物品转换为 GenericStack
                for (ItemStack itemStack : ingredient.getItems()) {
                    if (!itemStack.isEmpty()) {
                        AEItemKey itemKey = AEItemKey.of(itemStack);
                        if (itemKey != null) {
                            slotStacks.add(new GenericStack(itemKey, itemStack.getCount()));
                        }
                    }
                }

                inputs.add(slotStacks);
            }

            // ========== 第二步：从 JEI 布局提取流体输入（Recipe API 不支持） ==========
            IRecipeSlotsView slotsView = layout.getRecipeSlotsView();
            List<IRecipeSlotView> inputSlots = slotsView.getSlotViews(RecipeIngredientRole.INPUT);

            for (IRecipeSlotView slot : inputSlots) {
                List<GenericStack> fluidStacks = new ArrayList<>();

                // 只提取流体（物品已从 Recipe 获取）
                for (ITypedIngredient<?> typedIngredient : slot.getAllIngredients().toList()) {
                    if (typedIngredient.getType() == ForgeTypes.FLUID_STACK) {
                        FluidStack fluidStack = (FluidStack) typedIngredient.getIngredient();
                        if (!fluidStack.isEmpty()) {
                            AEFluidKey fluidKey = AEFluidKey.of(fluidStack);
                            if (fluidKey != null) {
                                fluidStacks.add(new GenericStack(fluidKey, fluidStack.getAmount()));
                            }
                        }
                    }
                }

                // 如果这个槽位有流体，添加到 inputs
                if (!fluidStacks.isEmpty()) {
                    inputs.add(fluidStacks);
                }
            }

            // ========== 第三步：从 Recipe 对象提取物品输出（最准确） ==========
            List<GenericStack> outputs = new ArrayList<>();

            ItemStack resultItem = recipe.getResultItem(null);
            if (!resultItem.isEmpty()) {
                AEItemKey itemKey = AEItemKey.of(resultItem);
                if (itemKey != null) {
                    outputs.add(new GenericStack(itemKey, resultItem.getCount()));
                }
            }

            // ========== 第四步：从 JEI 布局提取流体输出（Recipe API 不支持） ==========
            List<IRecipeSlotView> outputSlots = slotsView.getSlotViews(RecipeIngredientRole.OUTPUT);

            for (IRecipeSlotView slot : outputSlots) {
                // 只提取流体（物品已从 Recipe 获取）
                for (ITypedIngredient<?> typedIngredient : slot.getAllIngredients().toList()) {
                    if (typedIngredient.getType() == ForgeTypes.FLUID_STACK) {
                        FluidStack fluidStack = (FluidStack) typedIngredient.getIngredient();
                        if (!fluidStack.isEmpty()) {
                            AEFluidKey fluidKey = AEFluidKey.of(fluidStack);
                            if (fluidKey != null) {
                                outputs.add(new GenericStack(fluidKey, fluidStack.getAmount()));
                            }
                        }
                    }
                }
            }

            return new RecipeInfo(recipe, isCrafting, inputs, outputs);

        } catch (Exception e) {
            LOGGER.warn("[RecipeFinder] Failed to extract recipe info for {}: {}",
                recipe.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 选择最佳配方（三级优先级）
     *
     * <p><strong>优先级策略：</strong></p>
     * <ol>
     *   <li><strong>Priority 1（最高）</strong>：玩家收藏的工作方块对应的配方</li>
     *   <li><strong>Priority 2</strong>：工作台配方（CraftingRecipe）</li>
     *   <li><strong>Priority 3（兜底）</strong>：列表中的第一个配方</li>
     * </ol>
     *
     * @param recipes 配方信息列表
     * @return 最佳配方信息，如果列表为空返回null
     */
    public static RecipeInfo selectBestRecipe(List<RecipeInfo> recipes) {
        if (recipes.isEmpty()) {
            return null;
        }

        LOGGER.info("[RecipeFinder] ========== 开始选择最佳配方 ==========");
        LOGGER.info("[RecipeFinder] 候选配方数量: {}", recipes.size());
        for (int i = 0; i < recipes.size(); i++) {
            RecipeInfo info = recipes.get(i);
            LOGGER.info("[RecipeFinder]   [{}] {} (类型: {})",
                i, info.getRecipe().getId(), info.getRecipe().getClass().getSimpleName());
        }

        // Priority 1: 玩家收藏的工作方块对应的配方 - 最高优先级
        LOGGER.info("[RecipeFinder] 尝试 Priority 1: 检查玩家收藏的工作方块配方...");
        RecipeInfo bookmarkedRecipe = selectRecipeByBookmarkedWorkstation(recipes);
        if (bookmarkedRecipe != null) {
            LOGGER.info("[RecipeFinder] ✓ Priority 1 成功! 选择收藏工作方块配方: {}",
                bookmarkedRecipe.getRecipe().getId());
            LOGGER.info("[RecipeFinder] ========================================");
            return bookmarkedRecipe;
        }
        LOGGER.info("[RecipeFinder] ✗ Priority 1 未找到匹配的收藏工作方块配方");

        // Priority 2: CraftingRecipe（工作台配方）- 第二优先级
        LOGGER.info("[RecipeFinder] 尝试 Priority 2: 检查工作台配方...");
        for (RecipeInfo info : recipes) {
            if (info.isCraftingRecipe()) {
                LOGGER.info("[RecipeFinder] ✓ Priority 2 成功! 选择工作台配方: {}",
                    info.getRecipe().getId());
                LOGGER.info("[RecipeFinder] ========================================");
                return info;
            }
        }
        LOGGER.info("[RecipeFinder] ✗ Priority 2 未找到工作台配方");

        // Priority 3: 列表中的第一个配方 - 兜底方案
        LOGGER.info("[RecipeFinder] 使用 Priority 3: 选择第一个配方: {}",
            recipes.get(0).getRecipe().getId());
        LOGGER.info("[RecipeFinder] ========================================");
        return recipes.get(0);
    }

    /**
     * 根据玩家收藏的工作方块选择配方
     *
     * <p>使用配方类别优先级列表，选择优先级最高的配方</p>
     *
     * @param recipes 候选配方列表
     * @return 匹配收藏工作方块的配方，如果没有匹配则返回 null
     */
    private static RecipeInfo selectRecipeByBookmarkedWorkstation(List<RecipeInfo> recipes) {
        LOGGER.debug("[RecipeFinder] >> 进入 selectRecipeByBookmarkedWorkstation");

        // 1. 获取 JEI Runtime
        IJeiRuntime runtime = JeiRuntimeProxy.get();
        if (runtime == null) {
            LOGGER.debug("[RecipeFinder] << JEI Runtime 未初始化，返回 null");
            return null;
        }
        LOGGER.debug("[RecipeFinder] JEI Runtime 已就绪");

        // 2. 构建工作方块到配方类别的映射（使用缓存）
        LOGGER.debug("[RecipeFinder] 正在构建工作方块映射...");
        java.util.Map<net.minecraft.world.item.Item, Set<ResourceLocation>> workstationMapping =
            RecipeTypeNameConfig.buildWorkstationToRecipeTypeMappingCached(runtime);

        if (workstationMapping.isEmpty()) {
            LOGGER.debug("[RecipeFinder] << 工作方块映射为空，返回 null");
            return null;
        }
        LOGGER.info("[RecipeFinder] 工作方块映射包含 {} 个工作方块", workstationMapping.size());

        // 3. 获取玩家收藏的所有物品
        LOGGER.debug("[RecipeFinder] 正在获取玩家收藏的物品...");
        List<ItemStack> bookmarkedItems = RecipeTypeNameConfig.getBookmarkedWorkstations();

        if (bookmarkedItems.isEmpty()) {
            LOGGER.info("[RecipeFinder] << 玩家没有收藏任何物品，返回 null");
            return null;
        }
        LOGGER.info("[RecipeFinder] 玩家收藏了 {} 个物品", bookmarkedItems.size());

        // 4. 构建配方类别优先级列表（按收藏顺序）
        LOGGER.debug("[RecipeFinder] 正在构建配方类别优先级列表...");
        List<ResourceLocation> priorityCategories = new ArrayList<>();

        for (ItemStack stack : bookmarkedItems) {
            net.minecraft.world.item.Item item = stack.getItem();
            if (workstationMapping.containsKey(item)) {
                Set<ResourceLocation> categories = workstationMapping.get(item);
                LOGGER.debug("[RecipeFinder]   工作方块 {} 支持类别: {}", item, categories);

                // 将该工作方块支持的所有类别添加到优先级列表（保持顺序，避免重复）
                for (ResourceLocation category : categories) {
                    if (!priorityCategories.contains(category)) {
                        priorityCategories.add(category);
                        LOGGER.debug("[RecipeFinder]   添加类别到优先级列表[位置 {}]: {}",
                            priorityCategories.size() - 1, category);
                    }
                }
            } else {
                LOGGER.debug("[RecipeFinder]   ✗ {} 不是工作方块，跳过", item);
            }
        }

        if (priorityCategories.isEmpty()) {
            LOGGER.info("[RecipeFinder] << 收藏的物品中没有工作方块，返回 null");
            return null;
        }

        LOGGER.info("[RecipeFinder] 配方类别优先级列表（共 {} 个）:", priorityCategories.size());
        for (int i = 0; i < priorityCategories.size(); i++) {
            LOGGER.info("[RecipeFinder]   优先级[{}]: {}", i, priorityCategories.get(i));
        }

        // 5. 为每个候选配方计算优先级，选择优先级最高的
        LOGGER.debug("[RecipeFinder] 开始为候选配方计算优先级...");
        RecipeInfo bestRecipe = null;
        int bestPriority = Integer.MAX_VALUE;

        for (int i = 0; i < recipes.size(); i++) {
            RecipeInfo recipeInfo = recipes.get(i);
            Recipe<?> recipe = recipeInfo.getRecipe();

            // 获取配方所属的 JEI 类别 UID
            LOGGER.debug("[RecipeFinder]   [配方 {}] 正在查询类别: {}", i, recipe.getId());
            ResourceLocation recipeCategory = getRecipeTypeId(runtime, recipe);

            if (recipeCategory == null) {
                LOGGER.debug("[RecipeFinder]   [配方 {}] 没有对应的 JEI 类别，跳过", i);
                continue;
            }

            LOGGER.debug("[RecipeFinder]   [配方 {}] 类别: {}", i, recipeCategory);

            // 查找该类别在优先级列表中的位置
            int priority = priorityCategories.indexOf(recipeCategory);

            if (priority == -1) {
                LOGGER.debug("[RecipeFinder]   [配方 {}] 类别 {} 不在优先级列表中，跳过", i, recipeCategory);
                continue;
            }

            LOGGER.info("[RecipeFinder]   [配方 {}] 优先级: {} (类别: {})", i, priority, recipeCategory);

            // 更新最佳配方（优先级数字越小越高）
            if (priority < bestPriority) {
                bestPriority = priority;
                bestRecipe = recipeInfo;
                LOGGER.info("[RecipeFinder]   ✓ 更新最佳配方: {} (优先级: {})", recipe.getId(), priority);
            }
        }

        if (bestRecipe != null) {
            LOGGER.info("[RecipeFinder] << ✓✓✓ 最终选择: 配方={}, 优先级={}, 类别={}",
                bestRecipe.getRecipe().getId(), bestPriority, priorityCategories.get(bestPriority));
        } else {
            LOGGER.info("[RecipeFinder] << 未找到匹配优先级列表的配方");
        }

        return bestRecipe;
    }

    /**
     * 获取配方对应的 JEI 配方类别 UID
     *
     * <p>遍历所有 JEI 类别，找到包含该配方的类别</p>
     *
     * @param runtime JEI 运行时
     * @param recipe  配方对象
     * @return 配方所属的 JEI 类别 UID，如果未找到则返回 null
     */
    private static ResourceLocation getRecipeTypeId(IJeiRuntime runtime, Recipe<?> recipe) {
        if (runtime == null || recipe == null) {
            LOGGER.debug("[RecipeFinder]     getRecipeTypeId: runtime 或 recipe 为 null");
            return null;
        }

        LOGGER.debug("[RecipeFinder]     >> 查找配方 {} 的类别 UID", recipe.getId());

        try {
            IRecipeManager recipeManager = runtime.getRecipeManager();
            List<IRecipeCategory<?>> allCategories = recipeManager.createRecipeCategoryLookup().get().toList();
            LOGGER.debug("[RecipeFinder]     JEI 总共有 {} 个配方类别", allCategories.size());

            int categoryIndex = 0;
            for (IRecipeCategory<?> category : allCategories) {
                try {
                    mezz.jei.api.recipe.RecipeType<?> jeiRecipeType = category.getRecipeType();
                    ResourceLocation categoryUid = jeiRecipeType.getUid();

                    // 获取该类别的所有配方
                    List<?> recipesInCategory = recipeManager.createRecipeLookup(jeiRecipeType)
                        .get()
                        .toList();

                    LOGGER.debug("[RecipeFinder]     检查类别[{}]: {} (包含 {} 个配方)",
                        categoryIndex++, categoryUid, recipesInCategory.size());

                    // 检查当前配方是否在此类别中
                    for (Object recipeObj : recipesInCategory) {
                        if (recipeObj == recipe ||
                            (recipeObj instanceof Recipe<?> r && r.getId().equals(recipe.getId()))) {
                            LOGGER.debug("[RecipeFinder]     << ✓ 找到! 配方属于类别: {}", categoryUid);
                            return categoryUid;
                        }
                    }

                } catch (Exception e) {
                    LOGGER.debug("[RecipeFinder]     类别检查出错: {}", e.getMessage());
                }
            }

            LOGGER.debug("[RecipeFinder]     << 未找到配方对应的类别");

        } catch (Exception e) {
            LOGGER.debug("[RecipeFinder] 获取配方类别 UID 时出错: {}", e.getMessage());
        }

        return null;
    }
}
