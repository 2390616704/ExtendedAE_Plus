package com.extendedae_plus.client.screen;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import com.extendedae_plus.menu.PatternRouterMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 样板输入总线界面
 *
 * 简单的9槽位界面，显示样板存储
 */
public class PatternRouterScreen extends AEBaseScreen<PatternRouterMenu> {

    public PatternRouterScreen(PatternRouterMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }
}
