package com.yxy.inaction;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.EventExecutorGroup;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;

public class EchoServer {

    public final int port;

    public EchoServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        EchoServer server = new EchoServer(8080);
        server.start();
    }

    public void start() throws Exception {
        final EchoServerHandler serverHandler = new EchoServerHandler();
        final EchoServerHandler2 serverHandler2 = new EchoServerHandler2();


        ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("MyThread-" + Thread.currentThread().getName());
                return thread;
            }
        };
        NioEventLoopGroup group = new NioEventLoopGroup();
        NioEventLoopGroup eventExecutorGroup = new NioEventLoopGroup(threadFactory);
        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(group)
                    .channel(NioServerSocketChannel.class)
                    .localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(serverHandler);
                            pipeline.addLast(serverHandler2);
                            //pipeline.addLast(eventExecutorGroup,new EchoServerBlockingHandler());
                        }
                    });
            ChannelFuture future = serverBootstrap.bind().sync();
            future.channel().closeFuture().sync();
        }catch (Exception e) {
            e.printStackTrace();
        }finally {
            group.shutdownGracefully().sync();
        }
    }
}
