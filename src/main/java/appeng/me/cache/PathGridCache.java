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

package appeng.me.cache;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;

import appeng.api.AEApi;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.events.MENetworkBootingStatusChange;
import appeng.api.networking.events.MENetworkChannelChanged;
import appeng.api.networking.events.MENetworkControllerChange;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.pathing.ChannelMode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.core.AEConfig;
import appeng.core.AppEng;
import appeng.core.stats.IAdvancementTrigger;
import appeng.me.Grid;
import appeng.me.pathfinding.*;
import appeng.tile.networking.TileController;

public class PathGridCache implements IPathingGrid {

    private static final String TAG_CHANNEL_MODE = "channelMode";

    private PathingCalculation ongoingCalculation = null;
    private final Set<TileController> controllers = new HashSet<>();
    private final Set<IGridNode> nodesNeedingChannels = new HashSet<>();
    private final Set<IGridNode> cannotCarryCompressedNodes = new HashSet<>();
    private final Grid grid;
    private int channelsInUse = 0;
    private int channelsByBlocks = 0;
    private double channelPowerUsage = 0.0;
    private boolean recalculateControllerNextTick = true;
    // Flag to indicate a reboot should occur next tick
    private boolean reboot = true;
    private boolean booting = false;
    private ControllerState controllerState = ControllerState.NO_CONTROLLER;
    private int ticksUntilReady = 0;
    private int lastChannels = 0;
    /**
     * This can be used for testing to set a specific channel mode on this grid that will not be overwritten by
     * repathing.
     */
    private boolean channelModeLocked;
    private ChannelMode channelMode = AEConfig.instance().getChannelMode();

    public PathGridCache(final IGrid g) {
        this.grid = (Grid) g;
    }

    @Override
    public void onUpdateTick() {
        if (this.recalculateControllerNextTick) {
            this.recalcController();
        }

        if (this.reboot) {
            this.reboot = false;

            if (!this.booting) {
                this.booting = true;
                this.grid.postEvent(new MENetworkBootingStatusChange());
            }

            this.channelsInUse = 0;

            if (this.controllerState == ControllerState.NO_CONTROLLER) {
                var requiredChannels = this.calculateAdHocChannels();
                int used = requiredChannels;
                if (requiredChannels > channelMode.getAdHocNetworkChannels()) {
                    used = 0;
                }
                this.channelsInUse = used;

                var nodes = this.grid.getNodes().size();
                this.ticksUntilReady = 1;
                this.channelsByBlocks = nodes * used;
                this.setChannelPowerUsage(this.channelsByBlocks / 128.0);

                this.grid.getPivot().beginVisit(new AdHocChannelUpdater(used));
            } else if (this.controllerState == ControllerState.CONTROLLER_CONFLICT) {
                this.ticksUntilReady = 1;
                this.grid.getPivot().beginVisit(new AdHocChannelUpdater(0));
            } else {
                this.ticksUntilReady = 1;
                this.ongoingCalculation = new PathingCalculation(grid);
            }
        }

        if (this.booting) {
            // Work on remaining pathfinding work
            if (ongoingCalculation != null) {
                ongoingCalculation.compute();
                this.channelsByBlocks = ongoingCalculation.getChannelsByBlocks();
                this.channelsInUse = ongoingCalculation.getChannelsInUse();
                ongoingCalculation = null;
            }

            this.ticksUntilReady--;

            if (ticksUntilReady <= 0) {
                this.booting = false;
                if (this.controllerState == ControllerState.CONTROLLER_ONLINE) {
                    var controllerIterator = this.controllers.iterator();
                    if (controllerIterator.hasNext()) {
                        var controller = controllerIterator.next();
                        controller.getGridNode(AEPartLocation.INTERNAL).beginVisit(new ControllerChannelUpdater());
                    }
                }

                // check for achievements
                this.achievementPost();

                this.setChannelPowerUsage(this.channelsByBlocks / 128.0);

                this.booting = false;
                this.grid.postEvent(new MENetworkBootingStatusChange());
            }
        }
    }

    @Override
    public void removeNode(final IGridNode gridNode, final IGridHost machine) {
        if (machine instanceof TileController) {
            this.controllers.remove(machine);
            this.recalculateControllerNextTick = true;
        }

        if (gridNode.hasFlag(GridFlags.REQUIRE_CHANNEL)) {
            this.nodesNeedingChannels.remove(gridNode);
        }

        if (gridNode.hasFlag(GridFlags.CANNOT_CARRY_COMPRESSED)) {
            this.cannotCarryCompressedNodes.remove(gridNode);
        }

        this.repath();
    }

    @Override
    public void addNode(final IGridNode gridNode, final IGridHost machine) {
        if (machine instanceof TileController) {
            this.controllers.add((TileController) machine);
            this.recalculateControllerNextTick = true;
        }

        if (gridNode.hasFlag(GridFlags.REQUIRE_CHANNEL)) {
            this.nodesNeedingChannels.add(gridNode);
        }

        if (gridNode.hasFlag(GridFlags.CANNOT_CARRY_COMPRESSED)) {
            this.cannotCarryCompressedNodes.add(gridNode);
        }

        this.repath();
    }

    private void recalcController() {
        this.recalculateControllerNextTick = false;
        final ControllerState old = this.controllerState;

        if (this.controllers.isEmpty()) {
            this.controllerState = ControllerState.NO_CONTROLLER;
        } else {
            final IGridNode startingNode = this.controllers.iterator().next().getGridNode(AEPartLocation.INTERNAL);
            if (startingNode == null) {
                this.controllerState = ControllerState.CONTROLLER_CONFLICT;
                return;
            }

            final DimensionalCoord dc = startingNode.getGridBlock().getLocation();
            final ControllerValidator cv = new ControllerValidator(dc.x, dc.y, dc.z);

            startingNode.beginVisit(cv);

            if (cv.isValid() && cv.getFound() == this.controllers.size()) {
                this.controllerState = ControllerState.CONTROLLER_ONLINE;
            } else {
                this.controllerState = ControllerState.CONTROLLER_CONFLICT;
            }
        }

        if (old != this.controllerState) {
            this.grid.postEvent(new MENetworkControllerChange());
        }
    }

    private int calculateAdHocChannels() {
        var ignore = new HashSet<IGridNode>();

        int channels = 0;
        for (var node : this.nodesNeedingChannels) {
            if (!ignore.contains(node)) {
                final IGridBlock gb = node.getGridBlock();
                // Prevent ad-hoc networks from being connected to the outside and inside node of P2P tunnels at the
                // same time
                // this effectively prevents the nesting of P2P-tunnels in ad-hoc networks.
                if (node.hasFlag(GridFlags.COMPRESSED_CHANNEL) && !this.cannotCarryCompressedNodes.isEmpty()) {
                    return channelMode.getAdHocNetworkChannels() + 1;
                }

                channels++;

                // Multiblocks only require a single channel. Add the remainder of the multi-block to the ignore-list,
                // to make this method skip them for channel calculation.
                if (node.hasFlag(GridFlags.MULTIBLOCK)) {
                    final IGridMultiblock gmb = (IGridMultiblock) gb;
                    final Iterator<IGridNode> i = gmb.getMultiblockNodes();
                    while (i.hasNext()) {
                        ignore.add(i.next());
                    }
                }
            }
        }

        return channels;
    }

    private void achievementPost() {
        if (this.lastChannels != this.channelsInUse) {
            final IAdvancementTrigger currentBracket = this.getAchievementBracket(this.channelsInUse);
            final IAdvancementTrigger lastBracket = this.getAchievementBracket(this.lastChannels);
            if (currentBracket != lastBracket && currentBracket != null) {
                for (var n : this.nodesNeedingChannels) {
                    var player = AEApi.instance().registries().players().findPlayer(n.getPlayerID());
                    if (player instanceof EntityPlayerMP) {
                        currentBracket.trigger((EntityPlayerMP) player);
                    }
                }
            }
        }
        this.lastChannels = this.channelsInUse;
    }

    private IAdvancementTrigger getAchievementBracket(final int ch) {
        if (ch < 8) {
            return null;
        }

        if (ch < 128) {
            return AppEng.instance().getAdvancementTriggers().getNetworkApprentice();
        }

        if (ch < 2048) {
            return AppEng.instance().getAdvancementTriggers().getNetworkEngineer();
        }

        return AppEng.instance().getAdvancementTriggers().getNetworkAdmin();
    }

    @MENetworkEventSubscribe
    void updateNodReq(final MENetworkChannelChanged ev) {
        final IGridNode gridNode = ev.node;

        if (gridNode.hasFlag(GridFlags.REQUIRE_CHANNEL)) {
            this.nodesNeedingChannels.add(gridNode);
        } else {
            this.nodesNeedingChannels.remove(gridNode);
        }

        this.repath();
    }

    @Override
    public boolean isNetworkBooting() {
        return this.booting;
    }

    @Override
    public ControllerState getControllerState() {
        return this.controllerState;
    }

    @Override
    public void repath() {
        if (!this.channelModeLocked) {
            this.channelMode = AEConfig.instance().getChannelMode();
        }

        // clean up...
        this.ongoingCalculation = null;

        this.channelsByBlocks = 0;
        this.reboot = true;
    }

    double getChannelPowerUsage() {
        return this.channelPowerUsage;
    }

    private void setChannelPowerUsage(final double channelPowerUsage) {
        this.channelPowerUsage = channelPowerUsage;
    }

    public ChannelMode getChannelMode() {
        return channelMode;
    }

    public void setForcedChannelMode(@Nullable ChannelMode forcedChannelMode) {
        if (forcedChannelMode == null) {
            if (this.channelModeLocked) {
                this.channelModeLocked = false;
                repath();
            }
        } else {
            this.channelModeLocked = true;
            if (this.channelMode != forcedChannelMode) {
                this.channelMode = forcedChannelMode;
                this.repath();
            }
        }
    }

    @Override
    public void onSplit(IGridStorage destinationStorage) {
        populateGridStorage(destinationStorage);
    }

    @Override
    public void onJoin(IGridStorage sourceStorage) {
        var tag = sourceStorage.dataObject();
        var channelModeName = tag.getString(TAG_CHANNEL_MODE);
        try {
            channelMode = ChannelMode.valueOf(channelModeName);
            channelModeLocked = true;
        } catch (IllegalArgumentException ignored) {
            channelModeLocked = false;
        }
    }

    @Override
    public void populateGridStorage(IGridStorage destinationStorage) {
        var tag = destinationStorage.dataObject();
        if (channelModeLocked) {
            tag.setString(TAG_CHANNEL_MODE, channelMode.name());
        } else {
            tag.removeTag(TAG_CHANNEL_MODE);
        }
    }

}
