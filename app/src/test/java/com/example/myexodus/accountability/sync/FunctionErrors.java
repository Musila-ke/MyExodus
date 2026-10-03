package com.example.myexodus.accountability.sync;

import com.google.firebase.functions.FirebaseFunctionsException;

/**
 * Java shim: FirebaseFunctionsException's (message, code, details) constructor is public in
 * bytecode but `internal` in the SDK's Kotlin metadata, so the Kotlin test suite cannot call
 * it directly and the transport-retry tests need to script real exception instances.
 */
public final class FunctionErrors {
    private FunctionErrors() {}

    public static FirebaseFunctionsException of(String message, FirebaseFunctionsException.Code code) {
        return new FirebaseFunctionsException(message, code, null);
    }

    public static FirebaseFunctionsException of(String message, FirebaseFunctionsException.Code code, Object details) {
        return new FirebaseFunctionsException(message, code, details);
    }
}
