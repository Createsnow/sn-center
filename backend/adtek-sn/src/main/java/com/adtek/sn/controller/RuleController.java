package com.adtek.sn.controller;
import com.adtek.common.core.*;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.RuleService;
import com.adtek.sn.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
/** 协议入口；授权和事务由Service执行，页面按钮不能代替用例权限。 */
@RestController @Tag(name="编码规则") @RequestMapping(value="/api/v1/sn/rules",produces="application/json")
public class RuleController {
 private final RuleService service;
 public RuleController(RuleService service){this.service=service;}
 @GetMapping @Operation(summary="全部工厂rule:list权限；绑定/关键字筛选稳定分页")
 public Result<PageResult<RuleVO>> list(@Valid @ModelAttribute SnPageQuery query,HttpServletRequest request){return Result.success(service.list(query),TraceFilter.current(request));}
 @GetMapping("/{id}/versions") @Operation(summary="全部工厂rule:list权限；历史版本倒序完整分页")
 public Result<PageResult<RuleVersionVO>> versions(@PathVariable String id,@Valid @ModelAttribute SnPageQuery query,HttpServletRequest request){return Result.success(service.versions(id,query),TraceFilter.current(request));}
 @PostMapping @Operation(summary="全部工厂create权限，代码和绑定唯一，首版与审计同事务")
 public Result<RuleVO> create(@Valid @RequestBody RuleSaveDTO command,HttpServletRequest request){return Result.success(service.create(command),TraceFilter.current(request));}
 @PostMapping("/{id}/update") @Operation(summary="全部工厂update权限；只改名不出新版本，未用原位改，已用新增版；revision冲突409")
 public Result<RuleChangeVO> update(@PathVariable String id,@Valid @RequestBody RuleSaveDTO command,HttpServletRequest request){return Result.success(service.update(id,command),TraceFilter.current(request));}
 @PostMapping("/preview") @Operation(summary="全部工厂preview权限；纯样例与容量计算，不写流水/额度")
 public Result<RulePreviewVO> preview(@Valid @RequestBody RulePreviewDTO command,HttpServletRequest request){return Result.success(service.preview(command),TraceFilter.current(request));}
 @GetMapping("/effective") @Operation(summary="全部工厂effective权限；PI高于客户高于通用，没有规则data为null")
 public Result<RuleVersionVO> effective(@RequestParam String pi,@RequestParam(required=false) String customer,HttpServletRequest request){return Result.success(service.effective(pi,customer),TraceFilter.current(request));}
}

