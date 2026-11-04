package com.spiketrade.seckill;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ReconcileReport {

    public static final String TYPE_A = "A";
    public static final String TYPE_B = "B";
    public static final String TYPE_C = "C";
    public static final String TYPE_D = "D";
    public static final String TYPE_E = "E";

    public static final String ACTION_REPAIRED = "REPAIRED";
    public static final String ACTION_REQUEUED = "REQUEUED";
    public static final String ACTION_MARKED_DEAD = "MARKED_DEAD";
    public static final String ACTION_REPORT = "REPORT";

    private Long voucherId;

    private Integer redisStock;

    private Long redisReservationCount;

    private Integer dbStock;

    private Long orderCount;

    private boolean consistent;

    private int repairedCount;

    private List<Issue> issues = new ArrayList<>();

    public void addIssue(String type, String subject, String action, String detail) {
        issues.add(new Issue(type, subject, action, detail));
        if (ACTION_REPAIRED.equals(action)
                || ACTION_REQUEUED.equals(action)
                || ACTION_MARKED_DEAD.equals(action)) {
            repairedCount++;
        }
    }

    @Data
    public static class Issue {

        private String type;

        private String subject;

        private String action;

        private String detail;

        public Issue() {
        }

        public Issue(String type, String subject, String action, String detail) {
            this.type = type;
            this.subject = subject;
            this.action = action;
            this.detail = detail;
        }
    }
}