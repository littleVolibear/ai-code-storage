package com.example.plandeduce.config;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class DynamicDataSourceContextHolder {
    private static final ThreadLocal<Deque<String>> CONTEXT = new ThreadLocal<>();

    private DynamicDataSourceContextHolder() {
    }

    public static String get() {
        Deque<String> dataSourceStack = CONTEXT.get();
        return dataSourceStack == null ? null : dataSourceStack.peek();
    }

    /** 切换到指定数据源，并保留进入前的数据源。 */
    public static void set(String key) {
        Objects.requireNonNull(key, "dataSourceKey 不能为空");
        Deque<String> dataSourceStack = CONTEXT.get();
        if (dataSourceStack == null) {
            dataSourceStack = new ArrayDeque<>();
            CONTEXT.set(dataSourceStack);
        }
        dataSourceStack.push(key);
    }

    /** 清除当前层数据源，并恢复进入当前层前的数据源。 */
    public static void clear() {
        Deque<String> dataSourceStack = CONTEXT.get();
        if (dataSourceStack == null) {
            return;
        }
        if (!dataSourceStack.isEmpty()) {
            dataSourceStack.pop();
        }
        if (dataSourceStack.isEmpty()) {
            CONTEXT.remove();
        }
    }
}
