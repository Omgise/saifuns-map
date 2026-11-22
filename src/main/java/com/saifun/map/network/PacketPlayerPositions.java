package com.saifun.map.network;

import com.saifun.map.ClientProxy;
import com.saifun.map.SaifunsMapMod;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Optional server-to-client packet carrying long-range player positions in the same dimension.
 * Never required on vanilla/third-party servers; client radar always works locally regardless.
 */
public class PacketPlayerPositions implements IMessage {

    public static class PlayerPos {
        public final UUID uuid;
        public final String name;
        public final double x;
        public final double y;
        public final double z;
        public final float yaw;

        public PlayerPos(UUID uuid, String name, double x, double y, double z, float yaw) {
            this.uuid = uuid;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    public int dimensionId;
    public List<PlayerPos> players = new ArrayList<>();

    public PacketPlayerPositions() {}

    public PacketPlayerPositions(int dimensionId, List<PlayerPos> players) {
        this.dimensionId = dimensionId;
        this.players = players;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.dimensionId = buf.readInt();
        int count = Math.min(buf.readInt(), 1024);
        this.players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long most = buf.readLong();
            long least = buf.readLong();
            String name = ByteBufUtils.readUTF8String(buf);
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float yaw = buf.readFloat();
            this.players.add(new PlayerPos(new UUID(most, least), name, x, y, z, yaw));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.dimensionId);
        buf.writeInt(this.players.size());
        for (PlayerPos p : this.players) {
            buf.writeLong(p.uuid.getMostSignificantBits());
            buf.writeLong(p.uuid.getLeastSignificantBits());
            ByteBufUtils.writeUTF8String(buf, p.name);
            buf.writeDouble(p.x);
            buf.writeDouble(p.y);
            buf.writeDouble(p.z);
            buf.writeFloat(p.yaw);
        }
    }

    public static class Handler implements IMessageHandler<PacketPlayerPositions, IMessage> {
        @Override
        public IMessage onMessage(PacketPlayerPositions message, MessageContext ctx) {
            if (SaifunsMapMod.proxy instanceof ClientProxy) {
                ClientProxy client = (ClientProxy) SaifunsMapMod.proxy;
                if (client.getRadarManager() != null) {
                    client.getRadarManager().updateRemotePlayers(message.dimensionId, message.players);
                }
            }
            return null;
        }
    }
}
