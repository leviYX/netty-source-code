package com.yxy.completeblefuture;

import com.yxy.utils.CommonUtils;

public class TestCommonUtils {
    public static void main(String[] args) {
        String readFile = CommonUtils.readFile("/Users/levi/develop/source_code/netty/netty-source-code/my-module/src/main/resources/test.txt");
        CommonUtils.printThreadLog(readFile);
    }
}
