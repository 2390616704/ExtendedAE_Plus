package com.extendedae_plus.integration.jei;

import com.extendedae_plus.mixin.jei.accessor.BookmarkOverlayAccessor;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import mezz.jei.gui.input.MouseUtil;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Pseudo;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 将所有会引用 JEI GUI 内部类（如 BookmarkList、IngredientBookmark、IElement 等）的逻辑
 * 隔离在此桥接类中，避免在未安装 JEI 或 JEI 组件不完整时过早类加载导致的 NoClassDefFoundError。
 *
 * 该类仅会被 {@link JeiRuntimeProxy} 通过反射调用。
 */
@Pseudo
public final class JeiBookmarkBridge {
    private JeiBookmarkBridge() {}

    // 通过 JeiRuntimeProxy 的包内可见方法安全地获取 Runtime
    private static @Nullable IJeiRuntime getRuntime() {
        try {
            Class<?> proxy = Class.forName("com.extendedae_plus.integration.jei.JeiRuntimeProxy");
            var m = proxy.getDeclaredMethod("get");
            Object rt = m.invoke(null);
            return (IJeiRuntime) rt;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static List<? extends ITypedIngredient<?>> getBookmarkList() {
        IJeiRuntime rt = getRuntime();
        if (rt == null) return Collections.emptyList();
        IBookmarkOverlay bookmarkOverlay = rt.getBookmarkOverlay();
        if (bookmarkOverlay instanceof BookmarkOverlayAccessor accessor) {
            BookmarkList bookmarkList = accessor.eap$getBookmarkList();
            return bookmarkList.getElements().stream().map(IElement::getTypedIngredient).toList();
        }
        return Collections.emptyList();
    }

    public static @Nullable JeiRecipeBookmarkContext getRecipeBookmarkContextUnderMouse() {
        IJeiRuntime rt = getRuntime();
        if (rt == null) return null;

        IBookmarkOverlay overlay = rt.getBookmarkOverlay();
        if (!(overlay instanceof BookmarkOverlay bookmarkOverlay)) {
            return null;
        }

        // BookmarkOverlay expects GUI-scaled coordinates, not raw window pixels.
        double mouseX = MouseUtil.getX();
        double mouseY = MouseUtil.getY();
        var hovered = bookmarkOverlay.getIngredientUnderMouse(mouseX, mouseY).findFirst().orElse(null);
        if (hovered == null) {
            return null;
        }

        var bookmark = hovered.getElement().getBookmark().orElse(null);
        if (!(bookmark instanceof RecipeBookmark<?, ?> recipeBookmark)) {
            return null;
        }

        var recipeType = recipeBookmark.getRecipeCategory().getRecipeType();
        var recipeId = getRecipeId(recipeBookmark);
        if (recipeType == null || recipeType.getUid() == null) {
            return null;
        }

        ItemStack outputPreview = extractItemStackFromTypedIngredient(hovered.getTypedIngredient());
        if (outputPreview.isEmpty()) {
            Object recipe = recipeBookmark.getRecipe();
            if (recipe instanceof net.minecraft.world.item.crafting.Recipe<?> r) {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.level != null) {
                        outputPreview = r.getResultItem(mc.level.registryAccess()).copy();
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        List<ItemStack> recipeInputs = extractRecipeInputs(rt, recipeBookmark);
        return new JeiRecipeBookmarkContext(recipeId, recipeType.getUid(), outputPreview, recipeInputs);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable net.minecraft.resources.ResourceLocation getRecipeId(RecipeBookmark<?, ?> recipeBookmark) {
        try {
            var category = (mezz.jei.api.recipe.category.IRecipeCategory) recipeBookmark.getRecipeCategory();
            return (net.minecraft.resources.ResourceLocation) category.getRegistryName(recipeBookmark.getRecipe());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<ItemStack> extractRecipeInputs(IJeiRuntime runtime, RecipeBookmark<?, ?> recipeBookmark) {
        List<ItemStack> inputs = new ArrayList<>();
        if (runtime == null || recipeBookmark == null) {
            return inputs;
        }
        try {
            Object recipeManager = runtime.getRecipeManager();
            Object recipeCategory = recipeBookmark.getRecipeCategory();
            Object recipe = recipeBookmark.getRecipe();
            Object supplier = invokeMethod2(recipeManager, "getRecipeIngredients", recipeCategory, recipe);
            if (supplier == null) {
                return inputs;
            }

            Object roleInput = Class.forName("mezz.jei.api.recipe.RecipeIngredientRole")
                .getField("INPUT")
                .get(null);
            Object ingredientList = invokeMethod1(supplier, "getIngredients", roleInput);
            if (!(ingredientList instanceof List<?> list)) {
                return inputs;
            }

            for (Object typed : list) {
                ItemStack stack = extractItemStackFromTypedIngredient(typed);
                if (!stack.isEmpty()) {
                    inputs.add(stack.copy());
                }
            }
        } catch (Throwable ignored) {
        }
        return inputs;
    }

    private static Object invokeMethod1(Object target, String methodName, Object arg) {
        if (target == null || arg == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!param.isInstance(arg) && !param.isAssignableFrom(arg.getClass())) {
                continue;
            }
            try {
                return method.invoke(target, arg);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeMethod2(Object target, String methodName, Object arg1, Object arg2) {
        if (target == null || arg1 == null || arg2 == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if ((!params[0].isInstance(arg1) && !params[0].isAssignableFrom(arg1.getClass()))
                || (!params[1].isInstance(arg2) && !params[1].isAssignableFrom(arg2.getClass()))) {
                continue;
            }
            try {
                return method.invoke(target, arg1, arg2);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static ItemStack extractItemStackFromTypedIngredient(Object typed) {
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
        return ItemStack.EMPTY;
    }

    public static void addBookmark(ItemStack stack) {
        IJeiRuntime rt = getRuntime();
        if (rt == null) return;

        IBookmarkOverlay overlay = rt.getBookmarkOverlay();
        if (overlay instanceof BookmarkOverlayAccessor accessor) {
            BookmarkList list = accessor.eap$getBookmarkList();
            Optional<ITypedIngredient<ItemStack>> typedOpt = rt.getIngredientManager()
                .createTypedIngredient(VanillaTypes.ITEM_STACK, stack);
            typedOpt.ifPresent(typed -> {
                IngredientBookmark<ItemStack> bookmark = IngredientBookmark.create(typed, rt.getIngredientManager());
                list.add(bookmark); // add 内部会自动保存到配置
            });
        }
    }

    public static void addBookmark(FluidStack fluidStack) {
        IJeiRuntime rt = getRuntime();
        if (rt == null) return;

        IBookmarkOverlay overlay = rt.getBookmarkOverlay();
        if (overlay instanceof BookmarkOverlayAccessor accessor) {
            BookmarkList list = accessor.eap$getBookmarkList();
            Optional<ITypedIngredient<FluidStack>> typedOpt = rt.getIngredientManager()
                .createTypedIngredient(ForgeTypes.FLUID_STACK, fluidStack);
            typedOpt.ifPresent(typed -> {
                IngredientBookmark<FluidStack> bookmark = IngredientBookmark.create(typed, rt.getIngredientManager());
                list.add(bookmark); // add 内部会自动保存到配置
            });
        }
    }

    /**
     * 如果存在 Mekanism/appmek，则将 Mekanism 化学堆栈添加到 JEI 书签。
     */
    public static void addBookmark(Object chemicalStack) {
        if (!ModList.get().isLoaded("mekanism") && !ModList.get().isLoaded("appmek")) return;

        IJeiRuntime rt = getRuntime();
        if (rt == null) return;

        IBookmarkOverlay overlay = rt.getBookmarkOverlay();
        if (overlay instanceof BookmarkOverlayAccessor accessor) {
            BookmarkList list = accessor.eap$getBookmarkList();
            try {
                if (chemicalStack == null) return;

                // Determine Mekanism JEI ingredient type constant by runtime class name
                String clsName = chemicalStack.getClass().getName();
                String mekanismJeiClass = "mekanism.client.jei.MekanismJEI";
                Class<?> jeiCls = Class.forName(mekanismJeiClass);
                Field typeField = getField(clsName, jeiCls);

                if (typeField == null) return;
                Object typeConst = typeField.get(null);

                // Use ingredient manager reflectively to create a typed ingredient
                Object ingredientManager = rt.getIngredientManager();
                Method createTypedIngredient = ingredientManager.getClass().getMethod("createTypedIngredient", IIngredientType.class, Object.class);
                Object opt = createTypedIngredient.invoke(ingredientManager, typeConst, chemicalStack);
                if (!(opt instanceof Optional<?> typedOpt)) return;
                if (typedOpt.isPresent()) {
                    Object typed = typedOpt.get();
                    // Find a compatible static create(...) method on IngredientBookmark where
                    // the second parameter is assignable from the actual ingredientManager instance.
                    Method createMethod = null;
                    for (Method m : IngredientBookmark.class.getMethods()) {
                        if (!m.getName().equals("create")) continue;
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length != 2) continue;
                        // first param should accept the typed ingredient
                        boolean firstOk = params[0].isAssignableFrom(typed.getClass()) || params[0].isAssignableFrom(ITypedIngredient.class);
                        boolean secondOk = params[1].isAssignableFrom(ingredientManager.getClass());
                        if (firstOk && secondOk) {
                            createMethod = m;
                            break;
                        }
                    }
                    if (createMethod != null) {
                        Object bookmark = createMethod.invoke(null, typed, ingredientManager);
                        if (bookmark != null) {
                            list.add((IngredientBookmark) bookmark);
                        }
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private static @Nullable Field getField(String clsName, Class<?> jeiCls) throws NoSuchFieldException {
        Field typeField = null;
        if ("mekanism.api.chemical.gas.GasStack".equals(clsName)) {
            typeField = jeiCls.getField("TYPE_GAS");
        } else if ("mekanism.api.chemical.infuse.InfusionStack".equals(clsName)) {
            typeField = jeiCls.getField("TYPE_INFUSION");
        } else if ("mekanism.api.chemical.pigment.PigmentStack".equals(clsName)) {
            typeField = jeiCls.getField("TYPE_PIGMENT");
        } else if ("mekanism.api.chemical.slurry.SlurryStack".equals(clsName)) {
            typeField = jeiCls.getField("TYPE_SLURRY");
        }
        return typeField;
    }

    /**
     * 获取鼠标下的配方书签（如果存在）
     * 
     * @return 配方书签对象（RecipeBookmark<?, ?>），如果不是配方书签则返回空
     */
    public static Optional<?> getRecipeBookmarkUnderMouse() {
        IJeiRuntime rt = getRuntime();
        if (rt == null) return Optional.empty();
        
        IBookmarkOverlay bookmarkOverlay = rt.getBookmarkOverlay();
        if (!(bookmarkOverlay instanceof BookmarkOverlayAccessor accessor)) {
            return Optional.empty();
        }

        // 优先路径：直接从鼠标下 clickable 元素提取 bookmark（避免 typedIngredient equals 误判）
        try {
            var overlayObj = (Object) bookmarkOverlay;
            var streamObj = overlayObj.getClass()
                .getMethod("getIngredientUnderMouse", double.class, double.class)
                .invoke(overlayObj, MouseUtil.getX(), MouseUtil.getY());
            if (streamObj instanceof java.util.stream.Stream<?> stream) {
                Object clickable = stream.findFirst().orElse(null);
                if (clickable != null) {
                    Object element = clickable.getClass().getMethod("getElement").invoke(clickable);
                    if (element != null) {
                        Object bookmarkOpt = element.getClass().getMethod("getBookmark").invoke(element);
                        if (bookmarkOpt instanceof Optional<?> b && b.isPresent()) {
                            Object bookmark = b.get();
                            if (bookmark != null && "RecipeBookmark".equals(bookmark.getClass().getSimpleName())) {
                                return Optional.of(bookmark);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        
        // 获取鼠标下的元素
        Optional<ITypedIngredient<?>> ingredientOpt = bookmarkOverlay.getIngredientUnderMouse();
        if (ingredientOpt.isEmpty()) {
            return Optional.empty();
        }
        
        // 遍历书签列表，查找匹配的配方书签
        BookmarkList bookmarkList = accessor.eap$getBookmarkList();
        for (IElement<?> element : bookmarkList.getElements()) {
            // 检查元素的 TypedIngredient 是否匹配
            if (element.getTypedIngredient().equals(ingredientOpt.get())) {
                // 检查是否有关联的书签
                Optional<?> bookmarkOpt = element.getBookmark();
                if (bookmarkOpt.isPresent()) {
                    Object bookmark = bookmarkOpt.get();
                    // 判断是否为 RecipeBookmark（而非 IngredientBookmark）
                    if (bookmark.getClass().getSimpleName().equals("RecipeBookmark")) {
                        return Optional.of(bookmark);
                    }
                }
            }
        }
        
        return Optional.empty();
    }

    /**
     * 从 JEI 书签移除物品
     */
    public static void removeBookmark(ItemStack stack) {
        IJeiRuntime rt = getRuntime();
        if (rt == null) return;

        IBookmarkOverlay overlay = rt.getBookmarkOverlay();
        if (overlay instanceof BookmarkOverlayAccessor accessor) {
            BookmarkList list = accessor.eap$getBookmarkList();
            Optional<ITypedIngredient<ItemStack>> typedOpt = rt.getIngredientManager()
                .createTypedIngredient(VanillaTypes.ITEM_STACK, stack);
            typedOpt.ifPresent(typed -> {
                IngredientBookmark<ItemStack> bookmark = IngredientBookmark.create(typed, rt.getIngredientManager());
                list.remove(bookmark);
            });
        }
    }
}
