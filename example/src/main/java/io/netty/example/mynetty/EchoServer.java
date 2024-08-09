package io.netty.example.mynetty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.example.echo.EchoServerHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public final class EchoServer {
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {

        //创建主从Reactor线程组
        /**
         * Main Reactor Group中的Reactor数量取决于服务端要监听的端口个数，通常我们的服务端程序只会监听一个端口，
         * 所以Main Reactor Group只会有一个Main Reactor线程来处理最重要的事情：绑定端口地址，
         * 接收客户端连接，为客户端创建对应的SocketChannel，将客户端SocketChannel分配给一个固定的Sub Reactor。
         */
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        /**
         * Sub Reactor Group里有多个Reactor线程，Reactor线程的个数可以通过系统参数-D io.netty.eventLoopThreads指定。
         * 默认的Reactor的个数为CPU核数 * 2。Sub Reactor线程主要用来轮询客户端SocketChannel上的IO就绪事件，处理IO就绪事件，执行异步任务。
         *
         * 一个客户端SocketChannel只能分配给一个固定的Sub Reactor。一个Sub Reactor负责处理多个客户端SocketChannel，
         * 这样可以将服务端承载的全量客户端连接分摊到多个Sub Reactor中处理，同时也能保证客户端SocketChannel上的IO处理的线程安全性。
         */
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)//配置主从Reactor
                    // NSSC封装的就是底层创建一个Socket用于listen和bind端口地址，我们把这个叫做监听Socket,
                    .channel(NioServerSocketChannel.class)//配置主Reactor中的channel类型，后面我把他简称为NSSC
                    .option(ChannelOption.SO_BACKLOG, 100)//设置主Reactor中channel的option选项，SO_BACKLOG主要用于设置全连接队列的大小
                    .handler(new LoggingHandler(LogLevel.INFO))//设置主Reactor中Channel->pipline->handler
                    .childHandler(new ChannelInitializer<SocketChannel>() {//设置从Reactor中注册channel的pipeline
                        /**
                         * netty有两种Channel类型：一种是服务端用于监听绑定端口地址的NioServerSocketChannel,一种是用于客户端通信的NioSocketChannel。
                         * 每种Channel类型实例都会对应一个PipeLine用于编排对应channel实例上的IO事件处理逻辑。
                         * PipeLine中组织的就是ChannelHandler用于编写特定的IO处理逻辑。
                         */
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            //p.addLast(new LoggingHandler(LogLevel.INFO));
                            p.addLast(serverHandler);
                        }
                    });

            // Start the server. 绑定端口启动服务，开始监听accept事件
            ChannelFuture future = b.bind(PORT).sync();
            // Wait until the server socket is closed.阻塞主线程
            future.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads. 优雅退出
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}