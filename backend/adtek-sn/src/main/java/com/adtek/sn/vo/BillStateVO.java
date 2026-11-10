package com.adtek.sn.vo;

/** 单据状态：暂停交付、离开快照、PI 冻结、未处理提醒数、是否已出号（有台账）。 */
public class BillStateVO {
    public String suspend;
    public String leaveState;
    public boolean piFrozen;
    public long openAlerts;
    public boolean issued;

    public static BillStateVO empty() {
        return new BillStateVO();
    }
}
