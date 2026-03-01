package com.extendedae_plus.util;

import com.extendedae_plus.integration.jei.JeiRuntimeProxy;
import com.extendedae_plus.util.uploadPattern.RecipeTypeNameConfig;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RecipeFinderUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("ExtendedAE Plus - RecipeFinder");

    private RecipeFinderUtil() {
    }

    public static Recipe<?> findRecipeById(Level level, ResourceLocation recipeId) {
        if (level == null || recipeId == null) {
            return null;
        }
        try {
            return level.getRecipeManager().byKey(recipeId).orElse(null);
        } catch (Throwable t) {
            LOGGER.debug("[RecipeFinder] findRecipeById failed: id={}, err={}", recipeId, t.toString());
            return null;
        }
    }

    public static List<Recipe<?>> findRecipesByIngredient(ITypedIngredient<?> ingredient, Level level) {
        if (ingredient == null || level == null) {
            return List.of();
        }

        ItemStack target = extractItemStackFromTypedIngredient(ingredient);
        if (target.isEmpty()) {
            return List.of();
        }

        return findRecipesByOutputItem(target, level);
    }

    public static List<Recipe<?>> findRecipesByOutputItem(ItemStack target, Level level) {
        List<Recipe<?>> results = new ArrayList<>();
        if (level == null || target == null || target.isEmpty()) {
            return results;
        }

        IJeiRuntime runtime = JeiRuntimeProxy.get();
        if (runtime == null) {
            return results;
        }

        try {
            IRecipeManager recipeManager = runtime.getRecipeManager();
            IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();

            Set<ResourceLocation> seenRecipeIds = new LinkedHashSet<>();
            Set<Recipe<?>> seenNoIdRecipes = Collections.newSetFromMap(new IdentityHashMap<>());
            ItemStack normalizedTarget = normalizeFocusStack(target);

            collectRecipesByRole(recipeManager, focusFactory, normalizedTarget, RecipeIngredientRole.OUTPUT, results, seenRecipeIds, seenNoIdRecipes);
            if (Screen.hasShiftDown()) {
                collectRecipesByRole(recipeManager, focusFactory, normalizedTarget, RecipeIngredientRole.INPUT, results, seenRecipeIds, seenNoIdRecipes);
            }
        } catch (Throwable t) {
            LOGGER.debug("[RecipeFinder] JEI recipe query failed: target={}, err={}", describeStack(target), t.toString());
        }

        return results;
    }

    public static Recipe<?> selectBestRecipe(List<Recipe<?>> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            LOGGER.debug("[RecipeFinder] selectBestRecipe: 配方列表为空");
            return null;
        }

        LOGGER.info("[RecipeFinder] ========== 开始选择最佳配方 ==========");
        LOGGER.info("[RecipeFinder] 候选配方数量: {}", recipes.size());
        for (int i = 0; i < recipes.size(); i++) {
            Recipe<?> r = recipes.get(i);
            LOGGER.info("[RecipeFinder]   [{}] {} (类型: {})",
                i, r.getId(), r.getClass().getSimpleName());
        }

        // Priority 1: 玩家收藏的工作方块对应的配方 - 最高优先级
        // 如果玩家收藏了工作方块，说明他想优先使用这个工作方块的配方
        LOGGER.info("[RecipeFinder] 尝试 Priority 1: 检查玩家收藏的工作方块配方...");
        Recipe<?> bookmarkedRecipe = selectRecipeByBookmarkedWorkstation(recipes);
        if (bookmarkedRecipe != null) {
            LOGGER.info("[RecipeFinder] ✓ Priority 1 成功! 选择收藏工作方块配方: {}", bookmarkedRecipe.getId());
            LOGGER.info("[RecipeFinder] ========================================");
            return bookmarkedRecipe;
        }
        LOGGER.info("[RecipeFinder] ✗ Priority 1 未找到匹配的收藏工作方块配方");

        // Priority 2: CraftingRecipe（工作台配方）- 第二优先级
        // 如果没有收藏的工作方块，则优先选择工作台配方
        LOGGER.info("[RecipeFinder] 尝试 Priority 2: 检查工作台配方...");
        for (Recipe<?> recipe : recipes) {
            if (recipe instanceof CraftingRecipe) {
                LOGGER.info("[RecipeFinder] ✓ Priority 2 成功! 选择工作台配方: {}", recipe.getId());
                LOGGER.info("[RecipeFinder] ========================================");
                return recipe;
            }
        }
        LOGGER.info("[RecipeFinder] ✗ Priority 2 未找到工作台配方");

        // Priority 3: 列表中的第一个配方 - 兜底方案
        LOGGER.info("[RecipeFinder] 使用 Priority 3: 选择第一个配方: {}", recipes.get(0).getId());
        LOGGER.info("[RecipeFinder] ========================================");
        return recipes.get(0);
    }

    public static ItemStack extractItemStackFromTypedIngredient(Object typed) {
        if (typed == null) {
            return ItemStack.EMPTY;
        }

        if (typed instanceof ITypedIngredient<?> ingredient) {
            Optional<ItemStack> stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
            if (stack.isPresent()) {
                return stack.get();
            }
        }

        if (typed instanceof ItemStack stack) {
            return stack;
        }

        return ItemStack.EMPTY;
    }

    private static void collectRecipesByRole(
        IRecipeManager recipeManager,
        IFocusFactory focusFactory,
        ItemStack target,
        RecipeIngredientRole role,
        List<Recipe<?>> results,
        Set<ResourceLocation> seenRecipeIds,
        Set<Recipe<?>> seenNoIdRecipes
    ) {
        IFocus<ItemStack> focus = focusFactory.createFocus(role, VanillaTypes.ITEM_STACK, target);
        List<IFocus<?>> focuses = List.of(focus);

        recipeManager.createRecipeCategoryLookup()
            .limitFocus(focuses)
            .get()
            .map(IRecipeCategory::getRecipeType)
            .distinct()
            .forEach(recipeType -> collectRecipesForType(recipeManager, recipeType, focuses, results, seenRecipeIds, seenNoIdRecipes));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collectRecipesForType(
        IRecipeManager recipeManager,
        RecipeType<?> recipeType,
        List<IFocus<?>> focuses,
        List<Recipe<?>> results,
        Set<ResourceLocation> seenRecipeIds,
        Set<Recipe<?>> seenNoIdRecipes
    ) {
        try {
            RecipeType rawType = recipeType;
            recipeManager.createRecipeLookup(rawType)
                .limitFocus(focuses)
                .get()
                .forEach(recipeObject -> addIfVanillaRecipe(recipeObject, results, seenRecipeIds, seenNoIdRecipes));
        } catch (Throwable t) {
            LOGGER.debug(
                "[RecipeFinder] JEI lookup failed: type={}, err={}",
                recipeType == null ? "unknown" : recipeType.getUid(),
                t.toString()
            );
        }
    }

    private static void addIfVanillaRecipe(
        Object recipeObject,
        List<Recipe<?>> results,
        Set<ResourceLocation> seenRecipeIds,
        Set<Recipe<?>> seenNoIdRecipes
    ) {
        if (!(recipeObject instanceof Recipe<?> recipe)) {
            return;
        }

        ResourceLocation recipeId = recipe.getId();
        if (recipeId != null) {
            if (seenRecipeIds.add(recipeId)) {
                results.add(recipe);
            }
            return;
        }

        if (seenNoIdRecipes.add(recipe)) {
            results.add(recipe);
        }
    }

    private static ItemStack normalizeFocusStack(ItemStack stack) {
        ItemStack normalized = stack.copy();
        normalized.setCount(1);
        return normalized;
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return (itemId == null ? "unknown" : itemId.toString()) + "x" + stack.getCount();
    }

    /**
     * 根据玩家收藏的工作方块选择配方
     * 使用配方类别优先级列表，选择优先级最高的配方
     *
     * @param recipes 候选配方列表
     * @return 匹配收藏工作方块的配方，如果没有匹配则返回 null
     */
    private static Recipe<?> selectRecipeByBookmarkedWorkstation(List<Recipe<?>> recipes) {
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
        java.util.Map<net.minecraft.world.item.Item, java.util.Set<ResourceLocation>> workstationMapping =
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
        for (int i = 0; i < bookmarkedItems.size(); i++) {
            ItemStack stack = bookmarkedItems.get(i);
            LOGGER.debug("[RecipeFinder]   书签[{}]: {}", i, describeStack(stack));
        }

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
        Recipe<?> bestRecipe = null;
        int bestPriority = Integer.MAX_VALUE;

        for (int i = 0; i < recipes.size(); i++) {
            Recipe<?> recipe = recipes.get(i);

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
                bestRecipe = recipe;
                LOGGER.info("[RecipeFinder]   ✓ 更新最佳配方: {} (优先级: {})", recipe.getId(), priority);
            }
        }

        if (bestRecipe != null) {
            LOGGER.info("[RecipeFinder] << ✓✓✓ 最终选择: 配方={}, 优先级={}, 类别={}",
                bestRecipe.getId(), bestPriority, priorityCategories.get(bestPriority));
        } else {
            LOGGER.info("[RecipeFinder] << 未找到匹配优先级列表的配方");
        }

        return bestRecipe;
    }

    /**
     * 获取配方对应的 JEI 配方类别 UID
     * 遍历所有 JEI 类别，找到包含该配方的类别
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
