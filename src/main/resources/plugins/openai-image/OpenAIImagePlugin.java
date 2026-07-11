import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.media.OpenAiImageProvider;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;

/** 注册 OpenAI 图片生成与编辑 Provider。 */
public class OpenAIImagePlugin implements AgentPlugin {
    /** 注册按请求读取当前 Profile OpenAI 密钥的 Provider。 */
    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerImageGenProvider(
                new OpenAiImageProvider(
                        () ->
                                StrUtil.blankToDefault(
                                        RuntimeConfigResolver.getValue("providers.openai.apiKey"),
                                        ctx.getEnv("OPENAI_API_KEY"))));
    }
}
