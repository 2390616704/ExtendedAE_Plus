package com.extendedae_plus.util;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.extendedae_plus.integration.jei.JeiRuntimeProxy;
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
     * 选择最佳配方(优先选择工作台配方)
     *
     * @param recipes 配方信息列表
     * @return 最佳配方信息,如果列表为空返回null
     */
    public static RecipeInfo selectBestRecipe(List<RecipeInfo> recipes) {
        if (recipes.isEmpty()) {
            return null;
        }

        // 优先返回工作台配方
        for (RecipeInfo info : recipes) {
            if (info.isCraftingRecipe()) {
                return info;
            }
        }

        // 如果没有工作台配方,返回第一个
        return recipes.get(0);
    }
}
