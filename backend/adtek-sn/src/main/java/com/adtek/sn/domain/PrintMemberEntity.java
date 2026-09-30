package com.adtek.sn.domain;
/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record PrintMemberEntity(String printId,long ordinal,java.time.LocalDate genMonth,String itemId,String batchId,String snapshot) {}
