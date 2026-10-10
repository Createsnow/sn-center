package com.adtek.sn.service.support;

/** 工厂输入沿用Python str.strip的Unicode空白集合；建档与失败痕迹使用同一清理规则。 */
public final class FactoryText {
    /** 原型按Unicode字符裁剪痕迹详情，不截断补充平面字符。 */
    public static String cut(String value, int limit) {
        return value.codePointCount(0, value.length()) <= limit
                ? value
                : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    private FactoryText() {}

    public static String trim(String value) {
        if (value == null) return "";
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start)))
            start += Character.charCount(value.codePointAt(start));
        while (start < end && whitespace(value.codePointBefore(end)))
            end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }

    private static boolean whitespace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85;
    }
}
