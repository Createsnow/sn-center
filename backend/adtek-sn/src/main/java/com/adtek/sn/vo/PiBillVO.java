package com.adtek.sn.vo;

/** （PI，单据）对；单据汇总标脏用。 */
public class PiBillVO {
    public String piNo;
    public String billNo;
    public long ver;

    public PiBillVO() {}

    public PiBillVO(String piNo, String billNo) {
        this.piNo = piNo;
        this.billNo = billNo;
    }
}
