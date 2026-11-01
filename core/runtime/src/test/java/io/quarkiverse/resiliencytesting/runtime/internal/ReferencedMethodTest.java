package io.quarkiverse.resiliencytesting.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.MethodReference;

/**
 * Reading back the method a method reference names. The {@code read} overloads mirror those of
 * {@code Faults.target}, so that the references here resolve the way they do there; methods that return a value
 * and void methods both fit them.
 */
class ReferencedMethodTest {

    interface Base {
        String list();
    }

    interface Inventory extends Base {
        String reserve(String item);

        void ping();

        long update(String a, int b, long c, double d, boolean e);

        void replace(String a, int b, long c, double d, boolean e);

        String find(String name);

        String find(int id);
    }

    private static <B> ReferencedMethod read(MethodReference.Method0<B> method) {
        return ReferencedMethod.of(method);
    }

    private static <B, P1> ReferencedMethod read(MethodReference.Method1<B, P1> method) {
        return ReferencedMethod.of(method);
    }

    private static <B, P1, P2, P3, P4, P5> ReferencedMethod read(MethodReference.Method5<B, P1, P2, P3, P4, P5> method) {
        return ReferencedMethod.of(method);
    }

    private static Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        return Inventory.class.getMethod(name, parameters);
    }

    @Test
    void aMethodReferenceNamesItsMethodAndTheTypeItWasNamedOn() throws Exception {
        ReferencedMethod reserve = read(Inventory::reserve);
        assertEquals(Inventory.class, reserve.type());
        assertEquals(method("reserve", String.class), reserve.method());

        assertEquals(method("ping"), read(Inventory::ping).method());
        assertEquals(method("update", String.class, int.class, long.class, double.class, boolean.class),
                read(Inventory::update).method());
        assertEquals(method("replace", String.class, int.class, long.class, double.class, boolean.class),
                read(Inventory::replace).method());
    }

    @Test
    void anInheritedMethodIsNamedOnTheTypeOfTheReference() {
        ReferencedMethod list = read(Inventory::list);
        // the type the test named, which is the client, not the interface that declares the method
        assertEquals(Inventory.class, list.type());
        assertEquals("list", list.method().getName());
        assertEquals("io.quarkiverse.resiliencytesting.runtime.internal.ReferencedMethodTest$Inventory::list",
                list.toString());
    }

    @Test
    void aCastPicksOneOfSeveralOverloads() throws Exception {
        assertEquals(method("find", int.class),
                read((MethodReference.Method1<Inventory, Integer>) Inventory::find).method());
    }

    @Test
    void aLambdaOrABoundReferenceIsRefused() {
        IllegalArgumentException lambda = assertThrows(IllegalArgumentException.class,
                () -> read((Inventory inventory) -> inventory.reserve("widget")));
        assertTrue(lambda.getMessage().contains("Type::method"), lambda.getMessage());

        String item = "widget";
        // the instance is captured, and the method's own type is lost
        assertThrows(IllegalArgumentException.class, () -> read(item::concat));
    }
}
