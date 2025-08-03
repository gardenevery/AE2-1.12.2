package appeng.server.subcommands;

import java.util.Locale;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import appeng.api.networking.pathing.ChannelMode;
import appeng.core.AEConfig;
import appeng.core.AELog;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.server.ISubCommand;

public class SetChannelMode implements ISubCommand {

    @Override
    public String getHelp(MinecraftServer srv) {
        return "commands.ae2.SetChannelMode.help";
    }

    @Override
    public void call(MinecraftServer srv, String[] args, ICommandSender sender) {
        String[] actualArgs = new String[args.length > 0 ? args.length - 1 : 0];
        if (args.length > 1) {
            System.arraycopy(args, 1, actualArgs, 0, args.length - 1);
        }

        if (actualArgs.length < 1) {
            ChannelMode currentMode = AEConfig.instance().getChannelMode();
            sender.sendMessage(new TextComponentString("Current channel mode: "
                    + currentMode.name().toLowerCase(Locale.ROOT)));

            sender.sendMessage(new TextComponentString("Usage: /ae2 channels <mode>"));
            sender.sendMessage(new TextComponentString("Available modes: "
                    + String.join(", ", ChannelMode.names())));
            return;
        }

        String modeName = actualArgs[0].toUpperCase(Locale.ROOT);
        ChannelMode newMode;
        try {
            newMode = ChannelMode.valueOf(modeName);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(new TextComponentString("Invalid channel mode '" + actualArgs[0] + "'!"));
            sender.sendMessage(new TextComponentString("Valid options: "
                    + String.join(", ", ChannelMode.names())));
            return;
        }

        AELog.info("{} is changing channel mode to {}", sender.getName(), newMode);

        AEConfig.instance().setChannelMode(newMode);
        AEConfig.instance().save();

        int gridCount = 0;
        for (Grid grid : TickHandler.instance().getGridList()) {
            grid.getPathingService().repath();
            gridCount++;
        }

        String resultMessage = String.format("Channel mode set to %s. Updated %d grids.",
                newMode.name().toLowerCase(Locale.ROOT), gridCount);
        sender.sendMessage(new TextComponentString(resultMessage));
    }
}
