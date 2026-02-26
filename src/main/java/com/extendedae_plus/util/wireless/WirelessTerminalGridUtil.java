package com.extendedae_plus.util.wireless;

import appeng.api.networking.IGrid;
import appeng.items.tools.powered.WirelessCraftingTerminalItem;
import appeng.items.tools.powered.WirelessTerminalItem;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import de.mari_023.ae2wtlib.wut.WTDefinition;
import de.mari_023.ae2wtlib.wut.WUTHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Resolve an AE2 grid from a wireless terminal carried by the player.
 */
public final class WirelessTerminalGridUtil {
    private WirelessTerminalGridUtil() {
    }

    public static @Nullable IGrid findPlayerGrid(ServerPlayer player) {
        if (player == null) {
            return null;
        }

        WirelessTerminalLocator.LocatedTerminal located = WirelessTerminalLocator.find(player);
        ItemStack terminal = located.stack;
        if (terminal.isEmpty()) {
            return null;
        }

        String curiosSlotId = located.getCuriosSlotId();
        int curiosIndex = located.getCuriosIndex();
        if (curiosSlotId != null && curiosIndex >= 0) {
            try {
                String current = WUTHandler.getCurrentTerminal(terminal);
                WTDefinition def = WUTHandler.wirelessTerminals.get(current);
                if (def == null) {
                    return null;
                }

                WTMenuHost wtHost = def.wTMenuHostFactory().create(player, null, terminal, (p, sub) -> {
                });
                if (wtHost == null) {
                    return null;
                }

                var node = wtHost.getActionableNode();
                if (node == null) {
                    return null;
                }

                IGrid grid = node.getGrid();
                if (grid == null || !wtHost.drainPower()) {
                    return null;
                }

                located.commit();
                return grid;
            } catch (Throwable ignored) {
                return null;
            }
        }

        WirelessCraftingTerminalItem wct = terminal.getItem() instanceof WirelessCraftingTerminalItem c ? c : null;
        WirelessTerminalItem wt = wct != null ? wct : (terminal.getItem() instanceof WirelessTerminalItem t ? t : null);
        if (wt == null) {
            return null;
        }

        IGrid grid = wt.getLinkedGrid(terminal, player.serverLevel(), player);
        if (grid == null) {
            return null;
        }
        if (!wt.hasPower(player, 0.5, terminal)) {
            return null;
        }
        return grid;
    }
}
