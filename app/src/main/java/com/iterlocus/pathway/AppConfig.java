package com.iterlocus.pathway;

/**
 * 应用级配置。
 * <p>
 * 发布渠道相关地址集中在此处，避免散落在各个 Activity 中写死。
 */
public final class AppConfig {
    /**
     * 是否启用「检测更新」。
     * 为 true 且 {@link #UPDATE_API_URL} 非空时：启动静默检查，侧滑菜单可手动检查。
     */
    public static final boolean UPDATE_CHECK_ENABLED = true;

    /**
     * 检查更新使用的 GitHub releases API 地址（latest）。
     */
    public static final String UPDATE_API_URL =
            "https://api.github.com/repos/XYS-DEL/pathway/releases/latest";

    /**
     * 「问题反馈」打开的 GitHub Issues 地址。
     */
    public static final String FEEDBACK_URL =
            "https://github.com/XYS-DEL/pathway/issues";

    /**
     * 「联系作者」可选：邮件地址。
     */
    public static final String CONTACT_EMAIL = "3322794490@qq.com";

    /**
     * 「联系作者」可选：GitHub 仓库页。
     */
    public static final String CONTACT_GITHUB_URL =
            "https://github.com/XYS-DEL/pathway";

    private AppConfig() {
    }
}
