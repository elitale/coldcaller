package com.elitale.coldbirds.coldcalling.domain.value;

import java.util.function.Function;

/**
 * Represents the result of an operation that can fail.
 * Use instead of checked exceptions for service-layer operations.
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

    record Ok<T>(T value) implements Result<T> {}

    record Err<T>(String message, Throwable cause) implements Result<T> {
        public Err(String message) {
            this(message, null);
        }
    }

    static <T> Result<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Result<T> err(String message) {
        return new Err<>(message);
    }

    static <T> Result<T> err(String message, Throwable cause) {
        return new Err<>(message, cause);
    }

    default boolean isOk() {
        return this instanceof Ok;
    }

    default boolean isErr() {
        return this instanceof Err;
    }

    default <U> Result<U> map(Function<T, U> fn) {
        return switch (this) {
            case Ok<T> ok   -> Result.ok(fn.apply(ok.value()));
            case Err<T> err -> Result.err(err.message(), err.cause());
        };
    }
}
