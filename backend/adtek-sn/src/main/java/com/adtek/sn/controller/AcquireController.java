package com.adtek.sn.controller;
import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.AcquireQuery;
import com.adtek.sn.service.*;
import com.adtek.sn.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.Operation;

/** 领取页协议适配；角色/工厂、范围、幂等和事务由共同用例保证。 */
@RestController @RequestMapping("/api/v1/sn") public class AcquireController {
 private final AcquireService acquire;private final PrintService prints;
 public AcquireController(AcquireService acquire,PrintService prints){this.acquire=acquire;this.prints=prints;}
 private AcquireQuery query(String factory,String pi,String mode,String material,String status,boolean all,int page,int size){return new AcquireQuery(factory,pi,mode,material,status,all,page,size);}
 @GetMapping("/acquisitions/pis") public Result<PageResult<AcquireSummaryVO>> pis(@RequestParam(required=false) String factoryId,@RequestParam(required=false) String pi,@RequestParam(defaultValue="false") boolean piAll,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return Result.success(acquire.summaries(query(factoryId,pi,"ALL",null,null,piAll,page,size)),TraceFilter.current(request));}
 @GetMapping("/acquisitions/segments") public Result<PageResult<AcquireSegmentVO>> segments(@RequestParam(required=false) String factoryId,@RequestParam(required=false) String pi,@RequestParam(defaultValue="ALL") String materialMode,@RequestParam(required=false) String material,@RequestParam(required=false) String status,@RequestParam(defaultValue="false") boolean piAll,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return Result.success(acquire.segments(query(factoryId,pi,materialMode,material,status,piAll,page,size)),TraceFilter.current(request));}
 @Operation(summary="整工厂+PI领取",description="全部合格待领取成员；零结果持久保存，无空有效批次。返回原任务，成功后读取分类结果。")
 @PostMapping("/acquisitions") @ResponseStatus(HttpStatus.ACCEPTED) public Result<TaskVO> take(@Valid @RequestBody AcquisitionDTO command,HttpServletRequest request){return Result.success(acquire.take(command),TraceFilter.current(request));}
 @GetMapping("/acquisitions/operations/{kind}/{id}") public Result<TaskVO> operation(@PathVariable String kind,@PathVariable String id,HttpServletRequest request){return Result.success(acquire.operation(kind,id),TraceFilter.current(request));}
 @GetMapping("/acquisitions/results/{id}") public Result<AcquireWorkVO> result(@PathVariable String id,HttpServletRequest request){return Result.success(acquire.result(id),TraceFilter.current(request));}
 @GetMapping("/acquisitions/results/{id}/items") public Result<CursorResult<AcquireResultVO>> results(@PathVariable String id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int size,HttpServletRequest request){return Result.success(acquire.results(id,cursor,size),TraceFilter.current(request));}
 @GetMapping("/acquire-batches") public Result<PageResult<AcquireBatchVO>> batches(@RequestParam(required=false) String factoryId,@RequestParam(required=false) String pi,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return Result.success(acquire.batches(query(factoryId,pi,"ALL",null,null,false,page,size)),TraceFilter.current(request));}
 @GetMapping("/acquire-batches/{id}/items") public Result<CursorResult<AcquireItemVO>> items(@PathVariable String id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int size,HttpServletRequest request){return Result.success(acquire.items(id,cursor,size),TraceFilter.current(request));}
 @Operation(summary="确认整工厂+PI打印并准备固定文件",description="同事务保存PRINTED/固定成员/成功操作与文件任务；operationState=SUCCEEDED不代表文件已就绪。CSV与XLSX独立恢复，下载复核原工厂当前权限。")
 @PostMapping("/prints") @ResponseStatus(HttpStatus.ACCEPTED) public Result<TaskVO> print(@Valid @RequestBody AcquisitionDTO command,HttpServletRequest request){return Result.success(prints.confirm(command),TraceFilter.current(request));}
 @GetMapping("/prints/operations/{id}") public Result<TaskVO> printOperation(@PathVariable String id,HttpServletRequest request){return Result.success(prints.operation(id),TraceFilter.current(request));}
 @GetMapping("/prints") public Result<PageResult<PrintVO>> prints(@RequestParam(required=false) String factoryId,@RequestParam(required=false) String pi,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return Result.success(prints.history(query(factoryId,pi,"ALL",null,null,false,page,size)),TraceFilter.current(request));}
 @GetMapping("/prints/{id}") public Result<PrintVO> print(@PathVariable String id,HttpServletRequest request){return Result.success(prints.get(id),TraceFilter.current(request));}
}
