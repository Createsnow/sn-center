package com.adtek.sn.dto;
import jakarta.validation.constraints.*;
import java.util.List;
/** 精确起号字符串；清单顺序有意义，重复拒绝而非静默去重；fileName仅显示元数据，不是路径。 */
public record PiInitializationDTO(@NotBlank String operationId,@NotBlank @Size(max=128) String pi,
 @Pattern(regexp="[1-9][0-9]{0,18}") String start,@NotNull List<@NotNull String> sns,
 @Size(max=64) String customer,@Size(max=128) String material,@Pattern(regexp="[1-9][0-9]{0,18}") String factoryId,@Size(max=255) String fileName) {}
