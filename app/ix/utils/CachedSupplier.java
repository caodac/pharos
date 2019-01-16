package ix.utils;

import java.util.function.Supplier;

/**
 * Created by katzelda on 10/18/18.
 */
public interface CachedSupplier<T> extends Supplier<T>{
    static <T> CachedSupplier<T> of(Supplier<T> original) {
        return new CachedSupplier<T>() {
            Supplier<T> delegate = this::firstTime;
            boolean initialized;
            public T get() {
                return delegate.get();
            }
            private synchronized T firstTime() {
                if(!initialized) {
                    T value=original.get();
                    delegate=() -> value;
                    initialized=true;
                }
                return delegate.get();
            }
        };
    }
}