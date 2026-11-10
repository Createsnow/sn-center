package com.adtek.sn.vo;

import java.util.List;

/** 切换「对接 MES」前的提示：受影响枚数与（关闭时）阻塞项。 */
public class FactoryToggleVO {
    public String factoryCode;
    public boolean mesEnabled;
    public long allocated;
    public long unbatched;
    public Blockers blockers;
    public Boolean canEnable;

    /** 关闭对接的阻塞。 */
    public static class Blockers {
        public long tasks;
        public List<BatchBrief> batches;
        public long batchTotal;
        public long batchQty;
        public long reassignQty;
    }

    /** 未确认或失败的批次。 */
    public static class BatchBrief {
        public String batchNo;
        public long qty;
        public String result;
    }
}
