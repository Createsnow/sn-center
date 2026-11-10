package com.adtek.framework.security;

import com.adtek.framework.task.RecurringTask;

import org.springframework.stereotype.Component;

/** 每日分批清理过期超过 1 天的服务端会话（读请求不写会话表，过期行只在这里删除）。 */
@Component
public class SessionPurgeTask implements RecurringTask {
    private final SessionService sessions;

    public SessionPurgeTask(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return "framework-session-purge";
    }

    @Override
    public String cron() {
        return "0 40 3 * * *";
    }

    @Override
    public void run() {
        sessions.purgeExpired();
    }
}
