package com.jimuqu.claw.features;

import com.jimuqu.claw.SolonClawApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

import java.io.IOException;

/**
 * 预留的基础 HTTP 测试样例。
 */
@SolonTest(SolonClawApp.class)
public class HelloTest extends HttpTester {
    /**
     * 当前项目暂未启用默认 hello 接口测试。
     *
     * @throws IOException 请求过程中的异常
     */
    @Test
    public void hello() throws IOException {
        //assert path("/hello?name=world").get().contains("world");
        // assert path("/hello?name=solon").get().contains("solon");
    }
}
