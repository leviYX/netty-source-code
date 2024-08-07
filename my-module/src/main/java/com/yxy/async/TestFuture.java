package com.yxy.async;

import java.util.concurrent.*;

public class TestFuture {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        long startTime = System.nanoTime();
        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        Future<Integer> future = threadPool.submit(new Callable<Integer>(){
            @Override
            public Integer call() throws Exception {
                TimeUnit.SECONDS.sleep(10);
                return 1;
            }
        });
        Integer result = future.get();

        System.out.println("结果是：" + result + "。耗时为:" + (System.nanoTime() - startTime) / 1000000000 + "秒");
    }
}
