package com.yxy.completeblefuture;

import com.yxy.utils.CommonUtils;
import io.netty.util.concurrent.CompleteFuture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class RunAsyncDemo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        CompletableFuture<Void> runAsyncFuture = CompletableFuture.runAsync(() -> {
            CommonUtils.printThreadLog("read file start");
            CommonUtils.sleepSecond(4);
            CommonUtils.printThreadLog("read file end");
        });
        Void unused = runAsyncFuture.get();
        Void unused1 = unused;
        System.out.println(unused1);
    }
}
