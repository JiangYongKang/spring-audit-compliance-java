package com.github.highcumontoa.audit.model;

/**
 * 事件接入结果（稳定的三态：已接收 / 重复 / 拒绝）。
 */
public record IngestResult(Status status, RejectionReason rejectionReason, String detail) {

    public enum Status {
        ACCEPTED, DUPLICATE, REJECTED
    }

    public static IngestResult accepted() {
        return new IngestResult(Status.ACCEPTED, null, null);
    }

    public static IngestResult duplicate() {
        return new IngestResult(Status.DUPLICATE, null, "event already recorded");
    }

    public static IngestResult rejected(RejectionReason reason, String detail) {
        return new IngestResult(Status.REJECTED, reason, detail);
    }
}
