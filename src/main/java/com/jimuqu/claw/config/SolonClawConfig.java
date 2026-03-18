package com.jimuqu.claw.config;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.claw.agent.channel.ChannelRegistry;
import com.jimuqu.claw.agent.runtime.AgentRuntimeService;
import com.jimuqu.claw.agent.runtime.ConversationAgent;
import com.jimuqu.claw.agent.runtime.ConversationScheduler;
import com.jimuqu.claw.agent.runtime.HeartbeatService;
import com.jimuqu.claw.agent.runtime.SolonAiConversationAgent;
import com.jimuqu.claw.agent.store.RuntimeStoreService;
import com.jimuqu.claw.channel.dingtalk.DingTalkAccessTokenService;
import com.jimuqu.claw.channel.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.claw.channel.dingtalk.DingTalkRobotSender;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.BindProps;
import org.noear.solon.annotation.Configuration;

import java.io.File;

/**
 * 统一装配 SolonClaw 运行时依赖。
 */
@Configuration
public class SolonClawConfig {
    /**
     * 绑定项目自定义配置。
     *
     * @return 配置对象
     */
    @Bean
    @BindProps(prefix = "solonclaw")
    public SolonClawProperties solonClawProperties() {
        return new SolonClawProperties();
    }

    /**
     * 创建运行时存储服务。
     *
     * @param properties 项目配置
     * @return 存储服务
     */
    @Bean
    public RuntimeStoreService runtimeStoreService(SolonClawProperties properties) {
        File runtimeDir = FileUtil.file(properties.getStorage().getRuntimeDir());
        return new RuntimeStoreService(runtimeDir);
    }

    /**
     * 创建会话调度器。
     *
     * @param properties 项目配置
     * @return 会话调度器
     */
    @Bean
    public ConversationScheduler conversationScheduler(SolonClawProperties properties) {
        return new ConversationScheduler(properties.getAgent().getScheduler().getMaxConcurrentPerConversation());
    }

    /**
     * 创建会话执行 Agent。
     *
     * @param chatModel 聊天模型
     * @param properties 项目配置
     * @return 会话执行 Agent
     */
    @Bean
    public ConversationAgent conversationAgent(ChatModel chatModel, SolonClawProperties properties) {
        return new SolonAiConversationAgent(chatModel, properties.getAgent().getSystemPrompt());
    }

    /**
     * 创建渠道注册表。
     *
     * @return 渠道注册表
     */
    @Bean
    public ChannelRegistry channelRegistry() {
        return new ChannelRegistry();
    }

    /**
     * 创建钉钉 token 服务。
     *
     * @param properties 项目配置
     * @return token 服务
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public DingTalkAccessTokenService dingTalkAccessTokenService(SolonClawProperties properties) {
        return new DingTalkAccessTokenService(properties.getChannels().getDingtalk());
    }

    /**
     * 创建钉钉机器人发送服务。
     *
     * @param dingTalkAccessTokenService token 服务
     * @param properties 项目配置
     * @return 发送服务
     * @throws Exception 创建底层客户端时的异常
     */
    @Bean
    public DingTalkRobotSender dingTalkRobotSender(
            DingTalkAccessTokenService dingTalkAccessTokenService,
            SolonClawProperties properties
    ) throws Exception {
        return new DingTalkRobotSender(dingTalkAccessTokenService, properties.getChannels().getDingtalk());
    }

    /**
     * 创建 Agent 运行时服务。
     *
     * @param conversationAgent 会话执行 Agent
     * @param runtimeStoreService 运行时存储服务
     * @param conversationScheduler 会话调度器
     * @param channelRegistry 渠道注册表
     * @param properties 项目配置
     * @return Agent 运行时服务
     */
    @Bean
    public AgentRuntimeService agentRuntimeService(
            ConversationAgent conversationAgent,
            RuntimeStoreService runtimeStoreService,
            ConversationScheduler conversationScheduler,
            ChannelRegistry channelRegistry,
            SolonClawProperties properties
    ) {
        return new AgentRuntimeService(
                conversationAgent,
                runtimeStoreService,
                conversationScheduler,
                channelRegistry,
                properties
        );
    }

    /**
     * 创建并注册钉钉渠道适配器。
     *
     * @param agentRuntimeService Agent 运行时服务
     * @param runtimeStoreService 运行时存储服务
     * @param dingTalkRobotSender 钉钉机器人发送服务
     * @param channelRegistry 渠道注册表
     * @param properties 项目配置
     * @return 钉钉渠道适配器
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public DingTalkChannelAdapter dingTalkChannelAdapter(
            AgentRuntimeService agentRuntimeService,
            RuntimeStoreService runtimeStoreService,
            DingTalkRobotSender dingTalkRobotSender,
            ChannelRegistry channelRegistry,
            SolonClawProperties properties
    ) {
        DingTalkChannelAdapter adapter = new DingTalkChannelAdapter(
                agentRuntimeService,
                runtimeStoreService,
                dingTalkRobotSender,
                properties.getChannels().getDingtalk()
        );
        channelRegistry.register(adapter);
        return adapter;
    }

    /**
     * 创建心跳服务。
     *
     * @param agentRuntimeService Agent 运行时服务
     * @param runtimeStoreService 运行时存储服务
     * @param properties 项目配置
     * @return 心跳服务
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public HeartbeatService heartbeatService(
            AgentRuntimeService agentRuntimeService,
            RuntimeStoreService runtimeStoreService,
            SolonClawProperties properties
    ) {
        return new HeartbeatService(agentRuntimeService, runtimeStoreService, properties);
    }
}
