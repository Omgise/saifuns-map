package com.saifun.map.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Sent from server to client on login when Saifun's Map is installed server-side.
 * Unlocks hybrid server-assisted features (long-range teammate radar, 3D pings, 1-click group sharing)
 * while keeping the mod 100% optional on vanilla / third-party servers.
 */
public class PacketServerHello implements IMessage {

    public int protocolVersion = 1;

    public PacketServerHello() {}

    public PacketServerHello(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.protocolVersion = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.protocolVersion);
    }

    public static class Handler implements IMessageHandler<PacketServerHello, IMessage> {
        @Override
        public IMessage onMessage(PacketServerHello message, MessageContext ctx) {
            MapNetwork.setServerModPresent(true);
            return null;
        }
    }
}
