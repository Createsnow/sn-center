"""规则库与规则选取（F-08）、按客户绑定规则接口（F-22）。
系统管理员、普通操作员（全局规则只能选用，授权组织的规则可增删改）；按客户绑定只有系统管理员。"""

from fastapi import APIRouter

from app.api.deps import AdminDep, OperatorDep
from app.schemas.rule import (
    CustomerBindIn,
    CustomerTemplateIn,
    LineClearIn,
    LineSelectIn,
    PiBindIn,
    RuleIn,
    RulePreviewIn,
    RuleSpecIn,
    RuleUpdateIn,
)
from app.services import rules

router = APIRouter(prefix="/rules", tags=["rules"])


def _spec(b: RuleSpecIn) -> rules.SpecIn:
    return rules.SpecIn(
        b.prefix,
        b.suffix,
        b.base,
        b.seq_len,
        b.charset,
        b.with_length,
        b.length_pad,
        b.sep_before,
        b.sep_after,
        b.with_unit,
    )


@router.get("", summary="规则库：按全部 / 未分类 / 客户 / PI 筛选，可按编码、名称、客户、PI 搜索")
def rule_list(
    cu: OperatorDep, q: str | None = None, category: str | None = None, category_value: str | None = None
) -> list:
    return rules.list_rules(q, category, category_value, cu.read_factories(None))


@router.post("")
def rule_create(body: RuleIn, cu: OperatorDep) -> dict:
    return rules.create(
        cu, body.rule_code, body.rule_name, _spec(body), body.category_scope, body.category_value, body.factory_code
    )


@router.put(
    "/{id:int}",
    summary="修改：从没生成过号的版本就地修改；生成过的改格式须另出一版并换新前缀；只改名称或分类不另出版；"
    "普通操作员只能改授权组织的规则",
)
def rule_update(id: int, body: RuleUpdateIn, cu: OperatorDep) -> dict:
    return rules.update(cu, id, body.rule_name, _spec(body), body.category_scope, body.category_value)


@router.delete(
    "/{id:int}", summary="删除：只能删从没生成过号、也没指定给任何 PI 的规则；普通操作员只能删授权组织的规则"
)
def rule_delete(id: int, cu: OperatorDep) -> dict:
    return rules.delete(cu, id)


@router.post("/preview", summary="示例号：前 5 个和最大号（米数、单位用示例值）、最大流水")
def rule_preview(body: RulePreviewIn, cu: OperatorDep) -> dict:
    return rules.preview(_spec(body), body.length, body.unit)


@router.get("/options", summary="生成页规则库选项：推荐的排前面；给出本 PI 用这条规则时的版本与是否须换前缀")
def rule_options(cu: OperatorDep, pi: str | None = None, customer: str | None = None) -> list:
    return rules.options(pi, customer, cu.read_factories(None))


@router.post("/line-select", summary="物料行选定 / 改选规则：规则库（rule_id，必要时 new_prefix）或自定义（custom）")
def rule_line_select(body: LineSelectIn, cu: OperatorDep) -> dict:
    lines = [x.model_dump() for x in body.lines or []]
    custom = None if body.custom is None else body.custom.model_dump()
    return rules.select_for_lines(cu, body.pi_no, lines, body.rule_id, custom, body.new_prefix)


@router.post("/line-clear", summary="物料行改回按 PI / 客户默认")
def rule_line_clear(body: LineClearIn, cu: OperatorDep) -> dict:
    return rules.clear_lines(cu, body.pi_no, [x.model_dump() for x in body.lines or []])


@router.put("/pi-binding", summary="给 PI 绑定规则作各物料行的默认（版本已归属别的 PI 时须填 new_prefix）")
def rule_pi_bind(body: PiBindIn, cu: OperatorDep) -> dict:
    return rules.bind_pi(cu, body.pi_no, body.rule_id, body.new_prefix)


@router.delete("/pi-binding", summary="解除 PI 绑定：规则转为通用模板，已选过规则的物料行不受影响")
def rule_pi_unbind(cu: OperatorDep, pi: str | None = None) -> dict:
    return rules.unbind_pi(cu, pi)


@router.post("/customer-template", summary="用客户绑定的规则作模板：为本 PI 换前缀另出版本并绑定给本 PI")
def rule_customer_template(body: CustomerTemplateIn, cu: OperatorDep) -> dict:
    return rules.adopt_customer_template(cu, body.pi_no, body.new_prefix)


@router.post(
    "/customer-binding",
    tags=["open"],
    summary="按客户绑定规则（F-22，设置按客户绑定的唯一入口）：每个客户只能绑一条，已绑其他规则时拒绝",
)
def rule_customer_bind(body: CustomerBindIn, cu: AdminDep) -> dict:
    return rules.bind_customer(cu, body.customer_code, body.rule_id, body.rule_code)


@router.delete("/customer-binding", summary="在生成页解除客户绑定：规则转为通用模板（客户跨组织，只有系统管理员）")
def rule_customer_unbind(cu: AdminDep, customer: str | None = None) -> dict:
    return rules.unbind_customer(cu, customer)
