package com.extendedae_plus.menu;

import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import com.extendedae_plus.content.router.PatternRouterBlockEntity;
import com.extendedae_plus.init.ModMenuTypes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 样板输入总线的菜单
 *
 * 简单的9槽位界面，玩家可以放入编码样板
 */
public class PatternRouterMenu extends AEBaseMenu {

    private final PatternRouterBlockEntity host;

    public PatternRouterMenu(int id, Inventory playerInventory, PatternRouterBlockEntity host) {
        super(ModMenuTypes.PATTERN_ROUTER.get(), id, playerInventory, host);
        this.host = host;

        // 添加9个样板槽位（3x3网格）
        var patternStorage = host.getPatternStorage();
        for (int i = 0; i < 9; i++) {
            this.addSlot(new AppEngSlot(patternStorage, i), SlotSemantics.STORAGE);
        }

        this.createPlayerInventorySlots(playerInventory);
    }
}
