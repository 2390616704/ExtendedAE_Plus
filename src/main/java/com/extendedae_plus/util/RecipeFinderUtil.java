package com.extendedae_plus.util;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

        try {
            var recipes = level.getRecipeManager().getRecipes();

            for (Recipe<?> recipe : recipes) {
                if (matchesOutput(recipe, target, level)) {
                    results.add(recipe);
                }
            }

            if (Screen.hasShiftDown()) {
                for (Recipe<?> recipe : recipes) {
                    if (!results.contains(recipe) && matchesInput(recipe, target)) {
                        results.add(recipe);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[RecipeFinder] findRecipesByOutputItem failed: target={}, err={}",
                describeStack(target), t.toString());
        }

        return results;
    }

    public static Recipe<?> selectBestRecipe(List<Recipe<?>> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return null;
        }
        for (Recipe<?> recipe : recipes) {
            if (recipe instanceof CraftingRecipe) {
                return recipe;
            }
        }
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
        try {
            Object ingredient = typed.getClass().getMethod("getIngredient").invoke(typed);
            if (ingredient instanceof ItemStack stack) {
                return stack;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object maybe = typed.getClass().getMethod("getItemStack").invoke(typed);
            if (maybe instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof ItemStack stack) {
                return stack;
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static boolean matchesOutput(Recipe<?> recipe, ItemStack target, Level level) {
        try {
            ItemStack result = recipe.getResultItem(level.registryAccess());
            return !result.isEmpty() && ItemStack.isSameItemSameTags(result, target);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesInput(Recipe<?> recipe, ItemStack target) {
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.test(target)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return (itemId == null ? "unknown" : itemId.toString()) + "x" + stack.getCount();
    }
}
