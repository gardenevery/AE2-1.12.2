/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 AlgorithmX2
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package appeng.util.item;

import java.util.Map;

import javax.annotation.Nonnull;

import com.google.common.collect.MapMaker;

import net.minecraft.item.ItemStack;

public final class AEItemStackRegistry {

    private static final ItemStackHashStrategy HASH_STRATEGY = ItemStackHashStrategy.comparingAllButCount();
    private static final Map<Integer, AESharedItemStack> REGISTRY = new MapMaker().weakValues().makeMap();

    private AEItemStackRegistry() {
    }

    static synchronized AESharedItemStack getRegisteredStack(final @Nonnull ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            throw new IllegalArgumentException("stack cannot be empty");
        }

        var hash = HASH_STRATEGY.hashCode(itemStack);
        var ret = REGISTRY.get(hash);
        if (ret != null) {
            return ret;
        }

        // computeIfAbsent is not feasible since new AESharedItemStack gets
        // instantly GC'd when leaving the lambda.
        var itemStackCopy = itemStack.copy();
        itemStackCopy.setCount(1);
        var sharedStack = new AESharedItemStack(itemStackCopy);
        REGISTRY.put(hash, sharedStack);
        return sharedStack;
    }
}
