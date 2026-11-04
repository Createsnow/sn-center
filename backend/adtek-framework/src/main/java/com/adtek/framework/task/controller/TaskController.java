package com.adtek.framework.task.controller;

import com.adtek.common.core.Result;
import com.adtek.framework.task.service.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.framework.web.TraceFilter;

import jakarta.servlet.http.*;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 设施协议入口；所有者及业务对象资格由Service统一复核，不开放任意任务提交。 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {
    private final DurableTaskService tasks;
    private final TaskFileService files;

    public TaskController(DurableTaskService tasks, TaskFileService files) {
        this.tasks = tasks;
        this.files = files;
    }

    @GetMapping("/tasks/{id}")
    public Result<TaskVO> get(@PathVariable String id, HttpServletRequest request) {
        return Result.success(tasks.get(id), TraceFilter.current(request));
    }

    @PostMapping("/tasks/{id}/cancel")
    public Result<TaskVO> cancel(@PathVariable String id, HttpServletRequest request) {
        return Result.success(tasks.cancel(id), TraceFilter.current(request));
    }

    @PostMapping("/tasks/{id}/recover")
    public Result<TaskVO> recover(@PathVariable String id, HttpServletRequest request) {
        return Result.success(tasks.recoverFile(id), TraceFilter.current(request));
    }

    @GetMapping("/files/{id}")
    public void download(@PathVariable String id, HttpServletResponse response)
            throws java.io.IOException {
        try (var file = files.open(id)) {
            response.setContentType(file.mediaType());
            response.setContentLengthLong(file.bytes());
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(file.filename()).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = file.input().read(buffer)) != -1) {
                response.getOutputStream().write(buffer, 0, count);
            }
        } catch (java.io.IOException | RuntimeException error) {
            if (!response.isCommitted()) {
                response.reset();
                response.setHeader("X-Trace-Id", org.slf4j.MDC.get("traceId"));
                throw error;
            }
            // 文件已输出后不追加JSON污染产物；Content-Length使客户端发现截断，告警不带请求或秘密。
            org.slf4j.LoggerFactory.getLogger(TaskController.class)
                    .warn(
                            "File stream interrupted traceId={} exceptionType={}",
                            org.slf4j.MDC.get("traceId"),
                            error.getClass().getSimpleName());
            response.getOutputStream().close();
        }
    }
}
