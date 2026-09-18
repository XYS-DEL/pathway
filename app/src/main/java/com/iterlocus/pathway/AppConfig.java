package com.iterlocus.pathway;

/**
 * 应用级配置。
 * <p>
 * 与发布渠道相关的地址集中在此处，避免像上游那样散落在各个 Activity 中写死。
 * 二改后这两个入口默认关闭：填入自己的仓库地址后即可启用，无需改动调用方逻辑。
 */
public final class AppConfig {
    /**
     * 是否启用「检查更新」。
     * 置为 true 且 {@link #UPDATE_API_URL} 非空后，侧滑菜单的检查更新才会真正发起请求。
     */
    public static final boolean UPDATE_CHECK_ENABLED = false;

    /**
     * 检查更新使用的 GitHub releases API 地址。
     * 例如 https://api.github.com/repos/&lt;owner&gt;/&lt;repo&gt;/releases/latest
     */
    public static final String UPDATE_API_URL = "";

    /**
     * 意见反馈地址。
     * 例如 https://github.com/&lt;owner&gt;/&lt;repo&gt;/issues
     */
    public static final String FEEDBACK_URL = "";

    private AppConfig() {
    }
}
