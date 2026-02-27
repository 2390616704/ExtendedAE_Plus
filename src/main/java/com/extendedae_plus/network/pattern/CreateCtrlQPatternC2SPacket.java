package com.extendedae_plus.network.pattern;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.core.definitions.AEItems;
import appeng.items.tools.powered.WirelessCraftingTerminalItem;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.me.helpers.PlayerSource;
import com.extendedae_plus.util.uploadPattern.MatrixUploadUtil;
import com.extendedae_plus.util.uploadPattern.ProviderUploadUtil;
import com.extendedae_plus.util.wireless.WirelessTerminalLocator;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import de.mari_023.ae2wtlib.wut.WTDefinition;
import de.mari_023.ae2wtlib.wut.WUTHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.network.NetworkEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * C2S: Ctrl+Q 快速创建样板。
 */
public class CreateCtrlQPatternC2SPacket {
    private static final String LAST_CTRLQ_HASH_KEY = "eap_ctrlq_last_request_hash";
    private static final String LAST_CTRLQ_TICK_KEY = "eap_ctrlq_last_request_tick";

    private final ResourceLocation recipeId;
    private final boolean isCraftingPattern;
    private final List<ItemStack> selectedIngredients;
    private final boolean fromRecipeTypeBookmark;

    public CreateCtrlQPatternC2SPacket(ResourceLocation recipeId, boolean isCraftingPattern, List<ItemStack> selectedIngredients) {
        this(recipeId, isCraftingPattern, selectedIngredients, false);
    }

    public CreateCtrlQPatternC2SPacket(ResourceLocation recipeId, boolean isCraftingPattern, List<ItemStack> selectedIngredients, boolean fromRecipeTypeBookmark) {
        this.recipeId = recipeId;
        this.isCraftingPattern = isCraftingPattern;
        this.selectedIngredients = selectedIngredients;
        this.fromRecipeTypeBookmark = fromRecipeTypeBookmark;
    }

    public static void encode(CreateCtrlQPatternC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.recipeId);
        buf.writeBoolean(msg.isCraftingPattern);
        buf.writeInt(msg.selectedIngredients.size());
        for (ItemStack stack : msg.selectedIngredients) {
            buf.writeItem(stack);
        }
        buf.writeBoolean(msg.fromRecipeTypeBookmark);
    }

    public static CreateCtrlQPatternC2SPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        boolean isCraftingPattern = buf.readBoolean();
        int count = buf.readInt();
        List<ItemStack> ingredients = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ingredients.add(buf.readItem());
        }
        boolean fromRecipeTypeBookmark = buf.readBoolean();
        return new CreateCtrlQPatternC2SPacket(recipeId, isCraftingPattern, ingredients, fromRecipeTypeBookmark);
    }

    public static void handle(CreateCtrlQPatternC2SPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }

            RecipeManager recipeManager = player.level().getRecipeManager();
            Recipe<?> recipe = findRecipeById(recipeManager, msg.recipeId);
            if (recipe == null) {
                player.displayClientMessage(Component.translatable("message.extendedae_plus.recipe_not_found"), false);
                return;
            }

            if (isDuplicateRequest(player, msg)) {
                return;
            }

            if (!consumeBlankPattern(player)) {
                player.displayClientMessage(Component.translatable("message.extendedae_plus.no_blank_pattern"), false);
                return;
            }

            ItemStack pattern = createPattern(recipe, msg.isCraftingPattern, msg.selectedIngredients, player);
            if (pattern.isEmpty()) {
                player.getInventory().add(AEItems.BLANK_PATTERN.stack());
                player.displayClientMessage(Component.translatable("message.extendedae_plus.pattern_creation_failed"), false);
                return;
            }

            if (msg.fromRecipeTypeBookmark) {
                handleRecipeBookmarkFlow(player, recipe, pattern);
                return;
            }

            if (!player.getInventory().add(pattern)) {
                player.drop(pattern, false);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRecipeBookmarkFlow(ServerPlayer player, Recipe<?> recipe, ItemStack pattern) {
        if (isMatrixRecipeType(recipe)) {
            MatrixUploadUtil.MatrixUploadStatus status = MatrixUploadUtil.uploadPatternToMatrixFromPlayerNetwork(player, pattern.copy());
            if (status == MatrixUploadUtil.MatrixUploadStatus.SUCCESS) {
                return;
            }

            if (status == MatrixUploadUtil.MatrixUploadStatus.NO_MATRIX
                || status == MatrixUploadUtil.MatrixUploadStatus.NO_NETWORK) {
                player.displayClientMessage(Component.translatable("extendedae_plus.upload_to_matrix.fail_no_matrix"), false);
            } else if (status == MatrixUploadUtil.MatrixUploadStatus.DUPLICATE) {
                player.displayClientMessage(Component.translatable("extendedae_plus.upload_to_matrix.repetition"), false);
            } else {
                player.displayClientMessage(Component.translatable("extendedae_plus.upload_to_matrix.fail_full"), false);
            }
            return;
        }

        String pendingId = ProviderUploadUtil.beginPendingCtrlQUpload(player, pattern);
        if (pendingId == null) {
            player.displayClientMessage(Component.translatable("message.extendedae_plus.pattern_creation_failed"), false);
            return;
        }

        if (!ProviderUploadUtil.openProviderSelectionForPlayerNetwork(player)) {
            ProviderUploadUtil.clearPendingCtrlQUpload(player);
            player.displayClientMessage(Component.translatable("message.extendedae_plus.no_provider_found"), false);
        }
    }

    private static boolean isMatrixRecipeType(Recipe<?> recipe) {
        return recipe instanceof CraftingRecipe
            || recipe instanceof StonecutterRecipe
            || recipe instanceof SmithingRecipe;
    }

    private static boolean isDuplicateRequest(ServerPlayer player, CreateCtrlQPatternC2SPacket msg) {
        long now = player.level().getGameTime();
        int hash = computeRequestHash(msg);

        var data = player.getPersistentData();
        long lastTick = data.getLong(LAST_CTRLQ_TICK_KEY);
        int lastHash = data.getInt(LAST_CTRLQ_HASH_KEY);

        data.putLong(LAST_CTRLQ_TICK_KEY, now);
        data.putInt(LAST_CTRLQ_HASH_KEY, hash);

        return hash == lastHash && now - lastTick <= 1;
    }

    private static int computeRequestHash(CreateCtrlQPatternC2SPacket msg) {
        int hash = 17;
        hash = 31 * hash + msg.recipeId.hashCode();
        hash = 31 * hash + (msg.isCraftingPattern ? 1 : 0);
        hash = 31 * hash + msg.selectedIngredients.size();
        for (ItemStack stack : msg.selectedIngredients) {
            hash = 31 * hash + computeStackHash(stack);
        }
        // Intentionally ignore fromRecipeTypeBookmark in dedupe to collapse mixed duplicate paths.
        return hash;
    }

    private static int computeStackHash(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        int hash = 17;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        hash = 31 * hash + (itemId == null ? 0 : itemId.hashCode());
        hash = 31 * hash + stack.getCount();
        hash = 31 * hash + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        return hash;
    }

    private static boolean consumeBlankPattern(ServerPlayer player) {
        if (tryExtractFromNetwork(player)) {
            return true;
        }

        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(AEItems.BLANK_PATTERN.asItem())) {
                stack.shrink(1);
                return true;
            }
        }

        return false;
    }

    private static boolean tryExtractFromNetwork(ServerPlayer player) {
        WirelessTerminalLocator.LocatedTerminal located = WirelessTerminalLocator.find(player);
        ItemStack terminal = located.stack;
        if (terminal.isEmpty()) {
            return false;
        }

        IGrid grid;
        boolean usedWtHost;

        String curiosSlotId = located.getCuriosSlotId();
        int curiosIndex = located.getCuriosIndex();

        if (curiosSlotId != null && curiosIndex >= 0) {
            try {
                String current = WUTHandler.getCurrentTerminal(terminal);
                WTDefinition def = WUTHandler.wirelessTerminals.get(current);
                if (def == null) {
                    return false;
                }
                WTMenuHost wtHost = def.wTMenuHostFactory().create(player, null, terminal, (p, sub) -> {
                });
                if (wtHost == null) {
                    return false;
                }
                var node = wtHost.getActionableNode();
                if (node == null) {
                    return false;
                }
                grid = node.getGrid();
                if (grid == null || !wtHost.drainPower()) {
                    return false;
                }
                usedWtHost = true;
            } catch (Exception e) {
                return false;
            }
        } else {
            WirelessCraftingTerminalItem wct = terminal.getItem() instanceof WirelessCraftingTerminalItem c ? c : null;
            WirelessTerminalItem wt = wct != null ? wct : (terminal.getItem() instanceof WirelessTerminalItem t ? t : null);
            if (wt == null) {
                return false;
            }
            grid = wt.getLinkedGrid(terminal, player.serverLevel(), player);
            if (grid == null || !wt.hasPower(player, 0.5, terminal)) {
                return false;
            }
            usedWtHost = false;
        }

        AEItemKey blankPatternKey = AEItemKey.of(AEItems.BLANK_PATTERN.stack());
        IEnergyService energy = grid.getEnergyService();
        MEStorage storage = grid.getStorageService().getInventory();
        long extracted = StorageHelper.poweredExtraction(energy, storage, blankPatternKey, 1, new PlayerSource(player));

        if (extracted <= 0) {
            return false;
        }

        if (!usedWtHost) {
            WirelessCraftingTerminalItem wct2 = terminal.getItem() instanceof WirelessCraftingTerminalItem c2 ? c2 : null;
            WirelessTerminalItem wt2 = wct2 != null ? wct2 : (terminal.getItem() instanceof WirelessTerminalItem t2 ? t2 : null);
            if (wt2 != null) {
                wt2.usePower(player, 0.5, terminal);
            }
        }
        located.commit();
        return true;
    }

    private static ItemStack createPattern(Recipe<?> recipe, boolean isCrafting, List<ItemStack> selectedIngredients, ServerPlayer player) {
        try {
            List<ItemStack> encodeInputs = ensureEncodingInputs(recipe, selectedIngredients);
            if (encodeInputs.isEmpty()) {
                return ItemStack.EMPTY;
            }

            if (isCrafting && recipe instanceof CraftingRecipe) {
                ItemStack[] inputs = new ItemStack[9];
                for (int i = 0; i < 9; i++) {
                    inputs[i] = i < encodeInputs.size() ? encodeInputs.get(i).copy() : ItemStack.EMPTY;
                }
                ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
                ItemStack encoded = invokePatternEncode(
                    "encodeCraftingPattern",
                    recipe,
                    inputs,
                    output,
                    true,
                    false
                );
                if (!encoded.isEmpty()) {
                    encoded.getOrCreateTag().putString("encodePlayer", player.getName().getString());
                }
                return encoded;
            }

            if (recipe instanceof StonecutterRecipe) {
                ItemStack input = encodeInputs.isEmpty() ? ItemStack.EMPTY : encodeInputs.get(0);
                ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
                if (input.isEmpty() || output.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                ItemStack encoded = invokePatternEncode(
                    "encodeStonecuttingPattern",
                    recipe,
                    AEItemKey.of(input),
                    AEItemKey.of(output),
                    true
                );
                if (!encoded.isEmpty()) {
                    encoded.getOrCreateTag().putString("encodePlayer", player.getName().getString());
                }
                return encoded;
            }

            if (recipe instanceof SmithingRecipe) {
                ItemStack template = encodeInputs.size() > 0 ? encodeInputs.get(0) : ItemStack.EMPTY;
                ItemStack base = encodeInputs.size() > 1 ? encodeInputs.get(1) : ItemStack.EMPTY;
                ItemStack addition = encodeInputs.size() > 2 ? encodeInputs.get(2) : ItemStack.EMPTY;
                ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
                if (template.isEmpty() || base.isEmpty() || addition.isEmpty() || output.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                ItemStack encoded = invokePatternEncode(
                    "encodeSmithingTablePattern",
                    recipe,
                    AEItemKey.of(template),
                    AEItemKey.of(base),
                    AEItemKey.of(addition),
                    AEItemKey.of(output),
                    true
                );
                if (!encoded.isEmpty()) {
                    encoded.getOrCreateTag().putString("encodePlayer", player.getName().getString());
                }
                return encoded;
            }

            List<GenericStack> inputs = new ArrayList<>();
            List<GenericStack> outputs = new ArrayList<>();
            for (ItemStack item : encodeInputs) {
                if (!item.isEmpty()) {
                    inputs.add(new GenericStack(AEItemKey.of(item), item.getCount()));
                }
            }

            ItemStack result = recipe.getResultItem(player.level().registryAccess());
            if (!result.isEmpty()) {
                outputs.add(new GenericStack(AEItemKey.of(result), result.getCount()));
            }

            ItemStack encodedPattern = PatternDetailsHelper.encodeProcessingPattern(
                inputs.toArray(new GenericStack[0]),
                outputs.toArray(new GenericStack[0])
            );
            encodedPattern.getOrCreateTag().putString("encodePlayer", player.getName().getString());
            return encodedPattern;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static List<ItemStack> ensureEncodingInputs(Recipe<?> recipe, List<ItemStack> selectedIngredients) {
        List<ItemStack> normalized = new ArrayList<>();
        if (selectedIngredients != null) {
            for (ItemStack stack : selectedIngredients) {
                if (stack != null && !stack.isEmpty()) {
                    normalized.add(stack.copy());
                }
            }
        }
        List<ItemStack> reflected = reflectInputsFromRecipe(recipe);
        if (!normalized.isEmpty()) {
            List<ItemStack> adjusted = applySelectedCountsBySlot(normalized, reflected);
            return applySelectedCounts(adjusted, reflected);
        }
        List<ItemStack> vanilla = extractVanillaIngredientInputs(recipe);
        if (!vanilla.isEmpty()) {
            return vanilla;
        }
        return reflected;
    }

    private static List<ItemStack> extractVanillaIngredientInputs(Recipe<?> recipe) {
        List<ItemStack> out = new ArrayList<>();
        if (recipe == null) {
            return out;
        }
        try {
            for (net.minecraft.world.item.crafting.Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                ItemStack[] items = ingredient.getItems();
                if (items.length > 0 && !items[0].isEmpty()) {
                    out.add(items[0].copy());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<ItemStack> reflectInputsFromRecipe(Recipe<?> recipe) {
        List<ItemStack> out = new ArrayList<>();
        if (recipe == null) {
            return out;
        }

        List<Object> candidates = new ArrayList<>();
        String[] knownInputAccessors = {
            "getItemInput",
            "getMainInput",
            "getBaseInput",
            "getTemplateInput",
            "getAdditionInput",
            "getTemplate",
            "getAddition",
            "getLeftInput",
            "getRightInput",
            "getInput",
            "getInputs",
            "getItemInputs"
        };
        for (String accessor : knownInputAccessors) {
            Object value = invokeNoArg(recipe, accessor);
            if (value != null) {
                candidates.add(value);
            }
        }
        if (!candidates.isEmpty()) {
            Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object candidate : candidates) {
                collectItemStacks(candidate, out, visited, 0);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }

        for (Method method : recipe.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            String methodName = method.getName().toLowerCase(Locale.ROOT);
            if (!isLikelyInputMethod(methodName)) {
                continue;
            }
            try {
                Object value = method.invoke(recipe);
                if (value != null) {
                    candidates.add(value);
                }
            } catch (Throwable ignored) {
            }
        }

        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object candidate : candidates) {
            collectItemStacks(candidate, out, visited, 0);
        }
        return out;
    }

    private static void collectItemStacks(Object source, List<ItemStack> out, Set<Object> visited, int depth) {
        if (source == null || depth > 6) {
            return;
        }
        if (source instanceof ItemStack stack) {
            if (!stack.isEmpty()) {
                addUniqueStack(out, stack);
            }
            return;
        }
        if (source instanceof net.minecraft.world.item.crafting.Ingredient ingredient) {
            ItemStack[] items = ingredient.getItems();
            if (items.length > 0 && !items[0].isEmpty()) {
                addUniqueStack(out, items[0]);
            }
            return;
        }
        if (source instanceof Optional<?> optional) {
            optional.ifPresent(value -> collectItemStacks(value, out, visited, depth + 1));
            return;
        }
        ItemStack countedStack = extractStackWithAmount(source);
        if (!countedStack.isEmpty()) {
            addUniqueStack(out, countedStack);
            return;
        }
        if (source instanceof Collection<?> collection) {
            boolean allItemStacks = true;
            for (Object value : collection) {
                if (value != null && !(value instanceof ItemStack)) {
                    allItemStacks = false;
                    break;
                }
            }
            if (allItemStacks) {
                for (Object value : collection) {
                    if (value instanceof ItemStack stack && !stack.isEmpty()) {
                        addUniqueStack(out, stack);
                        return;
                    }
                }
            }
            for (Object value : collection) {
                collectItemStacks(value, out, visited, depth + 1);
            }
            return;
        }
        if (source.getClass().isArray()) {
            int len = Array.getLength(source);
            boolean allItemStacks = true;
            for (int i = 0; i < len; i++) {
                Object value = Array.get(source, i);
                if (value != null && !(value instanceof ItemStack)) {
                    allItemStacks = false;
                    break;
                }
            }
            if (allItemStacks) {
                for (int i = 0; i < len; i++) {
                    Object value = Array.get(source, i);
                    if (value instanceof ItemStack stack && !stack.isEmpty()) {
                        addUniqueStack(out, stack);
                        return;
                    }
                }
            }
            for (int i = 0; i < len; i++) {
                collectItemStacks(Array.get(source, i), out, visited, depth + 1);
            }
            return;
        }

        if (!visited.add(source)) {
            return;
        }

        String[] preferredMethods = {
            "getRepresentations",
            "getMatchingStacks",
            "getItems",
            "getIngredient",
            "getInput",
            "getInputs",
            "getItemInput",
            "getItemInputs",
            "getItemStack"
        };
        for (String methodName : preferredMethods) {
            Object value = invokeNoArg(source, methodName);
            if (value != null) {
                collectItemStacks(value, out, visited, depth + 1);
            }
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            if (method.getParameterCount() == 0) {
                return method.invoke(target);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isLikelyInputMethod(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return false;
        }
        if (!(methodName.contains("input") || methodName.contains("ingredient"))) {
            return false;
        }
        return !(methodName.contains("output")
            || methodName.contains("result")
            || methodName.contains("product")
            || methodName.contains("chemical")
            || methodName.contains("fluid")
            || methodName.contains("gas")
            || methodName.contains("display")
            || methodName.equals("getresultitem"));
    }

    private static void addUniqueStack(List<ItemStack> out, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (ItemStack existing : out) {
            if (ItemStack.isSameItemSameTags(existing, stack)) {
                if (stack.getCount() > existing.getCount()) {
                    existing.setCount(stack.getCount());
                }
                return;
            }
        }
        out.add(stack.copy());
    }

    private static List<ItemStack> applySelectedCountsBySlot(List<ItemStack> selected, List<ItemStack> candidate) {
        if (selected == null || selected.isEmpty()) {
            return selected == null ? new ArrayList<>() : selected;
        }
        List<ItemStack> adjusted = new ArrayList<>(selected.size());
        for (ItemStack stack : selected) {
            adjusted.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        if (candidate == null || candidate.isEmpty() || candidate.size() != adjusted.size()) {
            return adjusted;
        }
        for (int i = 0; i < adjusted.size(); i++) {
            ItemStack out = adjusted.get(i);
            ItemStack source = candidate.get(i);
            if (out.isEmpty() || source == null || source.isEmpty()) {
                continue;
            }
            if (source.getCount() > out.getCount()) {
                out.setCount(source.getCount());
            }
        }
        return adjusted;
    }

    private static List<ItemStack> applySelectedCounts(List<ItemStack> selected, List<ItemStack> candidate) {
        if (selected == null || selected.isEmpty()) {
            return selected == null ? new ArrayList<>() : selected;
        }
        if (candidate == null || candidate.isEmpty()) {
            return selected;
        }
        List<ItemStack> adjusted = new ArrayList<>(selected.size());
        for (ItemStack base : selected) {
            if (base == null || base.isEmpty()) {
                continue;
            }
            ItemStack out = base.copy();
            int maxCount = out.getCount();
            for (ItemStack source : candidate) {
                if (source == null || source.isEmpty()) {
                    continue;
                }
                if (ItemStack.isSameItemSameTags(out, source) && source.getCount() > maxCount) {
                    maxCount = source.getCount();
                }
            }
            out.setCount(maxCount);
            adjusted.add(out);
        }
        return adjusted;
    }

    private static ItemStack extractStackWithAmount(Object source) {
        if (source == null) {
            return ItemStack.EMPTY;
        }
        if (source instanceof Collection<?> || source.getClass().isArray()) {
            return ItemStack.EMPTY;
        }
        int amount = extractInputAmount(source);
        if (amount <= 1) {
            return ItemStack.EMPTY;
        }
        String[] ingredientAccessors = {
            "getIngredient",
            "getInput",
            "getItemInput",
            "getRepresentations",
            "getMatchingStacks",
            "getItems",
            "getItemStack"
        };
        for (String accessor : ingredientAccessors) {
            Object value = invokeNoArg(source, accessor);
            ItemStack stack = extractFirstStack(value);
            if (!stack.isEmpty()) {
                ItemStack withAmount = stack.copy();
                withAmount.setCount(Math.max(withAmount.getCount(), amount));
                return withAmount;
            }
        }
        return ItemStack.EMPTY;
    }

    private static int extractInputAmount(Object source) {
        if (source == null) {
            return 1;
        }
        String[] amountMethods = {
            "getAmount",
            "getInputAmount",
            "getNeededAmount",
            "getRequiredAmount",
            "amount",
            "count",
            "getCount"
        };
        for (String methodName : amountMethods) {
            Object value = invokeNoArg(source, methodName);
            if (value instanceof Number n) {
                int amount = n.intValue();
                if (amount > 0 && amount < 100000) {
                    return amount;
                }
            }
        }
        return 1;
    }

    private static ItemStack extractFirstStack(Object value) {
        if (value == null) {
            return ItemStack.EMPTY;
        }
        if (value instanceof ItemStack stack) {
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        }
        if (value instanceof net.minecraft.world.item.crafting.Ingredient ingredient) {
            ItemStack[] items = ingredient.getItems();
            return items.length == 0 ? ItemStack.EMPTY : items[0];
        }
        if (value instanceof Optional<?> optional) {
            return extractFirstStack(optional.orElse(null));
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                ItemStack stack = extractFirstStack(element);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
            return ItemStack.EMPTY;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                ItemStack stack = extractFirstStack(Array.get(value, i));
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack invokePatternEncode(String methodName, Recipe<?> recipe, Object... args) {
        try {
            for (Method method : PatternDetailsHelper.class.getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != args.length + 1) {
                    continue;
                }
                Object recipeArg = adaptRecipeArgument(parameterTypes[0], recipe);
                if (recipeArg == null) {
                    continue;
                }

                Object[] invokeArgs = new Object[parameterTypes.length];
                invokeArgs[0] = recipeArg;
                System.arraycopy(args, 0, invokeArgs, 1, args.length);

                Object result = method.invoke(null, invokeArgs);
                if (result instanceof ItemStack stack) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static Object adaptRecipeArgument(Class<?> expectedType, Recipe<?> recipe) {
        if (expectedType.isInstance(recipe)) {
            return recipe;
        }
        if (!"net.minecraft.world.item.crafting.RecipeHolder".equals(expectedType.getName())) {
            return null;
        }

        try {
            for (Constructor<?> ctor : expectedType.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 2) {
                    continue;
                }
                if (ResourceLocation.class.isAssignableFrom(params[0]) && params[1].isInstance(recipe)) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(recipe.getId(), recipe);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Recipe<?> findRecipeById(RecipeManager recipeManager, ResourceLocation recipeId) {
        if (recipeManager == null || recipeId == null) {
            return null;
        }
        try {
            var recipeOpt = recipeManager.byKey(recipeId);
            if (recipeOpt.isPresent()) {
                return recipeOpt.get();
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Recipe<?> recipe : recipeManager.getRecipes()) {
                if (recipeId.equals(recipe.getId())) {
                    return recipe;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
