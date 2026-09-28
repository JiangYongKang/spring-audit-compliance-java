package com.github.highcumontoa.springauditcompliancejava.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.highcumontoa.springauditcompliancejava.dto.RuleRequest;
import com.github.highcumontoa.springauditcompliancejava.dto.RuleView;
import com.github.highcumontoa.springauditcompliancejava.service.RuleService;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService rules;

    public RuleController(RuleService rules) {
        this.rules = rules;
    }

    /** 新增规则（生成版本 1，active=true）。 */
    @PostMapping
    public RuleView create(@RequestBody RuleRequest request) {
        return rules.create(request);
    }

    /** 更新规则：追加新版本并停用旧版本，返回新版本。 */
    @PutMapping("/{ruleId}")
    public RuleView update(@PathVariable String ruleId, @RequestBody RuleRequest request) {
        return rules.update(ruleId, request);
    }

    /** 列出所有当前生效规则（每个规则仅最新版本）。 */
    @GetMapping
    public List<RuleView> list() {
        return rules.listActive();
    }

    /** 查询某规则的完整版本链（用于追溯与选择重放版本）。 */
    @GetMapping("/{ruleId}/versions")
    public List<RuleView> versions(@PathVariable String ruleId) {
        return rules.history(ruleId);
    }
}
