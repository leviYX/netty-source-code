package com.yxy.utils;

import java.beans.IntrospectionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public class CommonUtils {

    public static String readFile(String pathToFile){
        try {
            return Files.readString(Paths.get(pathToFile));
        }catch (Exception e){
            e.printStackTrace();
            return "";
        }
    }

    public static void sleepMillis(long millis){
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        }catch (InterruptedException e){
            e.printStackTrace();
        }
    }

    public static void sleepSecond(int seconds){
        try {
            TimeUnit.SECONDS.sleep(seconds);
        }catch (InterruptedException e){
            e.printStackTrace();
        }
    }

    public static void printThreadLog(String message){
        String result = new StringJoiner(" | ")
                .add(String.valueOf(System.currentTimeMillis()))
                .add(String.format("%2d",Thread.currentThread().getId()))
                .add(String.valueOf(Thread.currentThread().getName()))
                .add(message)
                .toString();
        System.out.println(result);
    }
}
