package editor.compilerhost.web;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/** Single-threaded field updaters: Web Image has no threads, so compare-and-set is a plain read, compare and write. */
public final class Updaters {
    private Updaters() {}

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Field not registered for reflection: " + owner.getName() + "." + name, e);
        }
    }

    public static <U, W> AtomicReferenceFieldUpdater<U, W> newRefUpdater(Class<U> tclass, Class<W> vclass, String fieldName) {
        Field f = field(tclass, fieldName);
        return new AtomicReferenceFieldUpdater<>() {
            @SuppressWarnings("unchecked")
            @Override public W get(U obj) {
                try { return (W) f.get(obj); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
            @Override public void set(U obj, W newValue) {
                try { f.set(obj, newValue); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
            @Override public void lazySet(U obj, W newValue) { set(obj, newValue); }
            @Override public boolean compareAndSet(U obj, W expect, W update) {
                if (get(obj) != expect) return false;
                set(obj, update);
                return true;
            }
            @Override public boolean weakCompareAndSet(U obj, W expect, W update) { return compareAndSet(obj, expect, update); }
        };
    }

    public static <U> AtomicIntegerFieldUpdater<U> newIntUpdater(Class<U> tclass, String fieldName) {
        Field f = field(tclass, fieldName);
        return new AtomicIntegerFieldUpdater<>() {
            @Override public int get(U obj) {
                try { return f.getInt(obj); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
            @Override public void set(U obj, int newValue) {
                try { f.setInt(obj, newValue); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
            @Override public void lazySet(U obj, int newValue) { set(obj, newValue); }
            @Override public boolean compareAndSet(U obj, int expect, int update) {
                if (get(obj) != expect) return false;
                set(obj, update);
                return true;
            }
            @Override public boolean weakCompareAndSet(U obj, int expect, int update) { return compareAndSet(obj, expect, update); }
        };
    }

    public static <U> AtomicLongFieldUpdater<U> newLongUpdater(Class<U> tclass, String fieldName) {
        Field f = field(tclass, fieldName);
        return new AtomicLongFieldUpdater<>() {
            @Override public long get(U obj) {
                try { return f.getLong(obj); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
            @Override public void set(U obj, long newValue) {
                try { f.setLong(obj, newValue); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            }
            @Override public void lazySet(U obj, long newValue) { set(obj, newValue); }
            @Override public boolean compareAndSet(U obj, long expect, long update) {
                if (get(obj) != expect) return false;
                set(obj, update);
                return true;
            }
            @Override public boolean weakCompareAndSet(U obj, long expect, long update) { return compareAndSet(obj, expect, update); }
        };
    }
}
