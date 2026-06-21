package com.elitale.coldbirds.coldcalling.domain.value;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResultTest {

    @Test
    void okIsOk() {
        Result<String> result = Result.ok("hello");
        assertThat(result.isOk()).isTrue();
        assertThat(result.isErr()).isFalse();
        assertThat(((Result.Ok<String>) result).value()).isEqualTo("hello");
    }

    @Test
    void errIsErr() {
        Result<String> result = Result.err("something went wrong");
        assertThat(result.isErr()).isTrue();
        assertThat(result.isOk()).isFalse();
        assertThat(((Result.Err<String>) result).message()).isEqualTo("something went wrong");
    }

    @Test
    void errWithCausePreservesCause() {
        RuntimeException cause = new RuntimeException("root cause");
        Result<String> result = Result.err("wrapped", cause);
        assertThat(((Result.Err<String>) result).cause()).isSameAs(cause);
    }

    @Test
    void mapTransformsOkValue() {
        Result<Integer> result = Result.<String>ok("hello").map(String::length);
        assertThat(result).isEqualTo(Result.ok(5));
    }

    @Test
    void mapPreservesErr() {
        Result<Integer> result = Result.<String>err("oops").map(String::length);
        assertThat(result.isErr()).isTrue();
        assertThat(((Result.Err<Integer>) result).message()).isEqualTo("oops");
    }
}
