package io.quarkiverse.resiliencytesting.runtime.internal;

import java.io.Serializable;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * The method a method reference such as {@code InventoryClient::reserve} names, and the type it was named on, which
 * for an inherited method is not the type that declares it. A serializable lambda carries both: the compiler gives it
 * a {@code writeReplace} that describes it as a {@link SerializedLambda}.
 */
record ReferencedMethod(Class<?> type, Method method) {

    static ReferencedMethod of(Serializable reference) {
        SerializedLambda lambda = serialized(reference);
        int kind = lambda.getImplMethodKind();
        if (lambda.getCapturedArgCount() > 0
                || kind != MethodHandleInfo.REF_invokeInterface && kind != MethodHandleInfo.REF_invokeVirtual) {
            throw notAMethodReference(reference);
        }
        ClassLoader loader = reference.getClass().getClassLoader();
        // the first parameter of the functional method is the instance the method is called on
        Class<?> type = MethodType.fromMethodDescriptorString(lambda.getInstantiatedMethodType(), loader).parameterType(0);
        MethodType signature = MethodType.fromMethodDescriptorString(lambda.getImplMethodSignature(), loader);
        for (Method m : type.getMethods()) {
            if (m.getName().equals(lambda.getImplMethodName()) && m.getReturnType() == signature.returnType()
                    && Arrays.equals(m.getParameterTypes(), signature.parameterArray())) {
                return new ReferencedMethod(type, m);
            }
        }
        throw new IllegalArgumentException("No public method " + lambda.getImplMethodName() + " on " + type.getName()
                + " matches the method reference " + reference);
    }

    private static SerializedLambda serialized(Serializable reference) {
        try {
            Method writeReplace = reference.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            if (writeReplace.invoke(reference) instanceof SerializedLambda lambda) {
                return lambda;
            }
        } catch (ReflectiveOperationException e) {
            throw notAMethodReference(reference);
        }
        throw notAMethodReference(reference);
    }

    private static IllegalArgumentException notAMethodReference(Serializable reference) {
        return new IllegalArgumentException("Expected a method reference of the form Type::method, such as "
                + "InventoryClient::reserve, but got " + reference + ". A lambda, or a method reference bound to an "
                + "instance such as client::reserve, does not name the method in a way that can be read back.");
    }

    @Override
    public String toString() {
        return type.getName() + "::" + method.getName();
    }
}
