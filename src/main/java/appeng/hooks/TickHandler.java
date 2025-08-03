/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.hooks;

import java.util.*;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import appeng.api.AEApi;
import appeng.api.networking.IGridNode;
import appeng.api.parts.CableRenderMode;
import appeng.api.util.AEColor;
import appeng.core.AEConfig;
import appeng.core.AELog;
import appeng.core.AppEng;
import appeng.core.sync.packets.PacketPaintedEntity;
import appeng.crafting.CraftingJob;
import appeng.me.Grid;
import appeng.tile.AEBaseTile;
import appeng.util.IWorldCallable;
import appeng.util.Platform;

public class TickHandler {

    private static final TickHandler INSTANCE = new TickHandler();
    private final Queue<IWorldCallable<?>> serverQueue = new ArrayDeque<>();
    private final Multimap<World, CraftingJob> craftingJobs = LinkedListMultimap.create();
    private final Map<World, Queue<IWorldCallable<?>>> callQueue = new WeakHashMap<>();
    private final HandlerRep server = new HandlerRep();
    private final HandlerRep client = new HandlerRep();
    private final Map<Integer, PlayerColor> cliPlayerColors = new HashMap<>();
    private final Map<Integer, PlayerColor> srvPlayerColors = new HashMap<>();
    private CableRenderMode crm = CableRenderMode.STANDARD;

    public static TickHandler instance() {
        return INSTANCE;
    }

    public Map<Integer, PlayerColor> getPlayerColors() {
        if (Platform.isServer()) {
            return this.srvPlayerColors;
        }
        return this.cliPlayerColors;
    }

    /**
     * Add a server or world callback which gets called the next time the queue is ticked.
     * <p>
     * Callbacks on the client are not support.
     * <p>
     * Using null as world will queue it into the global {@link TickEvent.ServerTickEvent}, otherwise it will be ticked
     * with the corresponding {@link TickEvent.WorldTickEvent}.
     *
     * @param w null or the specific {@link World}
     * @param c the callback
     */
    public void addCallable(final World w, final IWorldCallable<?> c) {
        if (w == null) {
            this.serverQueue.add(c);
        } else {
            Queue<IWorldCallable<?>> queue = this.callQueue.get(w);

            if (queue == null) {
                queue = new ArrayDeque<>();
                this.callQueue.put(w, queue);
            }

            queue.add(c);
        }
    }

    public void addInit(final AEBaseTile tile) {
        // for no there is no reason to care about this on the client...
        if (Platform.isServer()) {
            this.getRepo().tiles.add(tile);
        }
    }

    private HandlerRep getRepo() {
        return Platform.isServer() ? this.server : this.client;
    }

    public void addNetwork(final Grid grid) {
        // for no there is no reason to care about this on the client...
        if (Platform.isServer()) {
            this.getRepo().addNetwork(grid);
        }
    }

    public void removeNetwork(final Grid grid) {
        // for no there is no reason to care about this on the client...
        if (Platform.isServer()) {
            this.getRepo().removeNetwork(grid);
        }
    }

    public Iterable<Grid> getGridList() {
        return this.getRepo().networks;
    }

    public void shutdown() {
        this.getRepo().clear();
    }

    @SubscribeEvent
    public void unloadWorld(final WorldEvent.Unload ev) {
        // for no there is no reason to care about this on the client...
        if (Platform.isServer()) {
            final List<IGridNode> toDestroy = new ArrayList<>();

            this.getRepo().updateNetworks();
            for (final Grid g : this.getRepo().networks) {
                for (final IGridNode n : g.getNodes()) {
                    if (n.getWorld() == ev.getWorld()) {
                        toDestroy.add(n);
                    }
                }
            }

            for (final IGridNode n : toDestroy) {
                n.destroy();
            }
        }
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent ev) {

        if (ev.phase == Phase.START) {
            this.tickColors(this.cliPlayerColors);
            final CableRenderMode currentMode = AEApi.instance().partHelper().getCableRenderMode();
            if (currentMode != this.crm) {
                this.crm = currentMode;
                AppEng.proxy.triggerUpdates();
            }
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent ev) {
        if (ev.phase == Phase.START) {
            final Queue<IWorldCallable<?>> queue = this.callQueue.get(ev.world);
            this.processQueue(queue, ev.world);
        }

        if (ev.phase == Phase.END) {
            synchronized (this.craftingJobs) {
                final Collection<CraftingJob> jobSet = this.craftingJobs.get(ev.world);

                if (!jobSet.isEmpty()) {
                    final int jobSize = jobSet.size();
                    final int microSecondsPerTick = AEConfig.instance().getCraftingCalculationTimePerTick() * 1000;
                    final int simTime = Math.max(1, microSecondsPerTick / jobSize);

                    final Iterator<CraftingJob> i = jobSet.iterator();

                    while (i.hasNext()) {
                        final CraftingJob cj = i.next();
                        if (!cj.simulateFor(simTime)) {
                            i.remove();
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase == Phase.END) {
            this.tickColors(this.srvPlayerColors);
            // ready tiles.
            final HandlerRep repo = this.getRepo();
            while (!repo.tiles.isEmpty()) {
                final AEBaseTile bt = repo.tiles.poll();
                if (!bt.isInvalid()) {
                    bt.onReady();
                }
            }

            // tick networks.
            this.getRepo().updateNetworks();
            for (final Grid g : this.getRepo().networks) {
                g.update();
            }

            // cross world queue.
            this.processQueue(this.serverQueue, null);
        }
    }

    private void tickColors(final Map<Integer, PlayerColor> playerSet) {
        final Iterator<PlayerColor> i = playerSet.values().iterator();
        while (i.hasNext()) {
            final PlayerColor pc = i.next();
            if (pc.ticksLeft <= 0) {
                i.remove();
            }
            pc.ticksLeft--;
        }
    }

    private void processQueue(final Queue<IWorldCallable<?>> queue, final World world) {
        if (queue == null) {
            return;
        }

        final Stopwatch sw = Stopwatch.createStarted();

        IWorldCallable<?> c;
        while ((c = queue.poll()) != null) {
            try {
                c.call(world);

                if (sw.elapsed(TimeUnit.MILLISECONDS) > 50) {
                    break;
                }
            } catch (final Exception e) {
                AELog.debug("Queue processing error: {}", e.getMessage());
            }
        }
    }

    public void registerCraftingSimulation(final World world, final CraftingJob craftingJob) {
        synchronized (this.craftingJobs) {
            this.craftingJobs.put(world, craftingJob);
        }
    }

    private static class HandlerRep {

        private Queue<AEBaseTile> tiles = new ArrayDeque<>();
        private Set<Grid> networks = new HashSet<>();
        private Set<Grid> toAdd = new HashSet<>();
        private Set<Grid> toRemove = new HashSet<>();

        private void clear() {
            this.tiles = new ArrayDeque<>();
            this.networks = new HashSet<>();
            this.toAdd = new HashSet<>();
            this.toRemove = new HashSet<>();
        }

        private synchronized void addNetwork(Grid g) {
            this.toAdd.add(g);
            this.toRemove.remove(g);
        }

        private synchronized void removeNetwork(Grid g) {
            this.toRemove.add(g);
            this.toAdd.remove(g);
        }

        private synchronized void updateNetworks() {
            this.networks.removeAll(this.toRemove);
            this.toRemove.clear();

            this.networks.addAll(this.toAdd);
            this.toAdd.clear();
        }
    }

    public static class PlayerColor {

        public final AEColor myColor;
        private final int myEntity;
        private int ticksLeft;

        public PlayerColor(final int id, final AEColor col, final int ticks) {
            this.myEntity = id;
            this.myColor = col;
            this.ticksLeft = ticks;
        }

        public PacketPaintedEntity getPacket() {
            return new PacketPaintedEntity(this.myEntity, this.myColor, this.ticksLeft);
        }
    }
}
