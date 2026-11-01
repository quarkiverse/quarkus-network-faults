package io.quarkiverse.resiliencytesting;

import java.io.Serializable;

/**
 * The method references {@link Faults#target(MethodReference.Method0)} and its overloads take, such as
 * {@code InventoryClient::reserve}: one interface per number of parameters, up to five. Each is {@code void}, since a
 * method reference to a method that returns a value fits a void function type too, and the method is read back from
 * the reference rather than called. They are {@link Serializable} because that is what lets the method a reference
 * names be read back from it. A test passes a method reference and never names these types; a method that is
 * overloaded with the same number of parameters needs a cast to pick one.
 */
public interface MethodReference {

    interface Method0<B> extends Serializable {
        void call(B bean);
    }

    interface Method1<B, P1> extends Serializable {
        void call(B bean, P1 p1);
    }

    interface Method2<B, P1, P2> extends Serializable {
        void call(B bean, P1 p1, P2 p2);
    }

    interface Method3<B, P1, P2, P3> extends Serializable {
        void call(B bean, P1 p1, P2 p2, P3 p3);
    }

    interface Method4<B, P1, P2, P3, P4> extends Serializable {
        void call(B bean, P1 p1, P2 p2, P3 p3, P4 p4);
    }

    interface Method5<B, P1, P2, P3, P4, P5> extends Serializable {
        void call(B bean, P1 p1, P2 p2, P3 p3, P4 p4, P5 p5);
    }
}
