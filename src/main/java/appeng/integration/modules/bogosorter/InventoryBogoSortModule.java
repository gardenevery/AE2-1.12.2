package appeng.integration.modules.bogosorter;

import java.util.Comparator;

import com.cleanroommc.bogosorter.common.sort.SortHandler;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.Platform;

public class InventoryBogoSortModule {
    private static final boolean loaded = Platform.isModLoaded("bogosorter");

    public static final Comparator<IAEItemStack> COMPARATOR = (o1, o2) -> SortHandler.getClientItemComparator()
            .compare(o1.getDefinition(), o2.getDefinition());

    public static boolean isLoaded() {

        return loaded;
    }
}
