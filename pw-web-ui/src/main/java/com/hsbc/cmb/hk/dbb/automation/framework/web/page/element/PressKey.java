package com.hsbc.cmb.hk.dbb.automation.framework.web.page.element;

import java.util.Locale;

/**
 * Playwright 键盘按键枚举（P1-16 收口）。
 *
 * <p>历史上调用方写 {@code page.press("enter")} / {@code press("Enter")} 大小写不一致会直接抛错
 * （Playwright 只认 {@code "Enter"}）。枚举把合法键名收成常量，大小写由枚举名统一，
 * 既杜绝拼写/大小写问题，也让 codegen 产物可读可审计。
 *
 * <p>组合键用 {@link PageElement#press(PressKey...)} 传多个枚举值，内部按
 * Playwright 的 {@code "Control+Enter"} 形式拼接；顺序即传入顺序（修饰键在前）。
 */
public enum PressKey {
    // 导航 / 控制
    ENTER("Enter"),
    ESCAPE("Escape"),
    TAB("Tab"),
    BACKSPACE("Backspace"),
    DELETE("Delete"),
    INSERT("Insert"),
    HOME("Home"),
    END("End"),
    PAGE_UP("PageUp"),
    PAGE_DOWN("PageDown"),
    SPACE("Space"),

    // 方向键
    ARROW_UP("ArrowUp"),
    ARROW_DOWN("ArrowDown"),
    ARROW_LEFT("ArrowLeft"),
    ARROW_RIGHT("ArrowRight"),

    // 功能键
    F1("F1"), F2("F2"), F3("F3"), F4("F4"), F5("F5"), F6("F6"),
    F7("F7"), F8("F8"), F9("F9"), F10("F10"), F11("F11"), F12("F12"),

    // 修饰键（组合键时放在前面）
    CONTROL("Control"),
    SHIFT("Shift"),
    ALT("Alt"),
    META("Meta"),

    // 数字键 0-9
    DIGIT_0("0"), DIGIT_1("1"), DIGIT_2("2"), DIGIT_3("3"), DIGIT_4("4"),
    DIGIT_5("5"), DIGIT_6("6"), DIGIT_7("7"), DIGIT_8("8"), DIGIT_9("9"),

    // 常用符号键
    SLASH("/"), BACKSLASH("\\"), MINUS("-"), PERIOD("."), COMMA(",");

    private final String keyName;

    PressKey(String keyName) {
        this.keyName = keyName;
    }

    /** Playwright 要求的原始键名（大小写/拼写已固定）。 */
    public String keyName() {
        return keyName;
    }

    /**
     * 大小写不敏感解析（供旧代码/配置文本迁移用）：先按枚举名、再按 keyName 匹配。
     * 无法识别返回 {@code null}，由调用方决定是否抛错——不静默替换成别的键。
     */
    public static PressKey parse(String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (PressKey k : values()) {
            if (k.name().equals(normalized)) return k;
        }
        for (PressKey k : values()) {
            if (k.keyName.equalsIgnoreCase(text.trim())) return k;
        }
        return null;
    }
}
