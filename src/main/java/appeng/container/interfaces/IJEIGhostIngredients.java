package appeng.container.interfaces;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mezz.jei.api.gui.IGhostIngredientHandler.Target;

public interface IJEIGhostIngredients {
    List<Target<?>> getPhantomTargets(Object ingredient);

    default Map<Target<?>, Object> getFakeSlotTargetMap() {
        return new HashMap<>();
    }

}
