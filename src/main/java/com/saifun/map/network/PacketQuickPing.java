package com.saifun.map.network;

import com.saifun.map.ClientProxy;
import com.saifun.map.SaifunsMapMod;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional 3D Quick Ping packet (C2S -> server validates & rate-limits -> S2C broadcast to dimension).
 */
public class PacketQuickPing implements IMessage {

    public static final long PING_DURATION_MS = 15_000L;
    private static final long RATE_LIMIT_MS = 450L;
    private static final Map<UUID, Long> LAST_PING_BY_PLAYER = new ConcurrentHashMap<>();

    public String senderName = "";
    public String label = "";
    public int x;
    public int y;
    public int z;
    public int dimensionId;
    public int color = 0x38BDF8;

    public PacketQuickPing() {}

    public PacketQuickPing(String senderName, String label, int x, int y, int z, int dimensionId, int color) {
        this.senderName = senderName != null ? senderName : "";
        this.label = label != null ? label : "";
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
        this.color = color & 0xFFFFFF;
    }

    /** Client -> Server quick ping request (unique discriminator for Forge 1.7.10 SimpleIndexedCodec). */
    public static final class C2S extends PacketQuickPing {
        public C2S() {}
        public C2S(String senderName, String label, int x, int y, int z, int dimensionId, int color) {
            super(senderName, label, x, y, z, dimensionId, color);
        }
    }

    /** Server -> Client quick ping broadcast (unique discriminator for Forge 1.7.10 SimpleIndexedCodec). */
    public static final class S2C extends PacketQuickPing {
        public S2C() {}
        public S2C(String senderName, String label, int x, int y, int z, int dimensionId, int color) {
            super(senderName, label, x, y, z, dimensionId, color);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.senderName = ByteBufUtils.readUTF8String(buf);
        this.label = ByteBufUtils.readUTF8String(buf);
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.dimensionId = buf.readInt();
        this.color = buf.readInt() & 0xFFFFFF;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, senderName != null ? senderName : "");
        ByteBufUtils.writeUTF8String(buf, label != null ? label : "");
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(dimensionId);
        buf.writeInt(color & 0xFFFFFF);
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
        public static IMessage handle(PacketQuickPing msg, MessageContext ctx) {
            if (ctx.side == Side.SERVER) {
                EntityPlayerMP sender = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
                if (sender == null) return null;

                long now = System.currentTimeMillis();
                Long prev = LAST_PING_BY_PLAYER.get(sender.getUniqueID());
                if (prev != null && (now - prev) < RATE_LIMIT_MS) {
                    return null;
                }
                if (LAST_PING_BY_PLAYER.size() > 256) {
                    LAST_PING_BY_PLAYER.values().removeIf(t -> (now - t) > 60_000L);
                }
                LAST_PING_BY_PLAYER.put(sender.getUniqueID(), now);

                String realName = sender.getCommandSenderName();
                String safeLabel = (msg.label != null && !msg.label.trim().isEmpty())
                    ? msg.label.trim()
                    : realName;
                if (safeLabel.length() > 36) safeLabel = safeLabel.substring(0, 36);

                PacketQuickPing.S2C broadcast = new PacketQuickPing.S2C(
                    realName,
                    safeLabel,
                    msg.x,
                    Math.max(0, Math.min(255, msg.y)),
                    msg.z,
                    sender.dimension,
                    msg.color
                );

                MinecraftServer server = MinecraftServer.getServer();
                if (server != null && server.getConfigurationManager() != null) {
                    @SuppressWarnings("unchecked")
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP target : players) {
                        if (target != null && target.dimension == sender.dimension) {
                            try {
                                MapNetwork.INSTANCE.sendTo(broadcast, target);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                return null;
            }

            // Client-side: render animated 3D ping + soft notification chime
            if (SaifunsMapMod.proxy instanceof ClientProxy) {
                final ClientProxy client = (ClientProxy) SaifunsMapMod.proxy;
                Minecraft.getMinecraft().func_152344_a(() -> {
                    if (client.getWaypointManager() != null) {
                        client.getWaypointManager().addTransientPing(
                            msg.senderName,
                            msg.label,
                            msg.x, msg.y, msg.z,
                            msg.dimensionId,
                            msg.color,
                            PING_DURATION_MS
                        );
                    }
                });
            }
            return null;
        }
    }
}
