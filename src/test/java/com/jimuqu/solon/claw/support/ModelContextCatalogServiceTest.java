package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/** 验证模型目录异步刷新线程随 Profile 运行时销毁而释放。 */
class ModelContextCatalogServiceTest {
    /** Profile 运行时销毁必须关闭懒创建的目录刷新线程，并禁止旧实例再次创建线程。 */
    @Test
    void shutdownClosesRefreshExecutorAndPreventsRecreation() throws Exception {
        ModelContextCatalogService service = new ModelContextCatalogService(new AppConfig());

        service.getContextLength("openai", "openai", "https://api.example.test/v1", "missing");
        ExecutorService executor = executorOf(service);
        assertThat(executor).isNotNull();

        service.shutdown();

        assertThat(executor.isShutdown()).isTrue();
        service.getContextLength("openai", "openai", "https://api.example.test/v1", "missing");
        assertThat(executorOf(service)).isNull();
    }

    /** 使用反射只验证资源生命周期，不暴露仅用于测试的生产访问器。 */
    private ExecutorService executorOf(ModelContextCatalogService service) throws Exception {
        Field field = ModelContextCatalogService.class.getDeclaredField("asyncRefreshExecutor");
        field.setAccessible(true);
        return (ExecutorService) field.get(service);
    }
}
