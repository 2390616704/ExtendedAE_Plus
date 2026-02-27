package com.extendedae_plus.util;

import com.extendedae_plus.integration.jei.JeiRuntimeProxy;
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
}
