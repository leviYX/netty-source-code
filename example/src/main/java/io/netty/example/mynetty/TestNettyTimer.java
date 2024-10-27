package io.netty.example.mynetty;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

import java.util.concurrent.TimeUnit;


public class TestNettyTimer {
    public static void main(String[] args) {
        // 创建哈希时间轮
        HashedWheelTimer timer = new HashedWheelTimer(1, TimeUnit.SECONDS,20);
        // 添加一个延迟50秒的任务，就是从当前添加进去开始计时，50秒后执行
        timer.newTimeout(new TimerTask(){
            @Override
            public void run(Timeout timeout) {
                System.out.println(" timeout ");
            }
        }, 50, TimeUnit.SECONDS);


    }
}
