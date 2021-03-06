package net.minecraft.server;

import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.timeout.TimeoutException;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.net.SocketAddress;
import java.util.Queue;
import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class NetworkManager extends SimpleChannelInboundHandler<Packet<?>> {

    private static final Logger LOGGER = LogManager.getLogger();
    public static final Marker a = MarkerManager.getMarker("NETWORK");
    public static final Marker b = MarkerManager.getMarker("NETWORK_PACKETS", NetworkManager.a);
    public static final AttributeKey<EnumProtocol> c = AttributeKey.valueOf("protocol");
    public static final LazyInitVar<NioEventLoopGroup> d = new LazyInitVar<>(() -> {
        return new NioEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Client IO #%d").setDaemon(true).build());
    });
    public static final LazyInitVar<EpollEventLoopGroup> e = new LazyInitVar<>(() -> {
        return new EpollEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Epoll Client IO #%d").setDaemon(true).build());
    });
    public static final LazyInitVar<DefaultEventLoopGroup> f = new LazyInitVar<>(() -> {
        return new DefaultEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Local Client IO #%d").setDaemon(true).build());
    });
    private final EnumProtocolDirection h;
    private final Queue<NetworkManager.QueuedPacket> packetQueue = Queues.newConcurrentLinkedQueue(); private final Queue<NetworkManager.QueuedPacket> getPacketQueue() { return this.packetQueue; } // Paper - OBFHELPER
    public Channel channel;
    public SocketAddress socketAddress; public void setSpoofedRemoteAddress(SocketAddress address) { this.socketAddress = address; } // Paper - OBFHELPER
    // Spigot Start
    public java.util.UUID spoofedUUID;
    public com.mojang.authlib.properties.Property[] spoofedProfile;
    public boolean preparing = true;
    // Spigot End
    private PacketListener packetListener;
    private IChatBaseComponent m;
    private boolean n;
    private boolean o;
    private int p;
    private int q;
    private float r;
    private float s;
    private int t;
    private boolean u;
    // Paper start - NetworkClient implementation
    public int protocolVersion;
    public java.net.InetSocketAddress virtualHost;
    private static boolean enableExplicitFlush = Boolean.getBoolean("paper.explicit-flush");
    // Paper end

    public NetworkManager(EnumProtocolDirection enumprotocoldirection) {
        this.h = enumprotocoldirection;
    }

    public void channelActive(ChannelHandlerContext channelhandlercontext) throws Exception {
        super.channelActive(channelhandlercontext);
        this.channel = channelhandlercontext.channel();
        this.socketAddress = this.channel.remoteAddress();
        // Spigot Start
        this.preparing = false;
        // Spigot End

        try {
            this.setProtocol(EnumProtocol.HANDSHAKING);
        } catch (Throwable throwable) {
            NetworkManager.LOGGER.fatal(throwable);
        }

    }

    public void setProtocol(EnumProtocol enumprotocol) {
        this.channel.attr(NetworkManager.c).set(enumprotocol);
        this.channel.config().setAutoRead(true);
        NetworkManager.LOGGER.debug("Enabled auto read");
    }

    public void channelInactive(ChannelHandlerContext channelhandlercontext) throws Exception {
        this.close(new ChatMessage("disconnect.endOfStream", new Object[0]));
    }

    public void exceptionCaught(ChannelHandlerContext channelhandlercontext, Throwable throwable) {
        // Paper start
        if (throwable instanceof io.netty.handler.codec.EncoderException && throwable.getCause() instanceof PacketEncoder.PacketTooLargeException) {
            if (((PacketEncoder.PacketTooLargeException) throwable.getCause()).getPacket().packetTooLarge(this)) {
                return;
            } else {
                throwable = throwable.getCause();
            }
        }
        // Paper end
        if (throwable instanceof SkipEncodeException) {
            NetworkManager.LOGGER.debug("Skipping packet due to errors", throwable.getCause());
        } else {
            boolean flag = !this.u;

            this.u = true;
            if (this.channel.isOpen()) {
                if (throwable instanceof TimeoutException) {
                    NetworkManager.LOGGER.debug("Timeout", throwable);
                    this.close(new ChatMessage("disconnect.timeout", new Object[0]));
                } else {
                    ChatMessage chatmessage = new ChatMessage("disconnect.genericReason", new Object[]{"Internal Exception: " + throwable});

                    if (flag) {
                        NetworkManager.LOGGER.debug("Failed to sent packet", throwable);
                        this.sendPacket(new PacketPlayOutKickDisconnect(chatmessage), (future) -> {
                            this.close(chatmessage);
                        });
                        this.stopReading();
                    } else {
                        NetworkManager.LOGGER.debug("Double fault", throwable);
                        this.close(chatmessage);
                    }
                }

            }
        }
        if (MinecraftServer.getServer().isDebugging()) throwable.printStackTrace(); // Spigot
    }

    protected void channelRead0(ChannelHandlerContext channelhandlercontext, Packet<?> packet) throws Exception {
        if (this.channel.isOpen()) {
            try {
                a(packet, this.packetListener);
            } catch (CancelledPacketHandleException cancelledpackethandleexception) {
                ;
            }

            ++this.p;
        }

    }

    private static <T extends PacketListener> void a(Packet<T> packet, PacketListener packetlistener) {
        packet.a((T) packetlistener); // CraftBukkit - decompile error
    }

    public void setPacketListener(PacketListener packetlistener) {
        Validate.notNull(packetlistener, "packetListener", new Object[0]);
        NetworkManager.LOGGER.debug("Set listener of {} to {}", this, packetlistener);
        this.packetListener = packetlistener;
    }

    public void sendPacket(Packet<?> packet) {
        this.sendPacket(packet, (GenericFutureListener) null);
    }

    public void sendPacket(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> genericfuturelistener) {
        if (this.isConnected() && this.sendPacketQueue() && !(packet instanceof PacketPlayOutMapChunk && !((PacketPlayOutMapChunk) packet).isReady())) { // Paper - Async-Anti-Xray - Add chunk packets which are not ready or all packets if the packet queue contains chunk packets which are not ready to the packet queue and send the packets later in the right order
            //this.o(); // Paper - Async-Anti-Xray - Move to if statement (this.sendPacketQueue())
            this.b(packet, genericfuturelistener);
        } else {
            this.packetQueue.add(new NetworkManager.QueuedPacket(packet, genericfuturelistener));
        }

    }

    private void dispatchPacket(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> genericFutureListener) { this.b(packet, genericFutureListener); } // Paper - OBFHELPER
    private void b(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> genericfuturelistener) {
        EnumProtocol enumprotocol = EnumProtocol.a(packet);
        EnumProtocol enumprotocol1 = (EnumProtocol) this.channel.attr(NetworkManager.c).get();

        ++this.q;
        if (enumprotocol1 != enumprotocol) {
            NetworkManager.LOGGER.debug("Disabled auto read");
            this.channel.config().setAutoRead(false);
        }

        if (this.channel.eventLoop().inEventLoop()) {
            if (enumprotocol != enumprotocol1) {
                this.setProtocol(enumprotocol);
            }

            ChannelFuture channelfuture = this.channel.writeAndFlush(packet);

            if (genericfuturelistener != null) {
                channelfuture.addListener(genericfuturelistener);
            }

            channelfuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        } else {
            this.channel.eventLoop().execute(() -> {
                if (enumprotocol != enumprotocol1) {
                    this.setProtocol(enumprotocol);
                }

                ChannelFuture channelfuture1 = this.channel.writeAndFlush(packet);

                if (genericfuturelistener != null) {
                    channelfuture1.addListener(genericfuturelistener);
                }

                channelfuture1.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            });
        }

        // Paper start
        java.util.List<Packet> extraPackets = packet.getExtraPackets();
        if (extraPackets != null && !extraPackets.isEmpty()) {
            for (Packet extraPacket : extraPackets) {
                this.dispatchPacket(extraPacket, genericfuturelistener);
            }
        }
        // Paper end

    }

    // Paper start - Async-Anti-Xray - Stop dispatching further packets and return false if the peeked packet is a chunk packet which is not ready
    private boolean sendPacketQueue() { return this.o(); } // OBFHELPER // void -> boolean
    private boolean o() { // void -> boolean
        if (this.channel != null && this.channel.isOpen()) {
            Queue queue = this.packetQueue;

            synchronized (this.packetQueue) {
                while (!this.packetQueue.isEmpty()) {
                    NetworkManager.QueuedPacket networkmanager_queuedpacket = (NetworkManager.QueuedPacket) this.getPacketQueue().peek(); // poll -> peek

                    if (networkmanager_queuedpacket != null) { // Fix NPE (Spigot bug caused by handleDisconnection())
                        if (networkmanager_queuedpacket.getPacket() instanceof PacketPlayOutMapChunk && !((PacketPlayOutMapChunk) networkmanager_queuedpacket.getPacket()).isReady()) { // Check if the peeked packet is a chunk packet which is not ready
                            return false; // Return false if the peeked packet is a chunk packet which is not ready
                        } else {
                            this.getPacketQueue().poll(); // poll here
                            this.dispatchPacket(networkmanager_queuedpacket.getPacket(), networkmanager_queuedpacket.getGenericFutureListener()); // dispatch the packet
                        }
                    }
                }

            }
        }

        return true; // Return true if all packets were dispatched
    }
    // Paper end

    public void a() {
        this.o();
        if (this.packetListener instanceof LoginListener) {
            ((LoginListener) this.packetListener).tick();
        }

        if (this.packetListener instanceof PlayerConnection) {
            ((PlayerConnection) this.packetListener).tick();
        }

        if (this.channel != null) {
            if (enableExplicitFlush) this.channel.eventLoop().execute(() -> this.channel.flush()); // Paper - we don't need to explicit flush here, but allow opt in incase issues are found to a better version
        }

        if (this.t++ % 20 == 0) {
            this.s = this.s * 0.75F + (float) this.q * 0.25F;
            this.r = this.r * 0.75F + (float) this.p * 0.25F;
            this.q = 0;
            this.p = 0;
        }

    }

    public SocketAddress getSocketAddress() {
        return this.socketAddress;
    }

    public void close(IChatBaseComponent ichatbasecomponent) {
        // Spigot Start
        this.preparing = false;
        // Spigot End
        if (this.channel.isOpen()) {
            this.channel.close(); // We can't wait as this may be called from an event loop.
            this.m = ichatbasecomponent;
        }

    }

    public boolean isLocal() {
        return this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel;
    }

    public void a(SecretKey secretkey) {
        this.n = true;
        this.channel.pipeline().addBefore("splitter", "decrypt", new PacketDecrypter(MinecraftEncryption.a(2, secretkey)));
        this.channel.pipeline().addBefore("prepender", "encrypt", new PacketEncrypter(MinecraftEncryption.a(1, secretkey)));
    }

    public boolean isConnected() {
        return this.channel != null && this.channel.isOpen();
    }

    public boolean h() {
        return this.channel == null;
    }

    public PacketListener i() {
        return this.packetListener;
    }

    @Nullable
    public IChatBaseComponent j() {
        return this.m;
    }

    public void stopReading() {
        this.channel.config().setAutoRead(false);
    }

    public void setCompressionLevel(int i) {
        if (i >= 0) {
            if (this.channel.pipeline().get("decompress") instanceof PacketDecompressor) {
                ((PacketDecompressor) this.channel.pipeline().get("decompress")).a(i);
            } else {
                this.channel.pipeline().addBefore("decoder", "decompress", new PacketDecompressor(i));
            }

            if (this.channel.pipeline().get("compress") instanceof PacketCompressor) {
                ((PacketCompressor) this.channel.pipeline().get("compress")).a(i);
            } else {
                this.channel.pipeline().addBefore("encoder", "compress", new PacketCompressor(i));
            }
        } else {
            if (this.channel.pipeline().get("decompress") instanceof PacketDecompressor) {
                this.channel.pipeline().remove("decompress");
            }

            if (this.channel.pipeline().get("compress") instanceof PacketCompressor) {
                this.channel.pipeline().remove("compress");
            }
        }

    }

    public void handleDisconnection() {
        if (this.channel != null && !this.channel.isOpen()) {
            if (this.o) {
                NetworkManager.LOGGER.warn("handleDisconnection() called twice");
            } else {
                this.o = true;
                if (this.j() != null) {
                    this.i().a(this.j());
                } else if (this.i() != null) {
                    this.i().a(new ChatMessage("multiplayer.disconnect.generic", new Object[0]));
                }
                this.packetQueue.clear(); // Free up packet queue.
                // Paper start - Add PlayerConnectionCloseEvent
                final PacketListener packetListener = this.i();
                if (packetListener instanceof PlayerConnection) {
                    /* Player was logged in */
                    final PlayerConnection playerConnection = (PlayerConnection) packetListener;
                    new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(playerConnection.player.uniqueID,
                        playerConnection.player.getName(), ((java.net.InetSocketAddress)socketAddress).getAddress(), false).callEvent();
                } else if (packetListener instanceof LoginListener) {
                    /* Player is login stage */
                    final LoginListener loginListener = (LoginListener) packetListener;
                    switch (loginListener.getLoginState()) {
                        case READY_TO_ACCEPT:
                        case DELAY_ACCEPT:
                        case ACCEPTED:
                            final com.mojang.authlib.GameProfile profile = loginListener.getGameProfile(); /* Should be non-null at this stage */
                            new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(profile.getId(), profile.getName(),
                                ((java.net.InetSocketAddress)socketAddress).getAddress(), false).callEvent();
                    }
                }
                // Paper end
            }

        }
    }

    static class QueuedPacket {

        private final Packet<?> a; private final Packet<?> getPacket() { return this.a; } // Paper - OBFHELPER
        @Nullable
        private final GenericFutureListener<? extends Future<? super Void>> b; private final GenericFutureListener<? extends Future<? super Void>> getGenericFutureListener() { return this.b; } // Paper - OBFHELPER

        public QueuedPacket(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> genericfuturelistener) {
            this.a = packet;
            this.b = genericfuturelistener;
        }
    }

    // Spigot Start
    public SocketAddress getRawAddress()
    {
        return this.channel.remoteAddress();
    }
    // Spigot End
}
