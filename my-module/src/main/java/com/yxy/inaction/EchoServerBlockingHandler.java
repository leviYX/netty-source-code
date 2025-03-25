package com.yxy.inaction;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;

@ChannelHandler.Sharable
public class EchoServerBlockingHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println("*******************" + Thread.currentThread());
    }
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        //ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        boolean writable = ctx.channel().isWritable();
        if (writable) {
            System.out.println("channel is writable");
        }else {
            System.out.println("channel is not writable");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
