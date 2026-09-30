package com.adtek.sn.controller;
import com.adtek.common.core.*;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.FactoryService;
import com.adtek.sn.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
/** 协议入口；授权和事务由Service执行，页面按钮不能代替用例权限。 */
@RestController @Tag(name="工厂管理") @RequestMapping(value="/api/v1/sn/factories",produces="application/json")
public class FactoryController {
 private final FactoryService service;
 public FactoryController(FactoryService service){this.service=service;}
 @GetMapping @Operation(summary="当前factory:list工厂范围内的目录分页")
 public Result<PageResult<FactoryVO>> list(@Valid @ModelAttribute SnPageQuery query,HttpServletRequest request){return Result.success(service.list(query),TraceFilter.current(request));}
 @PostMapping @Operation(summary="全部工厂create权限手工建档；代码名称唯一，失败整体回滚")
 public Result<FactoryVO> create(@Valid @RequestBody FactoryCreateDTO command,HttpServletRequest request){return Result.success(service.create(command),TraceFilter.current(request));}
 @PostMapping("/sync") @Operation(summary="全部工厂sync权限；K3完整拉取后事务发布，冲突明确返回")
 public Result<FactorySyncVO> synchronize(HttpServletRequest request){return Result.success(service.synchronize(),TraceFilter.current(request));}
 @GetMapping("/organizations") @Operation(summary="全部工厂map权限；当前ERP目录完整分页，依赖失败明确拒绝")
 public Result<PageResult<OrganizationVO>> organizations(@Valid @ModelAttribute SnPageQuery query,HttpServletRequest request){return Result.success(service.organizations(query),TraceFilter.current(request));}
 @PostMapping("/{id}/organization") @Operation(summary="全部工厂map权限；显式稳定组织映射，版本冲突409，不按同名认领")
 public Result<FactoryVO> bind(@PathVariable String id,@Valid @RequestBody OrganizationMapDTO command,HttpServletRequest request){return Result.success(service.bind(id,command),TraceFilter.current(request));}
}

