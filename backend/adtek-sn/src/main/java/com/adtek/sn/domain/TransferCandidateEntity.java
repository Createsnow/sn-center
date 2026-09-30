package com.adtek.sn.domain;

public record TransferCandidateEntity(
        long total,
        long transferable,
        long toAcquire,
        long toPrint,
        long printed,
        long applying,
        long pendingAllocation) {}
