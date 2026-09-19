package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.iterlocus.pathway.RouteNameValidator.Problem;

import org.junit.Test;

public class RouteNameValidatorTest {

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    @Test
    public void acceptsNormalNames() {
        assertTrue(RouteNameValidator.isValid("上班路线"));
        assertTrue(RouteNameValidator.isValid("Route A-1(早高峰).v2"));
    }

    @Test
    public void acceptsExactlyMaxLength() {
        assertTrue(RouteNameValidator.isValid(repeat('a', RouteNameValidator.MAX_LENGTH)));
    }

    @Test
    public void rejectsOverMaxLength() {
        assertEquals(Problem.TOO_LONG,
                RouteNameValidator.findProblem(repeat('a', RouteNameValidator.MAX_LENGTH + 1)));
    }

    @Test
    public void rejectsNullEmptyAndBlank() {
        assertEquals(Problem.EMPTY, RouteNameValidator.findProblem(null));
        assertEquals(Problem.EMPTY, RouteNameValidator.findProblem(""));
        assertEquals(Problem.EMPTY, RouteNameValidator.findProblem("   "));
    }

    @Test
    public void rejectsControlCharacters() {
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\nA"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\rA"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\tA"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\u0000A"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\u007FA"));
    }

    @Test
    public void forbiddenCharsMatchesSpec() {
        // 字面钉死常量本身：增删成员或调换顺序都会失败。
        // 单独看下面的 rejectsEveryForbiddenCharacter 是对该常量的同义反复——
        // 断言集合取自被测常量，改坏常量它照样绿。两者合起来才成立：
        // 这里保证常量就是这 9 个字符，那里保证实现确实按该常量拒绝。
        assertEquals("/\\:*?\"<>|", RouteNameValidator.FORBIDDEN_CHARS);
    }

    @Test
    public void rejectsEveryForbiddenCharacter() {
        for (char c : RouteNameValidator.FORBIDDEN_CHARS.toCharArray()) {
            assertEquals("应拒绝字符: " + c,
                    Problem.FORBIDDEN_CHAR, RouteNameValidator.findProblem("路线" + c));
        }
    }

    @Test
    public void normalizeTrimsAndHandlesNull() {
        assertEquals("", RouteNameValidator.normalize(null));
        assertEquals("路线", RouteNameValidator.normalize("  路线  "));
    }

    @Test
    public void lengthIsMeasuredAfterTrim() {
        String padded = "  " + repeat('a', RouteNameValidator.MAX_LENGTH) + "  ";
        assertNull(RouteNameValidator.findProblem(padded));
        assertFalse(RouteNameValidator.isValid("  " + repeat('a', RouteNameValidator.MAX_LENGTH + 1)));
    }
}
