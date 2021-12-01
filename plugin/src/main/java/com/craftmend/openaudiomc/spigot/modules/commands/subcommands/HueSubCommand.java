package com.craftmend.openaudiomc.spigot.modules.commands.subcommands;

import com.craftmend.openaudiomc.OpenAudioMc;

import com.craftmend.openaudiomc.generic.networking.interfaces.NetworkingService;
import com.craftmend.openaudiomc.generic.user.User;
import com.craftmend.openaudiomc.spigot.OpenAudioMcSpigot;
import com.craftmend.openaudiomc.generic.commands.interfaces.SubCommand;
import com.craftmend.openaudiomc.generic.commands.objects.Argument;
import com.craftmend.openaudiomc.generic.hue.SerializedHueColor;
import com.craftmend.openaudiomc.generic.networking.packets.client.hue.PacketClientApplyHueColor;
import com.craftmend.openaudiomc.spigot.modules.players.SpigotPlayerService;
import com.craftmend.openaudiomc.spigot.modules.players.objects.SpigotConnection;
import com.craftmend.openaudiomc.spigot.modules.players.objects.SpigotPlayerSelector;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class HueSubCommand extends SubCommand {

    private OpenAudioMcSpigot openAudioMcSpigot;

    public HueSubCommand(OpenAudioMcSpigot openAudioMcSpigot) {
        super("hue");
        registerArguments(
                new Argument("set <selector> <lights> <r> <g> <b> <brightness>",
                        "Set the HUE lights of a specific selector to a RGBA value. The lights selection is a JSON array, like [1,2,3]")
        );
        this.openAudioMcSpigot = openAudioMcSpigot;
    }

    @Override
    public void onExecute(User sender, String[] args) {
        if (args.length == 0) {
            sender.makeExecuteCommand("oa help " + getCommand());
            return;
        }

        if (args.length == 7 && args[0].equals("set")) {
            SerializedHueColor serializedHueColor = new SerializedHueColor(Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
            for (Player player : new SpigotPlayerSelector(args[1]).getPlayers((CommandSender) sender.getOriginal())) {
                SpigotConnection spigotConnection = OpenAudioMc.getService(SpigotPlayerService.class).getClient(player);
                if (spigotConnection.getClientConnection().getSession().isHasHueLinked()) {
                    OpenAudioMc.getService(NetworkingService.class).send(spigotConnection.getClientConnection(), new PacketClientApplyHueColor(serializedHueColor, args[2]));
                }
            }
            message(sender, "updated hue state for " + args[1]);
            return;
        }

        sender.makeExecuteCommand("oa help " + getCommand());
    }
}
