package com.yxy.pool;

import io.netty.buffer.ByteBufAllocator;

public class Test {
    public static void main(String[] args) {
        ByteBufAllocator.DEFAULT.buffer(12);
    }
}
