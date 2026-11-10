package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.dto.LineSelectDTO;
import com.adtek.sn.dto.PiBindDTO;
import com.adtek.sn.dto.PiLinesDTO;
import com.adtek.sn.dto.RuleCreateDTO;
import com.adtek.sn.dto.RulePreviewDTO;
import com.adtek.sn.dto.RuleUpdateDTO;
import com.adtek.sn.service.RuleService;
import com.adtek.sn.vo.RuleActionVO;
import com.adtek.sn.vo.RuleOptionVO;
import com.adtek.sn.vo.RulePreviewVO;
import com.adtek.sn.vo.RuleViewVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 规则库与规则选取。 */
@RestController
@RequestMapping("/api/v1/sn/rules")
public class RuleController {
    private final RuleService rules;

    public RuleController(RuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public Result<List<RuleViewVO>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String categoryValue) {
        return Result.ok(rules.list(q, category, categoryValue));
    }

    @PostMapping
    public Result<RuleViewVO> create(@RequestBody RuleCreateDTO body) {
        return Result.ok(rules.create(body));
    }

    @PostMapping("/{id}/update")
    public Result<RuleActionVO> update(@PathVariable long id, @RequestBody RuleUpdateDTO body) {
        return Result.ok(rules.update(id, body));
    }

    @PostMapping("/{id}/delete")
    public Result<RuleActionVO> delete(@PathVariable long id) {
        return Result.ok(rules.delete(id));
    }

    @PostMapping("/preview")
    public Result<RulePreviewVO> preview(@RequestBody RulePreviewDTO body) {
        return Result.ok(rules.preview(body));
    }

    @GetMapping("/options")
    public Result<List<RuleOptionVO>> options(
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String customer) {
        return Result.ok(rules.options(pi, customer));
    }

    @PostMapping("/line-select")
    public Result<RuleActionVO> lineSelect(@RequestBody LineSelectDTO body) {
        return Result.ok(rules.selectForLines(body));
    }

    @PostMapping("/line-clear")
    public Result<RuleActionVO> lineClear(@RequestBody PiLinesDTO body) {
        return Result.ok(rules.clearLines(body));
    }

    @PostMapping("/pi-binding")
    public Result<RuleActionVO> bind(@RequestBody PiBindDTO body) {
        return Result.ok(rules.bindPi(body));
    }

    @PostMapping("/pi-binding/delete")
    public Result<RuleActionVO> unbind(@RequestParam(required = false) String pi) {
        return Result.ok(rules.unbindPi(pi));
    }
}
