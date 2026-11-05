"""SN 导入：系统管理员、普通操作员（只能导入授权组织的单据）。

上传的 xlsx 直接作请求体（Content-Type: application/octet-stream），文件名放在查询参数 file_name；
先 /check 检查（不落库），再「确定导入」POST /sn-import（有一行不能导入整个文件都不导入）。
表头按列名认列（顺序不限，其他列不读），见 sn_import.COLUMNS。
"""

from fastapi import APIRouter, Request

from app.api.deps import OperatorDep, download, page_of, run_sync
from app.core.errors import ErrorCode, biz
from app.schemas.common import OptInt
from app.services import sn_import

router = APIRouter(prefix="/sn-import", tags=["sn-import"])


async def _content(request: Request) -> bytes:
    """读请求体；超过上限直接拒绝（按 Content-Length 先挡，再按实际长度）。"""
    n = request.headers.get("content-length")
    if n is not None and n.isdigit() and int(n) > sn_import.MAX_BYTES:
        raise biz(ErrorCode.IMPORT_FILE_TOO_LARGE, max=sn_import.MAX_MB)
    body = await request.body()
    if len(body) > sn_import.MAX_BYTES:
        raise biz(ErrorCode.IMPORT_FILE_TOO_LARGE, max=sn_import.MAX_MB)
    return body


@router.get(
    "/template",
    summary="导入模板 xlsx：固定列 PI、单据编号、SN、生产组织（必须有）+ 物料编码、附件项次、米数、客户编码（选填）",
)
def import_template(cu: OperatorDep):
    return download("SN导入模板", sn_import.template())


@router.post(
    "/check",
    summary="检查导入文件（不落库）：请求体为 xlsx；返回总行数、可导入行数、问题行（逐行列出原因）",
)
async def import_check(request: Request, cu: OperatorDep) -> dict:
    return await run_sync(sn_import.check, cu, await _content(request))


@router.post(
    "",
    summary="确定导入：请求体为 xlsx，file_name 为文件名；重新检查，有问题行整个文件不导入，否则全部登记",
)
async def import_run(request: Request, cu: OperatorDep, file_name: str | None = None) -> dict:
    return await run_sync(sn_import.run, cu, await _content(request), file_name)


@router.get(
    "/imports/{import_no}",
    summary="整单删除确认：导入单的枚数、待分配 / 已分配枚数、涉及的 PI 与单据；deletable = 全部待分配",
)
def import_summary(import_no: str, cu: OperatorDep) -> dict:
    return sn_import.summary(cu, import_no)


@router.post(
    "/imports/{import_no}/delete",
    summary="整单删除：导入单的号全部待分配时从号池移除（之后可重新导入），导入记录标为已删除；有已分配的号不能删",
)
def import_delete(import_no: str, cu: OperatorDep) -> dict:
    return sn_import.delete(cu, import_no)


@router.get("/items", summary="已导入的号（新的在前）：按 PI、单据编号、SN（前缀）、导入单号筛选，分页")
def import_items(
    cu: OperatorDep,
    pi: str | None = None,
    bill_no: str | None = None,
    sn: str | None = None,
    import_no: str | None = None,
    page: OptInt = None,
    page_size: OptInt = None,
) -> dict:
    f = sn_import.Filter(pi_no=pi, bill_no=bill_no, sn=sn, import_no=import_no)
    return sn_import.items(cu, f, page_of(page, page_size))
