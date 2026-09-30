package com.adtek.sn.domain;
import java.time.Instant;
/** PI控制行；activeTaskId仅由同一事务持有者设置/清理，水位不受可丢失进度影响。 */
public record PiCounterEntity(String pi,long lastSeq,long generatedQty,long importedQty,Long startSeq,boolean startLocked,Long lockedBy,Instant lockedAt,String activeTaskId) {}
