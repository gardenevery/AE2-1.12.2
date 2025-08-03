package appeng.container.implementations;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.oredict.OreDictionary;

import appeng.api.AEApi;
import appeng.api.config.*;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.guisync.GuiSync;
import appeng.container.slot.SlotRestrictedInput;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketValueConfig;
import appeng.parts.misc.PartOreDicStorageBus;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import appeng.util.item.OreReference;

public class ContainerOreDictStorageBus extends ContainerUpgradeable {
    private final PartOreDicStorageBus part;

    @GuiSync(3)
    public AccessRestriction rwMode = AccessRestriction.READ_WRITE;

    @GuiSync(4)
    public StorageFilter storageFilter = StorageFilter.EXTRACTABLE_ONLY;

    @GuiSync(7)
    public YesNo stickyMode = YesNo.NO;

    public ContainerOreDictStorageBus(final InventoryPlayer ip, final PartOreDicStorageBus anchor) {
        super(ip, anchor);
        this.part = anchor;
    }

    @Override
    public void detectAndSendChanges() {
        this.verifyPermissions(SecurityPermissions.BUILD, false);

        if (Platform.isServer()) {
            this.setReadWriteMode((AccessRestriction) part.getConfigManager().getSetting(Settings.ACCESS));
            this.setStorageFilter((StorageFilter) part.getConfigManager().getSetting(Settings.STORAGE_FILTER));
            this.setStickyMode((YesNo) this.getUpgradeable().getConfigManager().getSetting(Settings.STICKY_MODE));
        }

        super.standardDetectAndSendChanges();
    }

    @Override
    protected int getHeight() {
        return 170;
    }

    public void partition() {
        final IMEInventory<IAEItemStack> cellInv = this.part.getInternalHandler();

        if (cellInv == null) {
            return;
        }

        Set<Integer> oreIDs = new HashSet<>();

        for (IAEItemStack itemStack : cellInv.getAvailableItems(
                AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class).createList())) {
            OreReference ref = ((AEItemStack) itemStack).getOre().orElse(null);
            if (ref != null) {
                oreIDs.addAll(ref.getOres());
            }
        }

        String oreMatch = "(";
        String append = "";

        for (Iterator<Integer> it = oreIDs.iterator(); it.hasNext();) {
            int oreID = it.next();
            if (it.hasNext()) {
                append = ")|(";
            } else {
                append = ")";
            }
            oreMatch = oreMatch.concat(OreDictionary.getOreName(oreID) + append);
        }

        if (oreMatch.equals("(")) {
            oreMatch = "";
        }
        part.saveOreMatch(oreMatch);

        this.detectAndSendChanges();
    }

    public void saveOreMatch(String value) {
        part.saveOreMatch(value);
    }

    public void sendRegex() {
        try {
            NetworkHandler.instance().sendTo(new PacketValueConfig("OreDictStorageBus.sendRegex", part.getOreExp()),
                    (EntityPlayerMP) getInventoryPlayer().player);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public AccessRestriction getReadWriteMode() {
        return this.rwMode;
    }

    private void setReadWriteMode(final AccessRestriction rwMode) {
        this.rwMode = rwMode;
    }

    public StorageFilter getStorageFilter() {
        return this.storageFilter;
    }

    private void setStorageFilter(final StorageFilter storageFilter) {
        this.storageFilter = storageFilter;
    }

    @Override
    protected void setupConfig() {
        final IItemHandler upgrades = this.getUpgradeable().getInventoryByName("upgrades");
        this.addSlotToContainer((new SlotRestrictedInput(SlotRestrictedInput.PlacableItemType.UPGRADES, upgrades, 0,
                187, 8, this.getInventoryPlayer()))
                .setNotDraggable());
    }

    @Override
    public int availableUpgrades() {
        return 1;
    }

    @Override
    protected boolean supportCapacity() {
        return false;
    }

    public YesNo getStickyMode() {
        return this.stickyMode;
    }

    private void setStickyMode(final YesNo stickyMode) {
        this.stickyMode = stickyMode;
    }
}
