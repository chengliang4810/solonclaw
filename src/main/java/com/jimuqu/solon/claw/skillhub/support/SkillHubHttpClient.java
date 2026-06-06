package com.jimuqu.solon.claw.skillhub.support;

import java.util.Map;

/** Skills Hub HTTP 抽象。 */
public interface SkillHubHttpClient {
    /**
     * 读取Text。
     *
     * @param url 待校验或访问的 URL。
     * @param headers headers 参数。
     * @return 返回读取到的Text。
     */
    String getText(String url, Map<String, String> headers) throws Exception;

    /**
     * 根据tes读取对应数据。
     *
     * @param url 待校验或访问的 URL。
     * @param headers headers 参数。
     * @return 返回按tes读取得到的结果。
     */
    byte[] getBytes(String url, Map<String, String> headers) throws Exception;

    /**
     * 执行postJSON相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param headers headers 参数。
     * @param jsonBody JSON正文参数。
     * @return 返回post JSON结果。
     */
    String postJson(String url, Map<String, String> headers, String jsonBody) throws Exception;
}
