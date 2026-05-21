package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.acp.AcpEditApproval;
import com.jimuqu.solon.claw.cli.acp.AcpEventBus;
import com.jimuqu.solon.claw.cli.acp.AcpRequestQueue;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpImageSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for MCP and ACP advanced capabilities. */
public class McpAcpAdvancedCapabilitiesTest {

    // ---- McpImageSupport ----

    @Test
    void mcpImageSupport_extractsBase64ImageBlock() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "iVBORw0KGgo="); // minimal base64

        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "image");
        block.put("source", source);

        List<Map<String, Object>> images = McpImageSupport.extractImages(block);
        assertThat(images).hasSize(1);
        assertThat(images.get(0).get("mime_type")).isEqualTo("image/png");
        assertThat(images.get(0).get("data")).isNotNull();
    }

    @Test
    void mcpImageSupport_hasImages_returnsTrueWhenImagePresent() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("media_type", "image/jpeg");
        source.put("data", "iVBORw0KGgo=");

        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "image");
        block.put("source", source);

        assertThat(McpImageSupport.hasImages(block)).isTrue();
    }

    @Test
    void mcpImageSupport_hasImages_returnsFalseForTextBlock() {
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", "hello");

        assertThat(McpImageSupport.hasImages(block)).isFalse();
    }

    @Test
    void mcpImageSupport_extractsFromContentList() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("media_type", "image/png");
        source.put("data", "iVBORw0KGgo=");

        Map<String, Object> imageBlock = new LinkedHashMap<String, Object>();
        imageBlock.put("type", "image");
        imageBlock.put("source", source);

        List<Object> content = new ArrayList<Object>();
        content.add(imageBlock);

        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("content", content);

        List<Map<String, Object>> images = McpImageSupport.extractImages(envelope);
        assertThat(images).hasSize(1);
    }

    @Test
    void mcpImageSupport_toDataUri_producesCorrectPrefix() {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("mime_type", "image/png");
        descriptor.put("data", "abc123");

        String uri = McpImageSupport.toDataUri(descriptor);
        assertThat(uri).startsWith("data:image/png;base64,abc123");
    }

    @Test
    void mcpImageSupport_rejectsNonImageMimeType() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("media_type", "text/plain");
        source.put("data", "aGVsbG8=");

        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "image");
        block.put("source", source);

        List<Map<String, Object>> images = McpImageSupport.extractImages(block);
        assertThat(images).isEmpty();
    }

    // ---- AppConfig.McpOAuth ----

    @Test
    void appConfig_mcpOauth_defaultsAreNull() {
        AppConfig.McpOAuth oauth = new AppConfig.McpOAuth();
        assertThat(oauth.getClientId()).isNull();
        assertThat(oauth.getClientSecret()).isNull();
        assertThat(oauth.getTokenUrl()).isNull();
        assertThat(oauth.getScope()).isNull();
    }

    @Test
    void appConfig_mcpConfig_hasOauthField() {
        AppConfig.McpConfig mcp = new AppConfig.McpConfig();
        assertThat(mcp.getOauth()).isNotNull();
        mcp.getOauth().setClientId("my-client");
        mcp.getOauth().setTokenUrl("https://auth.example.com/token");
        assertThat(mcp.getOauth().getClientId()).isEqualTo("my-client");
        assertThat(mcp.getOauth().getTokenUrl()).isEqualTo("https://auth.example.com/token");
    }

    // ---- AcpEditApproval ----

    @Test
    void acpEditApproval_submitAndApprove() {
        AcpEditApproval approval = new AcpEditApproval();
        String id = approval.submit("/src/Foo.java", "- old\n+ new");
        assertThat(id).isNotBlank();
        assertThat(approval.getOutcome(id)).isEqualTo(AcpEditApproval.Outcome.PENDING);

        boolean approved = approval.approve(id);
        assertThat(approved).isTrue();
        assertThat(approval.getOutcome(id)).isEqualTo(AcpEditApproval.Outcome.APPROVED);
    }

    @Test
    void acpEditApproval_submitAndReject() {
        AcpEditApproval approval = new AcpEditApproval();
        String id = approval.submit("/src/Bar.java", "diff content");
        boolean rejected = approval.reject(id);
        assertThat(rejected).isTrue();
        assertThat(approval.getOutcome(id)).isEqualTo(AcpEditApproval.Outcome.REJECTED);
    }

    @Test
    void acpEditApproval_listPendingShowsOnlyPending() {
        AcpEditApproval approval = new AcpEditApproval();
        String id1 = approval.submit("/a.java", "diff1");
        String id2 = approval.submit("/b.java", "diff2");
        approval.approve(id1);

        List<Map<String, Object>> pending = approval.listPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("id")).isEqualTo(id2);
    }

    @Test
    void acpEditApproval_clearResolved_removesNonPending() {
        AcpEditApproval approval = new AcpEditApproval();
        String id1 = approval.submit("/a.java", "diff1");
        String id2 = approval.submit("/b.java", "diff2");
        approval.approve(id1);
        approval.clearResolved();

        assertThat(approval.listPending()).hasSize(1);
        assertThat(approval.getOutcome(id1)).isNull();
        assertThat(approval.getOutcome(id2)).isEqualTo(AcpEditApproval.Outcome.PENDING);
    }

    // ---- AcpEventBus ----

    @Test
    void acpEventBus_publishAndReceive() {
        AcpEventBus bus = new AcpEventBus();
        List<String> received = new ArrayList<String>();
        bus.subscribe((type, toolName, payload) -> received.add(type.name() + ":" + toolName));

        bus.publishToolStarted("read_file", null);
        bus.publishToolCompleted("read_file", 100L, null);
        bus.publishToolFailed("write_file", "permission denied");

        assertThat(received).containsExactly(
                "TOOL_CALL_STARTED:read_file",
                "TOOL_CALL_COMPLETED:read_file",
                "TOOL_CALL_FAILED:write_file");
    }

    @Test
    void acpEventBus_unsubscribe_stopsReceiving() {
        AcpEventBus bus = new AcpEventBus();
        List<String> received = new ArrayList<String>();
        AcpEventBus.Listener listener = (type, toolName, payload) -> received.add(toolName);
        bus.subscribe(listener);
        bus.publishToolStarted("tool_a", null);
        bus.unsubscribe(listener);
        bus.publishToolStarted("tool_b", null);

        assertThat(received).containsExactly("tool_a");
    }

    @Test
    void acpEventBus_listenerCount() {
        AcpEventBus bus = new AcpEventBus();
        assertThat(bus.listenerCount()).isEqualTo(0);
        AcpEventBus.Listener l = (t, n, p) -> {};
        bus.subscribe(l);
        assertThat(bus.listenerCount()).isEqualTo(1);
        bus.unsubscribe(l);
        assertThat(bus.listenerCount()).isEqualTo(0);
    }

    @Test
    void acpEventBus_faultyListenerDoesNotCrashBus() {
        AcpEventBus bus = new AcpEventBus();
        bus.subscribe((type, toolName, payload) -> { throw new RuntimeException("boom"); });
        List<String> received = new ArrayList<String>();
        bus.subscribe((type, toolName, payload) -> received.add(toolName));

        bus.publishToolStarted("safe_tool", null);
        assertThat(received).containsExactly("safe_tool");
    }

    // ---- AcpRequestQueue ----

    @Test
    void acpRequestQueue_enqueueAndPoll() {
        AcpRequestQueue queue = new AcpRequestQueue();
        AcpRequestQueue.QueuedRequest req = queue.enqueue("session-1", "hello");
        assertThat(req).isNotNull();
        assertThat(req.getSessionId()).isEqualTo("session-1");
        assertThat(req.getPrompt()).isEqualTo("hello");

        AcpRequestQueue.QueuedRequest polled = queue.poll();
        assertThat(polled).isNotNull();
        assertThat(polled.getPrompt()).isEqualTo("hello");
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void acpRequestQueue_fullQueueReturnsNull() {
        AcpRequestQueue queue = new AcpRequestQueue(2);
        queue.enqueue("s1", "p1");
        queue.enqueue("s2", "p2");
        AcpRequestQueue.QueuedRequest overflow = queue.enqueue("s3", "p3");
        assertThat(overflow).isNull();
        assertThat(queue.isFull()).isTrue();
    }

    @Test
    void acpRequestQueue_drainAll() {
        AcpRequestQueue queue = new AcpRequestQueue();
        queue.enqueue("s1", "p1");
        queue.enqueue("s2", "p2");
        List<AcpRequestQueue.QueuedRequest> drained = queue.drainAll();
        assertThat(drained).hasSize(2);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void acpRequestQueue_stats() {
        AcpRequestQueue queue = new AcpRequestQueue(10);
        queue.enqueue("s1", "p1");
        queue.enqueue("s2", "p2");
        queue.poll();

        Map<String, Object> stats = queue.stats();
        assertThat(stats.get("size")).isEqualTo(Integer.valueOf(1));
        assertThat(stats.get("capacity")).isEqualTo(Integer.valueOf(10));
        assertThat(stats.get("total_enqueued")).isEqualTo(Integer.valueOf(2));
        assertThat(stats.get("total_dequeued")).isEqualTo(Integer.valueOf(1));
    }
}
