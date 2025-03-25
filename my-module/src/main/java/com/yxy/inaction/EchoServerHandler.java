package com.yxy.inaction;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.CharsetUtil;
@ChannelHandler.Sharable
public class EchoServerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        System.out.println("SimpleChannelInboundHandler EchoServerHandler received data" + msg.toString(CharsetUtil.UTF_8) + Thread.currentThread());
        ctx.fireChannelRead(msg);
        // super.channelRead(ctx, msg);
    }
}
