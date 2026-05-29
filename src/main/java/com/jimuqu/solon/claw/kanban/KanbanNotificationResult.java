package com.jimuqu.solon.claw.kanban;

import java.util.ArrayList;
import java.util.List;

/** Result summary for one Kanban notification delivery tick. */
public class KanbanNotificationResult {
    private int subscriptions;
    private int claimedEvents;
    private int deliveredEvents;
    private int failedEvents;
    private int removedSubscriptions;
    private final List<String> errors = new ArrayList<String>();

    public int getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(int subscriptions) {
        this.subscriptions = subscriptions;
    }

    public int getClaimedEvents() {
        return claimedEvents;
    }

    public void addClaimedEvents(int claimedEvents) {
        this.claimedEvents += Math.max(0, claimedEvents);
    }

    public int getDeliveredEvents() {
        return deliveredEvents;
    }

    public void addDeliveredEvent() {
        this.deliveredEvents++;
    }

    public int getFailedEvents() {
        return failedEvents;
    }

    public void addFailedEvent() {
        this.failedEvents++;
    }

    public int getRemovedSubscriptions() {
        return removedSubscriptions;
    }

    public void addRemovedSubscription() {
        this.removedSubscriptions++;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void addError(String error) {
        if (error != null && errors.size() < 20) {
            errors.add(error);
        }
    }
}
