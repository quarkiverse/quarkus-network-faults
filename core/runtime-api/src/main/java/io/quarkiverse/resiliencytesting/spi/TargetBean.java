package io.quarkiverse.resiliencytesting.spi;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;

/**
 * The CDI bean a test asks about, as its types and qualifiers: what {@link BeanTargetResolver}s map to a target.
 * From an {@code Instance} these are the resolved bean's own types and qualifiers; from a class they are the class
 * and the qualifiers the test passed.
 */
public record TargetBean(Set<Type> types, Set<Annotation> qualifiers) {

    /**
     * The bean a test names by type and qualifiers. A caller-built varargs array may repeat a qualifier, so the
     * qualifiers go into a set that tolerates duplicates rather than letting {@code Set.of} throw.
     */
    public static TargetBean of(Class<?> type, Annotation... qualifiers) {
        return new TargetBean(Set.of(type), new LinkedHashSet<>(Arrays.asList(qualifiers)));
    }

    /**
     * The bean an {@code Instance} resolves to, with its own types and qualifiers.
     */
    public static TargetBean of(Instance<?> instance) {
        Bean<?> bean = instance.getHandle().getBean();
        return new TargetBean(bean.getTypes(), bean.getQualifiers());
    }

    /**
     * Whether any bean type is, extends or implements the given class.
     */
    public boolean hasType(Class<?> type) {
        for (Class<?> c : rawTypes()) {
            if (type.isAssignableFrom(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first bean type carrying the given annotation, if any.
     */
    public Optional<Class<?>> typeAnnotatedWith(Class<? extends Annotation> annotation) {
        for (Class<?> c : rawTypes()) {
            if (c.isAnnotationPresent(annotation)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * The qualifier of the given type, if present.
     */
    public <A extends Annotation> Optional<A> qualifier(Class<A> annotation) {
        for (Annotation q : qualifiers) {
            if (annotation.isInstance(q)) {
                return Optional.of(annotation.cast(q));
            }
        }
        return Optional.empty();
    }

    /**
     * The bean types as classes, generics erased.
     */
    public Set<Class<?>> rawTypes() {
        Set<Class<?>> raw = new LinkedHashSet<>();
        for (Type t : types) {
            if (t instanceof Class<?> c) {
                raw.add(c);
            } else if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
                raw.add(c);
            }
        }
        return raw;
    }

    @Override
    public String toString() {
        return "bean" + types + (qualifiers.isEmpty() ? "" : " with " + qualifiers);
    }
}
