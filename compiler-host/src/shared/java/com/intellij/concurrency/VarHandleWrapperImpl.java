package com.intellij.concurrency;

import com.intellij.util.containers.VarHandleWrapper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Single-threaded replacement for the VarHandle-based implementation: GraalVM Web Image has no threads
 * and cannot compile the generic VarHandle access paths, so plain reflective reads and writes are enough.
 */
@SuppressWarnings("unused")
public class VarHandleWrapperImpl extends VarHandleWrapper implements VarHandleWrapper.VarHandleWrapperFactory {
    private final Field myField;

    private VarHandleWrapperImpl(Field field) {
        myField = field;
    }

    @Override
    public VarHandleWrapper create(Class<?> containingClass, String name, Class<?> type) {
        try {
            Field field = containingClass.getDeclaredField(name);
            field.setAccessible(true);
            return new VarHandleWrapperImpl(field);
        }
        catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public VarHandleWrapper createForArrayElement(Class<?> arrayClass) {
        return new VarHandleWrapperImpl(null);
    }

    private Object get(Object thisObject) {
        try {
            return myField.get(thisObject);
        }
        catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void set(Object thisObject, Object value) {
        try {
            myField.set(thisObject, value);
        }
        catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // not abstract in older VarHandleWrapper versions
    public Object getVolatile(Object thisObject) {
        return get(thisObject);
    }

    // not abstract in older VarHandleWrapper versions
    public void setVolatile(Object thisObject, Object value) {
        set(thisObject, value);
    }

    @Override
    public boolean compareAndSet(Object thisObject, Object expected, Object actual) {
        if (get(thisObject) != expected) return false;
        set(thisObject, actual);
        return true;
    }

    private boolean compareAndSetBoxed(Object thisObject, Object expected, Object actual) {
        if (!Objects.equals(get(thisObject), expected)) return false;
        set(thisObject, actual);
        return true;
    }

    @Override
    public boolean compareAndSetInt(Object thisObject, int expected, int actual) {
        return compareAndSetBoxed(thisObject, expected, actual);
    }

    @Override
    public boolean compareAndSetLong(Object thisObject, long expected, long actual) {
        return compareAndSetBoxed(thisObject, expected, actual);
    }

    @Override
    public boolean compareAndSetByte(Object thisObject, byte expected, byte actual) {
        return compareAndSetBoxed(thisObject, expected, actual);
    }

    @Override
    public int getAndAdd(Object thisObject, int value) {
        int old = (Integer) get(thisObject);
        set(thisObject, old + value);
        return old;
    }

    @Override
    public Object getVolatileArrayElement(Object thisObject, int index) {
        return Array.get(thisObject, index);
    }

    @Override
    public void setVolatileArrayElement(Object thisObject, int index, Object value) {
        Array.set(thisObject, index, value);
    }

    @Override
    public boolean compareAndSetArrayElement(Object thisObject, int index, Object expected, Object value) {
        if (Array.get(thisObject, index) != expected) return false;
        Array.set(thisObject, index, value);
        return true;
    }

    public static void useVarHandlesInConcurrentCollections() {
        FACTORY = new VarHandleWrapperImpl(null);
    }
}
