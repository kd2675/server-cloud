package com.example.cloud.common.logback;

import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;

import java.util.List;

public class PrintOnlyWarningLogbackStatusListener extends OnConsoleStatusListener {

    private static final int LOG_LEVEL = Status.WARN;

    @Override
    public void addStatusEvent(Status status) {
        if (status.getLevel() == LOG_LEVEL) {
            super.addStatusEvent(status);
        }
    }

    @Override
    public void start() {
        final List<Status> statuses = context.getStatusManager().getCopyOfStatusList();
        for (Status status : statuses) {
            if (status.getLevel() == LOG_LEVEL) {
                super.start();
            }
        }
    }
}