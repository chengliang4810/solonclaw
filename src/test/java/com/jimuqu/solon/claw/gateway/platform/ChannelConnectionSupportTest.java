package com.jimuqu.solon.claw.gateway.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.jupiter.api.Test;

class ChannelConnectionSupportTest {
    /** 断开连接时应使用正常关闭码并停止回调线程池。 */
    @Test
    void shouldCloseWebSocketAndShutdownExecutor() {
        RecordingWebSocket webSocket = new RecordingWebSocket();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        ChannelConnectionSupport.disconnect(webSocket, executor);

        assertThat(webSocket.closeCode).isEqualTo(1000);
        assertThat(webSocket.closeReason).isEqualTo("normal");
        assertThat(executor.isShutdown()).isTrue();
    }

    private static class RecordingWebSocket implements WebSocket {
        private int closeCode;
        private String closeReason;

        @Override
        public Request request() {
            return new Request.Builder().url("http://127.0.0.1").build();
        }

        @Override
        public long queueSize() {
            return 0L;
        }

        @Override
        public boolean send(String text) {
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            return true;
        }

        @Override
        public boolean close(int code, String reason) {
            this.closeCode = code;
            this.closeReason = reason;
            return true;
        }

        @Override
        public void cancel() {}
    }
}
