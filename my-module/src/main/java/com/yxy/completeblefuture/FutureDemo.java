package com.yxy.completeblefuture;

import com.yxy.utils.CommonUtils;

import java.util.concurrent.*;

public class FutureDemo {

    private static final String prefix = "/Users/levi/develop/source_code/netty/netty-source-code/my-module/src/main/resources/";

    public static void main(String[] args) throws Exception{
        ExecutorService threadPool = Executors.newFixedThreadPool(5);

        // 读取敏感词汇
        Future<String[]> filterWordFuture = threadPool.submit(() -> {
            String filterContent = CommonUtils.readFile(prefix + "filter.txt");
            return filterContent.split(",");
        });

        // 读取新闻文件
        Future<String> newsWordFuture = threadPool.submit(()->{
            return CommonUtils.readFile(prefix + "news.txt");
        });

        // 新闻文件内容过滤敏感词
        Future<String> replaceFuture = threadPool.submit(() -> {
            try {
                String[] filterWords = filterWordFuture.get();
                String newsWords = newsWordFuture.get();
                for (String filterWord : filterWords) {
                    if (newsWords.contains(filterWord)) {
                        newsWords = newsWords.replace(filterWord, "**");
                    }
                }
                return newsWords;
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
        String result = replaceFuture.get();
        CommonUtils.printThreadLog(result);
    }
}
