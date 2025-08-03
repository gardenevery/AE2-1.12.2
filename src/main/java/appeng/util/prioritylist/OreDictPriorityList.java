package appeng.util.prioritylist;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEItemStack;
import appeng.util.item.OreDictFilterMatcher;
import appeng.util.item.OreDictFilterMatcher.MatchRule;
import appeng.util.item.OreHelper;
import appeng.util.item.OreReference;

public class OreDictPriorityList<T extends IAEStack<T>> implements IPartitionList<T> {
    private final Set<Integer> oreIDs;
    private final boolean matchesEmptyOreDict;

    public OreDictPriorityList(List<MatchRule> oreMatch) {
        this.oreIDs = OreHelper.INSTANCE.getMatchingOre(oreMatch);
        this.matchesEmptyOreDict = OreDictFilterMatcher.matches(oreMatch, "");
    }

    @Override
    public boolean isListed(final T input) {
        OreReference or = ((AEItemStack) input).getOre().orElse(null);
        if (or == null)
            return matchesEmptyOreDict;

        for (Integer oreID : or.getOres()) {
            if (this.oreIDs.contains(oreID)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isEmpty() {
        return oreIDs.isEmpty();
    }

    @Override
    public Iterable<T> getItems() {
        return new ArrayList<>();
    }

}
