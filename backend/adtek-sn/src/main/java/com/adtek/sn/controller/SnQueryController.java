package com.adtek.sn.controller;
import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.SnExportDTO;
import com.adtek.sn.query.SnItemQuery;
import com.adtek.sn.service.*;
import com.adtek.sn.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

@RestController @RequestMapping("/api/v1/sn") public class SnQueryController {
    private final SnQueryService query;private final DashboardService dashboard;
    public SnQueryController(SnQueryService query,DashboardService dashboard){this.query=query;this.dashboard=dashboard;}
    @Operation(summary="SN只读明细",description="所有旧筛选、精确SN和稳定游标；当前视图会随合法业务更新，固定一致导出使用exports。")
    @GetMapping("/items") public Result<CursorResult<SnQueryItemVO>> items(@ParameterObject @ModelAttribute SnItemQuery filters,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int size,HttpServletRequest request){return Result.success(query.items(filters,cursor,size),TraceFilter.current(request));}
    @GetMapping("/items/count") public Result<SnCountVO> count(@ParameterObject @ModelAttribute SnItemQuery filters,HttpServletRequest request){return Result.success(query.count(filters),TraceFilter.current(request));}
    @PostMapping("/exports") @ResponseStatus(HttpStatus.ACCEPTED) public Result<TaskVO> export(@Valid @RequestBody SnExportDTO command,HttpServletRequest request){return Result.success(query.export(command),TraceFilter.current(request));}
    @GetMapping("/exports") public Result<PageResult<SnExportVO>> history(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return Result.success(query.exports(page,size),TraceFilter.current(request));}
    @GetMapping("/exports/operations/{format}/{id}") public Result<TaskVO> operation(@PathVariable String format,@PathVariable String id,HttpServletRequest request){return Result.success(query.operation(format,id),TraceFilter.current(request));}
    @GetMapping("/dashboard") public Result<DashboardVO> dashboard(HttpServletRequest request){return Result.success(dashboard.overview(),TraceFilter.current(request));}
}
