package com.adtek.sn.query;
import jakarta.validation.constraints.*;
/** 有界稳定分页；ID/客户/PI精确筛选，q是转义后的包含搜索，不解释通配符。 */
public class SnPageQuery {
 private int page=1; private int size=20; private String q; private String customer; private String pi; private String factoryId; private String scope;
 @Min(1) @Max(10000) public int getPage(){return page;} public void setPage(int value){page=value;}
 @Min(1) @Max(100) public int getSize(){return size;} public void setSize(int value){size=value;}
 @Size(max=128) public String getQ(){return q;} public void setQ(String value){q=value;}
 @Size(max=64) public String getCustomer(){return customer;} public void setCustomer(String value){customer=value;}
 @Size(max=128) public String getPi(){return pi;} public void setPi(String value){pi=value;}
 public String getFactoryId(){return factoryId;} public void setFactoryId(String value){factoryId=value;}
 public String getScope(){return scope;} public void setScope(String value){scope=value;}
}
