package com.jimuqu.solon.claw.kanban;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Claimed notification events plus cursor state for reliable delivery. */
public class KanbanNotifyClaim {
    private String oldCursor;
    private String newCursor;
    private List<KanbanEventRecord> events = new ArrayList<KanbanEventRecord>();

    public String getOldCursor() {
        return oldCursor;
    }

    public void setOldCursor(String oldCursor) {
        this.oldCursor = oldCursor;
    }

    public String getNewCursor() {
        return newCursor;
    }

    public void setNewCursor(String newCursor) {
        this.newCursor = newCursor;
    }

    public List<KanbanEventRecord> getEvents() {
        return events == null ? Collections.<KanbanEventRecord>emptyList() : events;
    }

    public void setEvents(List<KanbanEventRecord> events) {
        this.events = events;
    }
}
