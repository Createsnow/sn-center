package com.adtek.sn.query;
import jakarta.validation.constraints.*;
public record TransferQuery(String factoryId,@Size(max=128) String pi,String status,@Min(1) int page,@Min(1) @Max(100) int size) {}
