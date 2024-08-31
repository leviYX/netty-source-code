package com.yxy.tl;

import java.util.HashMap;
import java.util.Map;

public class Test1 {
    public static ThreadLocal<Object> tl = new ThreadLocal<>();

    public static void main(String[] args) {

        new Thread(()->{
            tl.set("levi1");
            System.out.println(tl.get());
        },"t1").start();

        new Thread(()->{
            tl.set("levi2");
            System.out.println(tl.get());
        },"t2").start();

        System.out.println("**************");
    }
}
