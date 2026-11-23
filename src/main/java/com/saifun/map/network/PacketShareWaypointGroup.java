package com.saifun.map.network;

import com.saifun.map.ClientProxy;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.waypoints.Waypoint;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional server-assisted packet to share an entire waypoint group with teammates in 1 click.
 */
public class PacketShareWaypointGroup implements IMessage {

    private static final int MAX_WAYPOINTS_PER_SHARE = 64;
    private static final long RATE_LIMIT_MS = 2000L;
    private static final Map<UUID, Long> LAST_SHARE_BY_PLAYER = new ConcurrentHashMap<>();

    public static final class SharedEntry {
        public String name;
        public int x, y, z, dim, color;
        public int iconOrdinal;

        public SharedEntry(String name, int x, int y, int z, int dim, int color, int iconOrdinal) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.color = color & 0xFFFFFF;
            this.iconOrdinal = iconOrdinal;
        }
    }

    public String senderName = "";
    public String groupName = "";
    public List<SharedEntry> entries = new ArrayList<>();

    public PacketShareWaypointGroup() {}

    public PacketShareWaypointGroup(String senderName, String groupName, List<SharedEntry> entries) {
        this.senderName = senderName != null ? senderName : "";
        this.groupName = groupName != null ? groupName : Waypoint.GROUP_DEFAULT;
        this.entries = entries != null ? entries : new ArrayList<>();
    }

    public static final class C2S extends PacketShareWaypointGroup {
        public C2S() {}
        public C2S(String senderName, String groupName, List<SharedEntry> entries) {
            super(senderName, groupName, entries);
        }
    }

    public static final class S2C extends PacketShareWaypointGroup {
        public S2C() {}
        public S2C(String senderName, String groupName, List<SharedEntry> entries) {
            super(senderName, groupName, entries);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.senderName = ByteBufUtils.readUTF8String(buf);
        this.groupName = ByteBufUtils.readUTF8String(buf);
        int count = Math.max(0, Math.min(buf.readUnsignedShort(), MAX_WAYPOINTS_PER_SHARE));
        this.entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = ByteBufUtils.readUTF8String(buf);
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            int dim = buf.readInt();
            int color = buf.readInt() & 0xFFFFFF;
            int iconOrd = buf.readUnsignedByte();
            this.entries.add(new SharedEntry(name, x, y, z, dim, color, iconOrd));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, senderName != null ? senderName : "");
        ByteBufUtils.writeUTF8String(buf, groupName != null ? groupName : Waypoint.GROUP_DEFAULT);
        int count = Math.min(entries.size(), MAX_WAYPOINTS_PER_SHARE);
        buf.writeShort(count);
        for (int i = 0; i < count; i++) {
            SharedEntry e = entries.get(i);
            ByteBufUtils.writeUTF8String(buf, e.name != null ? e.name : "Waypoint");
            buf.writeInt(e.x);
            buf.writeInt(e.y);
            buf.writeInt(e.z);
            buf.writeInt(e.dim);
            buf.writeInt(e.color & 0xFFFFFF);
            buf.writeByte(e.iconOrdinal & 0xFF);
        }
    }

    public static class C2SHandler implements IMessageHandler<C2S, IMessage> {
        @Override
        public IMessage onMessage(C2S msg, MessageContext ctx) {
            return Handler.handle(msg, ctx);
        }
    }

    public static class S2CHandler implements IMessageHandler<S2C, IMessage> {
        @Override
        public IMessage onMessage(S2C msg, MessageContext ctx) {
            return Handler.handle(msg, ctx);
        }
    }

    public static class Handler {
        public static IMessage handle(PacketShareWaypointGroup msg, MessageContext ctx) {
            if (ctx.side == Side.SERVER) {
                EntityPlayerMP sender = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
                if (sender == null || msg.entries == null || msg.entries.isEmpty()) return null;

                long now = System.currentTimeMillis();
                Long prev = LAST_SHARE_BY_PLAYER.get(sender.getUniqueID());
                if (prev != null && (now - prev) < RATE_LIMIT_MS) {
                    return null;
                }
                if (LAST_SHARE_BY_PLAYER.size() > 256) {
                    LAST_SHARE_BY_PLAYER.values().removeIf(t -> (now - t) > 60_000L);
                }
                LAST_SHARE_BY_PLAYER.put(sender.getUniqueID(), now);

                String realName = sender.getCommandSenderName();
                String safeGroup = Waypoint.normalizeGroup(msg.groupName, Waypoint.Type.NORMAL);
                PacketShareWaypointGroup.S2C broadcast = new PacketShareWaypointGroup.S2C(realName, safeGroup, msg.entries);

                MinecraftServer server = MinecraftServer.getServer();
                if (server != null && server.getConfigurationManager() != null) {
                    @SuppressWarnings("unchecked")
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP target : players) {
                        if (target != null) {
                            try {
                                MapNetwork.INSTANCE.sendTo(broadcast, target);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                return null;
            }

            if (SaifunsMapMod.proxy instanceof ClientProxy) {
                final ClientProxy client = (ClientProxy) SaifunsMapMod.proxy;
                Minecraft.getMinecraft().func_152344_a(() -> {
                    if (client.getWaypointManager() != null) {
                        client.getWaypointManager().onReceivedSharedGroup(msg.senderName, msg.groupName, msg.entries);
                    }
                });
            }
            return null;
        }
    }
}
