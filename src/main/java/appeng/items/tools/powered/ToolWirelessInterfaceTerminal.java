package appeng.items.tools.powered;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.IGuiHandler;

import appeng.api.AEApi;
import appeng.core.sync.GuiBridge;

public class ToolWirelessInterfaceTerminal extends ToolWirelessTerminal {
    @Override
    public boolean canHandle(ItemStack is) {
        return AEApi.instance().definitions().items().wirelessInterfaceTerminal().isSameAs(is);
    }

    @Override
    public IGuiHandler getGuiHandler(ItemStack is) {
        return GuiBridge.GUI_WIRELESS_INTERFACE_TERMINAL;
    }
}
