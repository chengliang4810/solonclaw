package com.jimuqu.solon.claw.tui;

/** Receives durable TUI gateway events from runtime integrations. */
public interface TuiGatewayEventSink {
    void publish(TuiEvent event);
}
