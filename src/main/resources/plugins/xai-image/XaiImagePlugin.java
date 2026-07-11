import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.media.XaiImageProvider;
import com.jimuqu.solon.claw.plugin.AgentPlugin;
import com.jimuqu.solon.claw.plugin.AgentPluginContext;

/** 注册 xAI 图片生成与编辑 Provider。 */
public class XaiImagePlugin implements AgentPlugin {
    /** 注册按请求读取当前 Profile xAI 密钥的 Provider。 */
    @Override
    public void register(AgentPluginContext ctx) {
        ctx.registerImageGenProvider(
                new XaiImageProvider(
                        () ->
                                StrUtil.blankToDefault(
                                        RuntimeConfigResolver.getValue("providers.xai.apiKey"),
                                        ctx.getEnv("XAI_API_KEY"))));
    }
}
