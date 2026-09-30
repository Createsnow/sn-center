package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/** billNo未提供/null/空白表示全量；非空只替换该显示单号，稳定内码仍由ERP返回并校验。 */
public record OrderSyncDTO(@Size(max = 64) String billNo) {}
