package com.adtek.sn.query;

import java.util.List;

/** SN 查询条件（原系统 query.Filter）及按授权范围、流水范围换算后的条件。 */
public class SnItemQuery {
    public String factoryCode;
    public String customerCode;
    public String pi;
    public String billNo;
    public String materialCode;
    public String length;
    public String status;
    public String sn;
    public boolean piAll;
    public String seqFrom;
    public String seqTo;

    /** 换算后：授权范围（null = 全部）、是否只看有组织的号（同 PI 各组织）、流水范围。 */
    public List<String> scope;

    public boolean allOfPi;
    public String rangePi;
    public Long rangeLo;
    public Long rangeHi;
}
