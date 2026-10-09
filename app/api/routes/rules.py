"""规则模板：只有总部维护；任何账户可查看某 PI 当前生效的规则。"""

from fastapi import APIRouter, Query

from app.api.deps import AdminDep, UserDep
from app.schemas.rule import RuleIn, RulePiBindIn, RulePreviewIn, RuleSpecIn, RuleUpdateIn
from app.services import orders, rules

router = APIRouter(prefix="/rules", tags=["rules"])


def _spec(b: RuleSpecIn) -> rules.SpecIn:
    return rules.SpecIn(b.prefix, b.suffix, b.base, b.seq_len, b.charset)


@router.get("")
def rule_list(cu: AdminDep, scope: str | None = None, q: str | None = None) -> list:
    return rules.list_rules(scope, q)


@router.post("")
def rule_create(body: RuleIn, cu: AdminDep) -> dict:
    return rules.create(cu, body.rule_code, body.rule_name, body.bind_scope, body.bind_value, _spec(body))


@router.put("/pi-binding", summary="给 PI 指定规则（只能选通用规则）：之后生成默认用它")
def rule_pi_bind(body: RulePiBindIn, cu: AdminDep) -> dict:
    return rules.bind_pi(cu, body.pi_no, body.rule_id)


@router.delete("/pi-binding", summary="解除给 PI 指定的规则：下次生成前重新选择")
def rule_pi_unbind(cu: AdminDep, pi: str = Query(...)) -> dict:
    return rules.unbind_pi(cu, pi)


@router.put(
    "/{id}",
    summary="修改：只改名称不出版本；改编码参数时未生成过号就地修改，否则另出一版；可改绑定，改成通用即解绑",
)
def rule_update(id: int, body: RuleUpdateIn, cu: AdminDep) -> dict:
    return rules.update(cu, id, body.rule_name, _spec(body), body.bind_scope, body.bind_value)


@router.post("/preview")
def rule_preview(body: RulePreviewIn, cu: AdminDep) -> dict:
    return rules.preview(_spec(body), body.start)


@router.get("/effective", summary="某 PI 当前生效的规则（按 PI ＞ 按客户 ＞ 给该 PI 指定的 ＞ 通用）")
def rule_effective(cu: UserDep, pi: str = Query(...), customer: str | None = None):
    c = orders.customer_of_pi(pi.strip()) if customer is None or not customer.strip() else customer.strip()
    r = rules.resolve(pi.strip(), c)
    return None if r is None else rules.rule_info(r)
