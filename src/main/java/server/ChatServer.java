package server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;


/**
 * Created by fengyuce on 2017/5/1.
 */
public class ChatServer {
    private ChannelGroup bcast = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private Map<ChannelId , String> clients = new ConcurrentHashMap<ChannelId, String>();

    private int port = 0;

    public ChatServer(int _port) {
        this.port = _port;
    }

    public static void main(String[] args) throws Exception {
        new ChatServer(9999).start();
    }

    public void start() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group).channel(NioServerSocketChannel.class).localAddress(port)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(65536));
                            ch.pipeline().addLast(new WebSocketServerCompressionHandler());
                            ch.pipeline().addLast(new SimpleChannelInboundHandler() {
                                private WebSocketServerHandshaker handShaker;

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof FullHttpRequest) {
                                        FullHttpRequest fullHttpRequest = (FullHttpRequest) msg;
                                        bcast.add(ctx.channel());
                                        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(fullHttpRequest), null, true);
                                        handShaker = wsFactory.newHandshaker(fullHttpRequest);
                                        ChannelFuture channelFuture = handShaker.handshake(ctx.channel(), fullHttpRequest);
                                        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(fullHttpRequest.uri());
                                        Map<String, List<String>> parameters = queryStringDecoder.parameters();
                                        String clientName = parameters.get("name").get(0);
                                        if (channelFuture.isSuccess()) {
                                            add2Map(ctx.channel().id(),clientName);
                                            bcast.writeAndFlush(new TextWebSocketFrame(clientName+"加入房间，当前在线：" +clients.values()));
                                        }
                                    } else if (msg instanceof WebSocketFrame) {
                                        String request = ((TextWebSocketFrame) msg).text();
                                        String clientName = clients.get(ctx.channel().id());
                                        bcast.writeAndFlush(new TextWebSocketFrame(Instant.now().toString() +" "+clientName+" 说：" +request));
                                    }
                                }

                                private void add2Map(ChannelId channelId ,String clientName){
                                    synchronized (Object.class){
                                        clients.put(channelId,clientName);
                                    }
                                }

                                private String getWebSocketLocation(FullHttpRequest req) {
                                    String location = req.headers().get(HOST) + "/websocket";
                                    return "ws://" + location;
                                }
                            });
                        }
                    });
            ChannelFuture f = b.bind().sync();
            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully().sync();
        }
    }
}
