/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import software.amazon.awssdk.annotations.Immutable;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.annotations.ToBuilderIgnoreField;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * A map from {@code AttributeMap.Key<T>} to {@code T} that ensures the values stored with a key matches the type associated with
 * the key. This does not implement {@link Map} because it has more strict typing requirements, but a {@link Map} can be
 * converted
 * to an {code AttributeMap} via the type-unsafe {@link AttributeMap} method.
 *
 * This can be used for storing configuration values ({@code OptionKey.LOG_LEVEL} to {@code Boolean.TRUE}), attaching
 * arbitrary attributes to a request chain ({@code RequestAttribute.CONFIGURATION} to {@code ClientConfiguration}) or similar
 * use-cases.
 */
@SdkProtectedApi
@Immutable
public final class AttributeMap implements ToCopyableBuilder<AttributeMap.Builder, AttributeMap>, SdkAutoCloseable {
    private static final AttributeMap EMPTY = AttributeMap.builder().build();
    private final Map<Key<?>, Value<?>> attributes;

    private AttributeMap(Map<Key<?>, Value<?>> attributes) {
        this.attributes = new HashMap<>();
        attributes.forEach((k, v) -> this.attributes.put(k, v.copyForMap()));
    }

    /**
     * Return true if the provided key is configured in this map. Useful for differentiating between whether the provided key was
     * not configured in the map or if it is configured, but its value is null.
     */
    public <T> boolean containsKey(Key<T> typedKey) {
        return attributes.containsKey(typedKey);
    }

    /**
     * Get the value associated with the provided key from this map. This will return null if the value is not set or if the
     * value stored is null. These cases can be disambiguated using {@link #containsKey(Key)}.
     */
    public <T> T get(Key<T> key) {
        Validate.notNull(key, "Key to retrieve must not be null.");
        Value<?> value = attributes.get(key);
        if (value == null) {
            return null;
        }
        return key.convertValue(value.get(this::get));
    }

    /**
     * Merges two AttributeMaps into one. This object is given higher precedence then the attributes passed in as a parameter.
     *
     * @param lowerPrecedence Options to merge into 'this' AttributeMap object. Any attribute already specified in 'this' object
     *                        will be left as is since it has higher precedence.
     * @return New options with values merged.
     */
    public AttributeMap merge(AttributeMap lowerPrecedence) {
        Builder resultBuilder = builder();
        attributes.forEach((k, v) -> resultBuilder.internalPut(k, v.copyForMap()));
        lowerPrecedence.attributes.forEach((k, v) -> resultBuilder.internalPutIfAbsent(k, v::copyForMap));
        return resultBuilder.build();
    }

    public static AttributeMap empty() {
        return EMPTY;
    }

    public AttributeMap copy() {
        return toBuilder().build();
    }

    @Override
    public void close() {
        attributes.values().forEach(Value::close);
    }

    /**
     * An abstract class extended by pseudo-enums defining the key for data that is stored in the {@link AttributeMap}. For
     * example, a {@code ClientOption<T>} may extend this to define options that can be stored in an {@link AttributeMap}.
     */
    public abstract static class Key<T> {

        private final Class<?> valueType;

        protected Key(Class<T> valueType) {
            this.valueType = valueType;
        }

        protected Key(UnsafeValueType unsafeValueType) {
            this.valueType = unsafeValueType.valueType;
        }

        /**
         * Useful for parameterized types.
         */
        protected static class UnsafeValueType {
            private final Class<?> valueType;

            public UnsafeValueType(Class<?> valueType) {
                this.valueType = valueType;
            }
        }

        /**
         * Validate the provided value is of the correct type.
         */
        final void validateValue(Object value) {
            if (value != null) {
                Validate.isAssignableFrom(valueType, value.getClass(),
                                          "Invalid option: %s. Required value of type %s, but was %s.",
                                          this, valueType, value.getClass());
            }
        }

        /**
         * Validate the provided value is of the correct type and convert it to the proper type for this option.
         */
        public final T convertValue(Object value) {
            validateValue(value);

            @SuppressWarnings("unchecked") // Only actually unchecked if UnsafeValueType is used.
            T result = (T) valueType.cast(value);
            return result;
        }
    }

    @Override
    public String toString() {
        return attributes.toString();
    }

    @Override
    public int hashCode() {
        return attributes.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof AttributeMap && attributes.equals(((AttributeMap) obj).attributes);
    }

    @Override
    @ToBuilderIgnoreField("configuration")
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements CopyableBuilder<Builder, AttributeMap> {

        private final Map<Key<?>, Value<?>> configuration = new HashMap<>();

        private Builder() {
        }

        private Builder(AttributeMap attributeMap) {
            attributeMap.attributes.forEach((k, v) -> configuration.put(k, v.copyForBuilder()));
        }

        public <T> T get(Key<T> key) {
            Validate.notNull(key, "Key to retrieve must not be null.");
            Value<?> value = configuration.get(key);
            if (value == null) {
                return null;
            }
            return key.convertValue(value.get(this::get));
        }

        /**
         * Add a mapping between the provided key and value.
         */
        public <T> Builder put(Key<T> key, T value) {
            Validate.notNull(key, "Key to set must not be null.");
            configuration.put(key, new ConstantValue<>(value));
            return this;
        }

        public <T> Builder putLazy(Key<T> key, LazyValue<T> lazyValue) {
            Validate.notNull(key, "Key to set must not be null.");
            configuration.put(key, new DerivedValue<>(key, lazyValue));
            return this;
        }

        public <T> Builder putLazyIfAbsent(Key<T> key, LazyValue<T> lazyValue) {
            Validate.notNull(key, "Key to set must not be null.");
            internalPutIfAbsent(key, () -> new DerivedValue<>(key, lazyValue));
            return this;
        }

        private Builder internalPut(Key<?> key, Value<?> value) {
            configuration.put(key, value);
            return this;
        }

        private Builder internalPutIfAbsent(Key<?> key, Supplier<Value<?>> value) {
            configuration.compute(key, (k, v) -> {
                if (v == null || v.get(this::get) == null) {
                    return value.get();
                }
                return v;
            });
            return this;
        }

        /**
         * Adds all the attributes from the map provided. This is not type safe, and will throw an exception during creation if
         * a value in the map is not of the correct type for its key.
         */
        public Builder putAll(Map<? extends Key<?>, ?> attributes) {
            attributes.forEach((key, value) -> {
                key.validateValue(value);
                configuration.put(key, new ConstantValue<>(value));
            });
            return this;
        }

        @Override
        public AttributeMap build() {
            return new AttributeMap(configuration);
        }
    }

    @FunctionalInterface
    public interface LazyValue<T> {
        T get(LazyValueSource source);
    }

    @FunctionalInterface
    public interface LazyValueSource {
        <T> T get(Key<T> sourceKey);
    }

    private interface Value<T> extends SdkAutoCloseable {
        T get(LazyValueSource source);

        Value<T> copyForBuilder();

        Value<T> copyForMap();
    }

    private static class ConstantValue<T> implements Value<T> {
        private final T value;

        private ConstantValue(T value) {
            this.value = value;
        }

        @Override
        public T get(LazyValueSource source) {
            return value;
        }

        @Override
        public Value<T> copyForBuilder() {
            return this;
        }

        @Override
        public Value<T> copyForMap() {
            return this;
        }

        @Override
        public void close() {
            IoUtils.closeIfCloseable(value, null);
            shutdownIfExecutorService(value);
        }
    }

    private static class DerivedValue<T> implements Value<T> {
        private final Key<T> key;
        private final LazyValue<T> lazyValue;
        private boolean onStack = false;

        private DerivedValue(Key<T> key, LazyValue<T> lazyValue) {
            this.key = Validate.paramNotNull(key, "key");
            this.lazyValue = Validate.paramNotNull(lazyValue, "key");
        }

        @Override
        public T get(LazyValueSource source) {
            try {
                if (onStack) {
                    throw new IllegalStateException("Derived key " + key + " attempted to read itself");
                }
                onStack = true;
                return lazyValue.get(source);
            } finally {
                onStack = false;
            }
        }

        @Override
        public Value<T> copyForBuilder() {
            return new DerivedValue<>(key, lazyValue);
        }

        @Override
        public Value<T> copyForMap() {
            return new CachingDerivedValue<>(key, lazyValue);
        }

        @Override
        public void close() {
        }
    }

    private static final class CachingDerivedValue<T> extends DerivedValue<T> {
        private boolean valueCached = false;
        private T value;

        private CachingDerivedValue(Key<T> key, LazyValue<T> lazyValue) {
            super(key, lazyValue);
        }

        @Override
        public T get(LazyValueSource source) {
            if (!valueCached) {
                value = super.get(source);
                valueCached = true;
            }
            return value;
        }

        @Override
        public void close() {
            IoUtils.closeIfCloseable(value, null);
            shutdownIfExecutorService(value);
        }
    }

    private static void shutdownIfExecutorService(Object object) {
        if (object instanceof ExecutorService) {
            ExecutorService executor = (ExecutorService) object;
            executor.shutdown();
        }
    }
}
