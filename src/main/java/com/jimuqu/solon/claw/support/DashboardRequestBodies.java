package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.core.handle.Context;

/** Dashboard 请求体解析辅助类，收敛各控制台控制器中重复的 body(Context) 解析逻辑。 */
public final class DashboardRequestBodies {
    /** 创建控制台请求体解析辅助实例。 */
    private DashboardRequestBodies() {}

    /**
     * 将请求体解析为 Snack4 节点；空体返回空节点，非对象抛出非法参数异常。
     *
     * @param ctx 当前请求或运行上下文。
     * @return 返回jsonObject结果。
     */
    public static ONode jsonObject(Context ctx) {
        String raw = readBody(ctx);
        if (StrUtil.isBlank(raw)) {
            return new ONode();
        }
        return parseObject(raw);
    }

    /**
     * 将请求体解析为 Map；空体返回空 Map，否则反序列化为 LinkedHashMap。
     *
     * @param ctx 当前请求或运行上下文。
     * @return 返回jsonObjectMap结果。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> jsonObjectMap(Context ctx) {
        String raw = readBody(ctx);
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyMap();
        }
        ONode node = parseObject(raw);
        return ONode.deserialize(node.toJson(), LinkedHashMap.class);
    }

    /**
     * 读取请求体原始文本，读取失败抛出非法参数异常。
     *
     * @param ctx 当前请求或运行上下文。
     * @return 返回读取到的原始文本。
     */
    private static String readBody(Context ctx) {
        try {
            return ctx.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
    }

    /**
     * 将原始文本解析为对象节点，解析失败或非对象抛出非法参数异常。
     *
     * @param raw 原始请求体文本。
     * @return 返回parseObject结果。
     */
    private static ONode parseObject(String raw) {
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return node;
            }
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }
}
