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
import com.extendedae_plus.util.uploadPattern.ProviderUploadUtil;
import com.extendedae_plus.util.uploadPattern.RecipeTypeNameConfig;
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
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: Ctrl+Q quick-create pattern request.
 */
public class CreateCtrlQPatternC2SPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("ExtendedAE Plus - CtrlQ");

    private final ResourceLocation recipeId;
    private final boolean isCraftingPattern;
    private final List<ItemStack> selectedIngredients;
    private final List<ItemStack> outputs;
    private final boolean openProviderSelector;

    public CreateCtrlQPatternC2SPacket(ResourceLocation recipeId, boolean isCraftingPattern, List<ItemStack> selectedIngredients, List<ItemStack> outputs) {
        this(recipeId, isCraftingPattern, selectedIngredients, outputs, false);
    }

    public CreateCtrlQPatternC2SPacket(ResourceLocation recipeId, boolean isCraftingPattern, List<ItemStack> selectedIngredients, List<ItemStack> outputs, boolean openProviderSelector) {
        this.recipeId = recipeId;
        this.isCraftingPattern = isCraftingPattern;
        this.selectedIngredients = selectedIngredients;
        this.outputs = outputs;
        this.openProviderSelector = openProviderSelector;
    }

    public static void encode(CreateCtrlQPatternC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.recipeId);
        buf.writeBoolean(msg.isCraftingPattern);
        buf.writeInt(msg.selectedIngredients.size());
        for (ItemStack stack : msg.selectedIngredients) {
            buf.writeItem(stack);
        }
        buf.writeInt(msg.outputs.size());
        for (ItemStack stack : msg.outputs) {
            buf.writeItem(stack);
        }
        buf.writeBoolean(msg.openProviderSelector);
    }

    public static CreateCtrlQPatternC2SPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        boolean isCraftingPattern = buf.readBoolean();

        int ingredientCount = buf.readInt();
        List<ItemStack> ingredients = new ArrayList<>();
        for (int i = 0; i < ingredientCount; i++) {
            ingredients.add(buf.readItem());
        }

        int outputCount = buf.readInt();
        List<ItemStack> outputs = new ArrayList<>();
        for (int i = 0; i < outputCount; i++) {
            outputs.add(buf.readItem());
        }

        boolean openProviderSelector = buf.readableBytes() > 0 && buf.readBoolean();
        return new CreateCtrlQPatternC2SPacket(recipeId, isCraftingPattern, ingredients, outputs, openProviderSelector);
    }

    public static void handle(CreateCtrlQPatternC2SPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }

            RecipeManager recipeManager = player.level().getRecipeManager();
            var recipeOpt = recipeManager.byKey(msg.recipeId);
            if (recipeOpt.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.extendedae_plus.recipe_not_found"), false);
                return;
            }

            Recipe<?> recipe = recipeOpt.get();

            if (!consumeBlankPattern(player)) {
                player.displayClientMessage(Component.translatable("message.extendedae_plus.no_blank_pattern"), false);
                return;
            }

            ItemStack pattern = createPattern(recipe, msg.isCraftingPattern, msg.selectedIngredients, msg.outputs, player);
            if (pattern.isEmpty()) {
                player.getInventory().add(AEItems.BLANK_PATTERN.stack());
                player.displayClientMessage(Component.translatable("message.extendedae_plus.pattern_creation_failed"), false);
                return;
            }

            if (msg.openProviderSelector) {
                String pendingId = ProviderUploadUtil.beginPendingCtrlQUpload(player, pattern);
                if (pendingId == null) {
                    if (!player.getInventory().add(pattern)) {
                        player.drop(pattern, false);
                    }
                }
                return;
            }

            if (!player.getInventory().add(pattern)) {
                player.drop(pattern, false);
            }
        });
        ctx.setPacketHandled(true);
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
                if (def != null) {
                    WTMenuHost wtHost = def.wTMenuHostFactory().create(player, null, terminal, (p, sub) -> {
                    });
                    if (wtHost != null) {
                        var node = wtHost.getActionableNode();
                        if (node != null) {
                            grid = node.getGrid();
                            if (grid != null && wtHost.drainPower()) {
                                usedWtHost = true;
                            } else {
                                return false;
                            }
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
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
            if (grid == null) {
                return false;
            }
            if (!wt.hasPower(player, 0.5, terminal)) {
                return false;
            }
            usedWtHost = false;
        }

        AEItemKey blankPatternKey = AEItemKey.of(AEItems.BLANK_PATTERN.stack());
        IEnergyService energy = grid.getEnergyService();
        MEStorage storage = grid.getStorageService().getInventory();

        long extracted = StorageHelper.poweredExtraction(
            energy,
            storage,
            blankPatternKey,
            1,
            new PlayerSource(player)
        );

        if (extracted > 0) {
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

        return false;
    }

    private static ItemStack createPattern(Recipe<?> recipe, boolean isCrafting, List<ItemStack> selectedIngredients, List<ItemStack> selectedOutputs, ServerPlayer player) {
        try {
            LOGGER.info("[CreatePattern] ==================== 开始创建样板 ====================");
            LOGGER.info("[CreatePattern] 配方ID: {}", recipe.getId());
            LOGGER.info("[CreatePattern] 配方类型: {}", isCrafting ? "工作台配方" : "加工配方");
            LOGGER.info("[CreatePattern] selectedIngredients数量: {}", selectedIngredients.size());
            LOGGER.info("[CreatePattern] selectedOutputs数量: {}", selectedOutputs.size());

            // 打印接收到的材料
            for (int i = 0; i < selectedIngredients.size(); i++) {
                ItemStack item = selectedIngredients.get(i);
                LOGGER.info("[CreatePattern]   材料[{}]: {}", i, item);
            }
            for (int i = 0; i < selectedOutputs.size(); i++) {
                ItemStack item = selectedOutputs.get(i);
                LOGGER.info("[CreatePattern]   产物[{}]: {}", i, item);
            }

            if (isCrafting && recipe instanceof CraftingRecipe craftingRecipe) {
                LOGGER.info("[CreatePattern] 处理工作台配方");
                ItemStack[] inputs = new ItemStack[9];
                for (int i = 0; i < 9; i++) {
                    if (i < selectedIngredients.size()) {
                        inputs[i] = selectedIngredients.get(i).copy();
                    } else {
                        inputs[i] = ItemStack.EMPTY;
                    }
                }

                ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
                ItemStack encodedPattern = PatternDetailsHelper.encodeCraftingPattern(
                    craftingRecipe,
                    inputs,
                    output,
                    true,
                    false
                );

                encodedPattern.getOrCreateTag().putString("encodePlayer", player.getName().getString());
                addRecipeTypeSuffixToPattern(encodedPattern, recipe, output);
                LOGGER.info("[CreatePattern] 工作台样板创建完成");
                return encodedPattern;
            }

            LOGGER.info("[CreatePattern] 处理加工配方，开始转换ItemStack到GenericStack");
            List<GenericStack> inputs = new ArrayList<>();
            List<GenericStack> outputs = new ArrayList<>();

            // 转换输入材料
            for (int i = 0; i < selectedIngredients.size(); i++) {
                ItemStack item = selectedIngredients.get(i);
                if (!item.isEmpty()) {
                    GenericStack genericStack = GenericStack.unwrapItemStack(item);
                    if (genericStack != null) {
                        // 这是包装的流体
                        inputs.add(genericStack);
                        LOGGER.info("[CreatePattern]   材料[{}] unwrap -> 流体: {}", i, genericStack);
                    } else {
                        // 这是普通物品
                        AEItemKey itemKey = AEItemKey.of(item);
                        if (itemKey != null) {
                            GenericStack itemGeneric = new GenericStack(itemKey, item.getCount());
                            inputs.add(itemGeneric);
                            LOGGER.info("[CreatePattern]   材料[{}] -> 物品: {}", i, itemGeneric);
                        }
                    }
                }
            }

            // 转换输出产物
            for (int i = 0; i < selectedOutputs.size(); i++) {
                ItemStack item = selectedOutputs.get(i);
                if (!item.isEmpty()) {
                    GenericStack genericStack = GenericStack.unwrapItemStack(item);
                    if (genericStack != null) {
                        // 这是包装的流体
                        outputs.add(genericStack);
                        LOGGER.info("[CreatePattern]   产物[{}] unwrap -> 流体: {}", i, genericStack);
                    } else {
                        // 这是普通物品
                        AEItemKey itemKey = AEItemKey.of(item);
                        if (itemKey != null) {
                            GenericStack itemGeneric = new GenericStack(itemKey, item.getCount());
                            outputs.add(itemGeneric);
                            LOGGER.info("[CreatePattern]   产物[{}] -> 物品: {}", i, itemGeneric);
                        }
                    }
                }
            }

            LOGGER.info("[CreatePattern] 转换完成：inputs={} 个, outputs={} 个", inputs.size(), outputs.size());

            ItemStack encodedPattern = PatternDetailsHelper.encodeProcessingPattern(
                inputs.toArray(new GenericStack[0]),
                outputs.toArray(new GenericStack[0])
            );

            LOGGER.info("[CreatePattern] 加工样板编码完成");

            encodedPattern.getOrCreateTag().putString("encodePlayer", player.getName().getString());

            // 提取第一个输出用于命名
            ItemStack firstOutput = selectedOutputs.isEmpty() ? ItemStack.EMPTY : selectedOutputs.get(0);
            if (firstOutput.isEmpty() && recipe != null) {
                firstOutput = recipe.getResultItem(player.level().registryAccess());
            }
            addRecipeTypeSuffixToPattern(encodedPattern, recipe, firstOutput);

            return encodedPattern;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 为样板添加工作方块后缀
     *
     * <p>设置样板的显示名称为："物品名_工作方块名称"（如"铁锭_熔炉"）</p>
     *
     * @param pattern 已编码的样板ItemStack
     * @param recipe 配方对象
     * @param outputItem 输出物品（用于提取名称）
     */
    private static void addRecipeTypeSuffixToPattern(ItemStack pattern, Recipe<?> recipe, ItemStack outputItem) {
        if (pattern.isEmpty() || recipe == null || outputItem.isEmpty()) {
            LOGGER.warn("[CTRL+Q] 添加后缀失败：参数为空 pattern={}, recipe={}, outputItem={}",
                pattern.isEmpty(), recipe == null, outputItem.isEmpty());
            return;
        }

        try {
            // 通过 JEI 获取工作方块的本地化名称
            String workstationName = RecipeTypeNameConfig.getWorkstationNameFromJEI(recipe);

            // 回退方案：JEI 获取失败时使用配方类型路径
            if (workstationName == null || workstationName.isBlank()) {
                RecipeType<?> type = recipe.getType();
                ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
                workstationName = key != null ? key.getPath() : "unknown";
                LOGGER.info("[CTRL+Q] JEI 获取失败，使用配方类型路径: {}", workstationName);
            }

            // 获取输出物品的本地化名称
            String itemName = outputItem.getHoverName().getString();
            if (itemName == null || itemName.isBlank()) {
                itemName = outputItem.getDisplayName().getString();
                LOGGER.info("[CTRL+Q] 使用 displayName：{}", itemName);
            }

            if (itemName == null || itemName.isBlank()) {
                LOGGER.warn("[CTRL+Q] 物品名称为空：outputItem={}", outputItem);
                return;
            }

            // 创建新的样板名称: "物品名_工作方块名称"
            String patternName = itemName + "_" + workstationName;
            LOGGER.info("[CTRL+Q] 设置样板名称：{} (工作方块: {})", patternName, workstationName);
            pattern.setHoverName(Component.literal(patternName));

            // 验证是否设置成功
            String finalName = pattern.getHoverName().getString();
            LOGGER.info("[CTRL+Q] 验证样板名称：设置前={}, 设置后={}", itemName, finalName);

        } catch (Exception e) {
            LOGGER.error("[CTRL+Q] 添加工作方块后缀时发生异常", e);
        }
    }
}