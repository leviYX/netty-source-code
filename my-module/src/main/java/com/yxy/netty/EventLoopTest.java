package com.yxy.netty;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

public class EventLoopTest {
    public static void main(String[] args) {
        EventLoopGroup eventLoopGroup = new DefaultEventLoopGroup();
        EventLoop eventLoop = eventLoopGroup.next();
        // 使用eventLoop提交一个异步任务
        eventLoop.execute(new Runnable() {
            @Override
            public void run() {
                System.out.println("hello world");
            }
        });
    }
}
