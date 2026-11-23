package com.dropcode.backend.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator();

    @Test
    void generatesCodeWithCorrectLength() {
        assertThat(generator.generate()).hasSize(CodeGenerator.LENGTH);
    }

    @Test
    void generatesOnlyValidCodes() {
        for (int i = 0; i < 10_000; i++) {
            assertThat(generator.isValid(generator.generate())).isTrue();
        }
    }

    @Test
    void alphabetExcludesConfusingCharacters() {
        assertThat(CodeGenerator.ALPHABET).doesNotContain("0", "O", "1", "I", "L");
        assertThat(CodeGenerator.ALPHABET).hasSize(31);
    }

    @Test
    void generatesMostlyUniqueCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSizeGreaterThan(9_990);
    }

    @Test
    void normalizeTrimsAndUppercases() {
        assertThat(generator.normalize("  ab3k9 ")).isEqualTo("AB3K9");
        assertThat(generator.normalize(null)).isEmpty();
    }

    @Test
    void rejectsInvalidCodes() {
        assertThat(generator.isValid("AB3K")).isFalse();     // too short
        assertThat(generator.isValid("AB3K9X")).isFalse();   // too long
        assertThat(generator.isValid("AB0K9")).isFalse();    // contains 0
        assertThat(generator.isValid("ab3k9")).isFalse();    // lowercase, not normalized
        assertThat(generator.isValid(null)).isFalse();
    }
}