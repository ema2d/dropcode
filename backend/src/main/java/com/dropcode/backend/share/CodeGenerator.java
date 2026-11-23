package com.dropcode.backend.share;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class CodeGenerator {

    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final int LENGTH = 5;

    private static final Pattern VALID_CODE =
            Pattern.compile("^[" + ALPHABET + "]{" + LENGTH + "}$");

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            int index = random.nextInt(ALPHABET.length());
            code.append(ALPHABET.charAt(index));
        }
        return code.toString();
    }

    public String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toUpperCase(Locale.ROOT);
    }

    public boolean isValid(String code) {
        return code != null && VALID_CODE.matcher(code).matches();
    }
}