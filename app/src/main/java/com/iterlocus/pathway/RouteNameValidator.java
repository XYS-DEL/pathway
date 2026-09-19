package com.iterlocus.pathway;

/**
 * 路线名称的合规校验。纯逻辑，不依赖 Android，可单元测试。
 *
 * <p>名字会进数据库、被用户看到，也可能在后续被导出，所以在入口处就挡掉
 * 空白、超长、控制字符和文件名敏感字符。
 */
public final class RouteNameValidator {

    /** 名称长度上限，按 trim 后的字符数计。 */
    public static final int MAX_LENGTH = 32;

    /** 这些字符在后续若要导出成文件名时会惹麻烦，提前挡掉。 */
    public static final String FORBIDDEN_CHARS = "/\\:*?\"<>|";

    /** 不合规的原因。界面据此映射到 strings.xml 的文案。 */
    public enum Problem {
        /** trim 后为空 */
        EMPTY,
        /** 超过 {@link #MAX_LENGTH} */
        TOO_LONG,
        /** 含控制字符（0x00–0x1F 与 0x7F，含 \n \r \t） */
        CONTROL_CHAR,
        /** 含 {@link #FORBIDDEN_CHARS} 中的字符 */
        FORBIDDEN_CHAR
    }

    private RouteNameValidator() {
    }

    /** 归一化：null 视为空串，去掉首尾空白。入库与比较都用归一化后的值。 */
    public static String normalize(String rawName) {
        return rawName == null ? "" : rawName.trim();
    }

    /** 合规返回 null；否则返回第一个命中的原因。 */
    public static Problem findProblem(String rawName) {
        String name = normalize(rawName);
        if (name.isEmpty()) {
            return Problem.EMPTY;
        }
        if (name.length() > MAX_LENGTH) {
            return Problem.TOO_LONG;
        }
        for (int index = 0; index < name.length(); index++) {
            char c = name.charAt(index);
            if (c < 0x20 || c == 0x7F) {
                return Problem.CONTROL_CHAR;
            }
            if (FORBIDDEN_CHARS.indexOf(c) >= 0) {
                return Problem.FORBIDDEN_CHAR;
            }
        }
        return null;
    }

    public static boolean isValid(String rawName) {
        return findProblem(rawName) == null;
    }
}
