package com.example.plandeduce.config;

public final class DynamicDataSourceContextHolder {
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<String>();

    private DynamicDataSourceContextHolder() {
    }

    public static void set(String key) {
        CONTEXT.set(key);
    }

    public static String get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
