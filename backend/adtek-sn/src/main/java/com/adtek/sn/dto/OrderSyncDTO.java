package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/** billNo未提供/null表示全量；显式空白在用例中拒绝，单据身份仍由ERP返回并校验。 */
public record OrderSyncDTO(@Size(max = 64) String billNo) {}
