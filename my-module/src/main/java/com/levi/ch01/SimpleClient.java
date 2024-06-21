package com.levi.ch01;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * @Description:
 * @Author: Levi
 * @Date: 2024/6/21 19:22
 */

@Slf4j
public class SimpleClient {
    public static void main(String[] args) throws IOException, InterruptedException {
        final Logger logger = LoggerFactory.getLogger(SimpleClient.class);

        //得到客户端的channel
        SocketChannel socketChannel = SocketChannel.open();
        //设置非阻塞
        socketChannel.configureBlocking(false);
        //得到selector
        Selector selector = Selector.open();
        //把客户端的channel注册到selector上
        SelectionKey selectionKey = socketChannel.register(selector, 0);
        //设置事件
        selectionKey.interestOps(SelectionKey.OP_CONNECT);
        //客户端的channel去连接服务器
        socketChannel.connect(new InetSocketAddress(8080));
        //开始轮询事件
        while (true) {
            //无事件则阻塞
            selector.select();
            //得到事件的key
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();
                //如果是连接成功事件
                if (key.isConnectable()) {
                    if (socketChannel.finishConnect()) {
                        socketChannel.register(selector,SelectionKey.OP_READ);
                        logger.info("已经注册了读事件！");
                        //紧接着向服务端发送一条消息
                        socketChannel.write(ByteBuffer.wrap("客户端发送成功了".getBytes()));
                    }
                }
                //如果是读事件
                if (key.isReadable()) {
                    SocketChannel channel = (SocketChannel)key.channel();
                    //分配字节缓冲区来接受服务端传过来的数据
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    //向buffer写入客户端传来的数据
                    int len = channel.read(buffer);
                    byte[] readByte = new byte[len];
                    buffer.flip();
                    buffer.get(readByte);
                    logger.info("读到来自服务端的数据：" + new String(readByte));
                }
            }
        }
    }
}
