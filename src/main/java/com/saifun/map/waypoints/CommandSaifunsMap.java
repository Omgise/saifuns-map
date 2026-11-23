package com.saifun.map.waypoints;

import com.saifun.map.ClientEventHandler;
import com.saifun.map.ClientProxy;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.engine.MapPngExporter;
import com.saifun.map.gui.GuiWaypointEdit;
import com.saifun.map.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;

/**
 * Client-side command (/smap) powering clickable shared waypoint chat buttons
 * ([Save], [Edit], [Beacon]) and quick location sharing (/smap share [name]).
 */
public class CommandSaifunsMap extends CommandBase {

    private final WaypointManager waypointManager;

    public CommandSaifunsMap(WaypointManager waypointManager) {
        this.waypointManager = waypointManager;
    }

    @Override
    public String getCommandName() {
        return "smap";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/smap <save|edit|beacon|share|export> [args]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args == null || args.length == 0) {
            reply(sender, Lang.tr("saifunsmap.cmd.usage"));
            return;
        }

        String action = args[0].toLowerCase();

        if ("export".equals(action)) {
            if (SaifunsMapMod.proxy instanceof ClientProxy) {
                MapPngExporter.exportCurrentDimensionAsync(((ClientProxy) SaifunsMapMod.proxy).getWorldCache());
            }
            return;
        }

        if ("share".equals(action)) {
            Minecraft mc = Minecraft.getMinecraft();
            EntityClientPlayerMP player = mc.thePlayer;
            if (player == null || mc.theWorld == null) return;
            String name;
            if (args.length > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (i > 1) sb.append(' ');
                    sb.append(args[i]);
                }
                name = sb.toString().trim();
            } else {
                name = player.getDisplayName();
            }
            Waypoint wp = new Waypoint(
                name,
                null,
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY),
                MathHelper.floor_double(player.posZ),
                mc.theWorld.provider.dimensionId,
                WaypointManager.pickRandomColor(),
                Waypoint.Type.NORMAL
            );
            WaypointManager.shareInChat(wp);
            return;
        }

        if (args.length < 2) {
            reply(sender, Lang.tr("saifunsmap.cmd.missing_data"));
            return;
        }

        if ("importgroup".equals(action)) {
            int imported = waypointManager.importReceivedGroup(args[1]);
            if (imported < 0) {
                reply(sender, Lang.tr("saifunsmap.cmd.group_expired"));
            } else if (imported == 0) {
                reply(sender, Lang.tr("saifunsmap.cmd.group_none"));
            } else {
                reply(sender, Lang.tr("saifunsmap.cmd.group_imported", imported));
            }
            return;
        }

        Waypoint decoded = WaypointManager.decodeSharePayload(args[1]);
        if (decoded == null) {
            reply(sender, Lang.tr("saifunsmap.cmd.invalid_data"));
            return;
        }

        if ("save".equals(action)) {
            if (waypointManager.addIfNotDuplicate(decoded)) {
                waypointManager.save();
                reply(sender, Lang.tr("saifunsmap.cmd.saved",
                    decoded.name, decoded.x, decoded.y, decoded.z, decoded.dimensionId));
            } else {
                reply(sender, Lang.tr("saifunsmap.cmd.exists", decoded.name));
            }
        } else if ("edit".equals(action)) {
            GuiWaypointEdit screen = new GuiWaypointEdit(
                null,
                waypointManager,
                null,
                decoded.name,
                decoded.x,
                decoded.y,
                decoded.z,
                decoded.dimensionId,
                decoded.color
            );
            ClientEventHandler.scheduleOpenScreen(screen);
        } else if ("beacon".equals(action)) {
            boolean active = waypointManager.toggleTempBeacon(
                decoded.name,
                decoded.x,
                decoded.y,
                decoded.z,
                decoded.dimensionId,
                decoded.color
            );
            if (active) {
                reply(sender, Lang.tr("saifunsmap.cmd.beacon_on",
                    decoded.name, decoded.x, decoded.y, decoded.z));
            } else {
                reply(sender, Lang.tr("saifunsmap.cmd.beacon_off"));
            }
        }
    }

    private static void reply(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText("\u00a7b[Saifun's Map]\u00a7r " + msg));
    }
}
