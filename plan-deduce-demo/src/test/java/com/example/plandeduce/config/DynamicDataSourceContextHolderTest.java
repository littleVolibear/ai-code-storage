package com.example.plandeduce.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamicDataSourceContextHolderTest {

    @AfterEach
    void clearContext() {
        while (DynamicDataSourceContextHolder.get() != null) {
            DynamicDataSourceContextHolder.clear();
        }
    }

    @Test
    void shouldRestoreOuterDataSourceAfterNestedClear() {
        assertNull(DynamicDataSourceContextHolder.get());

        DynamicDataSourceContextHolder.set("outer");
        assertEquals("outer", DynamicDataSourceContextHolder.get());

        DynamicDataSourceContextHolder.set("inner");
        assertEquals("inner", DynamicDataSourceContextHolder.get());

        DynamicDataSourceContextHolder.clear();
        assertEquals("outer", DynamicDataSourceContextHolder.get());

        DynamicDataSourceContextHolder.clear();
        assertNull(DynamicDataSourceContextHolder.get());
    }

    @Test
    void shouldRestoreOuterDataSourceWhenSameKeyIsNested() {
        DynamicDataSourceContextHolder.set("same");
        DynamicDataSourceContextHolder.set("same");

        DynamicDataSourceContextHolder.clear();
        assertEquals("same", DynamicDataSourceContextHolder.get());

        DynamicDataSourceContextHolder.clear();
        assertNull(DynamicDataSourceContextHolder.get());
    }
}
