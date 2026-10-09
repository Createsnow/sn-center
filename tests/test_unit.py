"""单元测试：编码、号段切分、金蝶解析、多语言契约、与 Java 后端的一致性、cron。"""

import json
import re
from datetime import datetime
from decimal import Decimal
from pathlib import Path

import pytest

from app.core import encoding as codec
from app.core.encoding import DEFAULT_CHARSETS, Spec
from app.core.errors import ErrorCode
from app.core.scheduler import Cron
from app.db.migrate import migrations
from app.services import files, k3
from app.services.sn_items import sn_qty, split

ROOT = Path(__file__).resolve().parents[1]

# ---------------------------------------------------------------------- SnCodec（对应 SnCodecTest）

DEC5 = Spec("AVM", "X", 10, 5, "0123456789")
B32 = Spec("", "", 32, 4, DEFAULT_CHARSETS[32])


def test_formats_fixed_width_with_prefix_suffix():
    assert codec.format_sn(1, DEC5) == "AVM00001X"
    assert codec.format_sn(99_999, DEC5) == "AVM99999X"
    assert codec.encode_seq(31, B32) == "000V"
    assert codec.encode_seq(32, B32) == "0010"


def test_rejects_overflow_instead_of_widening():
    with pytest.raises(codec.SeqOverflow):
        codec.format_sn(100_000, DEC5)
    assert codec.max_seq(DEC5) == 99_999
    assert codec.max_seq(B32) == 32**4 - 1


def test_parse_is_inverse_of_format():
    for s in (0, 1, 31, 32, 1023, 1_048_575):
        assert codec.parse(codec.format_sn(s, B32), B32) == s
    assert codec.parse("AVM00042X", DEC5) == 42
    assert codec.parse("AVM0042X", DEC5) is None, "wrong length"
    assert codec.parse("BVM00042X", DEC5) is None, "wrong prefix"
    assert codec.parse("AVM0004AX", DEC5) is None, "char outside charset"


def test_custom_charset_keeps_order_of_table():
    s = Spec("", "", 10, 3, "ABCDEFGHJK")
    assert codec.encode_seq(10, s) == "ABA"
    assert codec.parse("ABA", s) == 10


def test_validation():
    assert codec.validate(Spec("", "", 8, 4, "01234567")) is ErrorCode.RULE_BASE_INVALID
    assert codec.validate(Spec("", "", 10, 13, "0123456789")) is ErrorCode.RULE_SEQ_LEN_INVALID
    assert codec.validate(Spec("", "", 10, 4, "012345678")) is ErrorCode.RULE_CHARSET_LENGTH
    assert codec.validate(Spec("", "", 10, 4, "0123456788")) is ErrorCode.RULE_CHARSET_DUPLICATE
    assert codec.validate(DEC5) is None


# ---------------------------------------------------------------------- Segments（对应 SegmentsTest）

SPEC4 = Spec("S", "", 10, 4, "0123456789")


def line(seq, material, qty):
    return {"line_seq": seq, "material_number": material, "material_name": material, "qty": Decimal(qty)}


def test_splits_by_line_order_including_repeated_and_empty_material():
    segs = split([line(1, "A", 3), line(2, "B", 2), line(3, "A", 2), line(4, "", 1)], 0, 8, 11, SPEC4)
    assert len(segs) == 4
    assert segs[0].material_code == "A"
    assert (segs[0].start_seq, segs[0].end_seq) == (11, 13)
    assert segs[1].start_sn == "S0014"
    assert segs[2].material_code == "A"
    assert segs[3].material_code == ""
    assert segs[3].end_seq == 18


def test_continues_after_already_generated_positions():
    segs = split([line(1, "A", 3), line(2, "B", 2)], 2, 2, 100, SPEC4)
    assert len(segs) == 2
    assert (segs[0].material_code, segs[0].qty, segs[0].start_seq) == ("A", 1, 100)
    assert (segs[1].material_code, segs[1].start_seq) == ("B", 101)


def test_fractional_quantity_counts_integer_part():
    assert sn_qty({"qty": Decimal("2.9")}) == 2


def test_segments_json_matches_java_jackson_layout():
    from app.services.generate import segments_json

    s = segments_json(split([line(1, "物料", 2)], 0, 2, 1, SPEC4))
    assert s == (
        '[{"lineSeq":1,"materialCode":"物料","materialName":"物料","qty":2,"startSeq":1,"endSeq":2,'
        '"startSn":"S0001","endSn":"S0002"}]'
    )


# ---------------------------------------------------------------------- 金蝶解析（对应 K3ParsingTest）


def test_query_rows_keep_only_plan_statuses_and_order():
    raw = [
        ["C1", "MO1", "PO", "PI1", "M1", "物料1", 10.0, "1", "SO1", "工厂A"],
        ["C1", "MO1", "PO", "PI1", "M2", "物料2", 5, 2.0, "", "工厂A"],
        ["C1", "MO2", "PO", "PI1", "M1", "物料1", 7, "4", "", "工厂A"],
    ]
    rows = k3.rows_from_query(raw)
    assert len(rows) == 2
    assert rows[1].material_number == "M2"
    assert rows[1].status == "2"
    assert rows[1].qty == Decimal("5")


def test_locale_name_picks_simplified_chinese():
    assert k3.locale_name([{"Key": 1033, "Value": "Lock"}, {"Key": 2052, "Value": "门锁"}]) == "门锁"


def test_error_is_extracted():
    err = [[{"Result": {"ResponseStatus": {"IsSuccess": False, "Errors": [{"Message": "字段不存在"}]}}}]]
    assert k3.error_of(err) == "字段不存在"
    assert k3.error_of([["a"]]) is None


# ---------------------------------------------------------------------- 多语言契约（对应 I18nContractTest）

PH = re.compile(r"\{(\w+)}")


@pytest.mark.parametrize("lang", ["zh-CN", "en", "vi"])
def test_every_error_code_translated(lang):
    path = ROOT / "frontend" / "src" / "locales" / f"errors.{lang}.json"
    if not path.is_file():
        pytest.skip("frontend sources not present")
    msgs = json.loads(path.read_text(encoding="utf-8"))
    for c in ErrorCode:
        assert c.name in msgs, f"{lang} missing {c.name}"
        assert set(PH.findall(c.zh)) == set(PH.findall(msgs[c.name])), f"{lang} placeholders of {c.name}"
    assert len(msgs) == len(ErrorCode), f"{lang} has stale codes"


# ---------------------------------------------------------------------- 建表脚本


def test_migration_flyway_checksum():
    """演示阶段只有一个 V1__init.sql；校验和按 Flyway 算法固定，改了建表脚本须同步更新这里。"""
    ours = {m.script: m for m in migrations()}
    assert list(ours) == ["V1__init.sql"]
    assert ours["V1__init.sql"].checksum() == -66636356
    assert len(ours["V1__init.sql"].statements()) == 21


# ---------------------------------------------------------------------- 定时任务


def test_spring_cron_next_fire():
    c = Cron("0 10 2 * * *")
    assert c.next_after(datetime(2026, 9, 28, 1, 0, 0)) == datetime(2026, 9, 28, 2, 10, 0)
    assert c.next_after(datetime(2026, 9, 28, 2, 10, 0)) == datetime(2026, 9, 29, 2, 10, 0)
    assert Cron("0 0 3 * * SUN").next_after(datetime(2026, 9, 28)) == datetime(2026, 10, 4, 3, 0, 0)
    assert Cron("*/15 * * * * *").next_after(datetime(2026, 1, 1, 0, 0, 1)) == datetime(2026, 1, 1, 0, 0, 15)


# ---------------------------------------------------------------------- 导出防公式注入


@pytest.mark.parametrize(
    "value",
    ["=1+1", "+1", "-A1", "@SUM(A1)", "\t=1+1", "\r=1+1", '=HYPERLINK("http://x","y")', "=A1,B1", "=a\nb"],
)
def test_csv_neutralizes_formula_even_when_quoted(value):
    cell = files.csv_line([value]).rstrip("\r\n")
    body = cell[1:-1].replace('""', '"') if cell.startswith('"') else cell
    assert body == "'" + value


def test_csv_keeps_numbers_and_plain_text():
    assert files.csv_line([-5, 1.5, "SN-001", "a,b", None, True]) == '-5,1.5,SN-001,"a,b",,true\r\n'


def test_xlsx_writes_formula_like_text_as_plain_string():
    import io

    import openpyxl

    rows = [["=1+1", '=HYPERLINK("http://x","y")', "@x", 7]]
    raw = files.to_bytes(files.xlsx_bytes("t", ["=h"], rows))
    ws = openpyxl.load_workbook(io.BytesIO(raw)).active
    cells = [c for row in ws.iter_rows() for c in row if c.value is not None]
    assert [c.value for c in cells] == ["=h", "=1+1", '=HYPERLINK("http://x","y")', "@x", 7]
    assert all(c.data_type == "s" for c in cells[:-1])
    assert cells[-1].data_type == "n"


# ---------------------------------------------------------------------- 令牌签名密钥


@pytest.mark.parametrize(
    "secret,problem",
    [
        ("", True),
        ("change-me-sn-center-secret", True),
        ("change-me-to-a-long-random-string", True),
        ("short-secret", True),
        ("x" * 32, False),
    ],
)
def test_secret_problem(monkeypatch, secret, problem):
    from app.core.config import settings

    monkeypatch.setenv("SN_SECRET", secret)
    assert (settings.secret_problem() is not None) is problem


def test_production_refuses_weak_secret_but_local_only_warns(monkeypatch):
    from app.core.config import settings
    from app.core.security import _sign
    from app.main import check_secret

    monkeypatch.setenv("SN_SECRET", "")
    monkeypatch.setenv("ENVIRONMENT", "local")
    check_secret()
    assert settings.secret_key, "blank secret never signs with an empty key"
    assert _sign("x") == _sign("x") != ""
    monkeypatch.setenv("ENVIRONMENT", "production")
    with pytest.raises(RuntimeError):
        check_secret()
    monkeypatch.setenv("SN_SECRET", "change-me-to-a-long-random-string")
    with pytest.raises(RuntimeError):
        check_secret()
    monkeypatch.setenv("SN_SECRET", "0123456789abcdef0123456789abcdef")
    check_secret()


# ---------------------------------------------------------------------- 数据库连接配置

DB_KEYS = ("DB_URL", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD")


def _db_target(monkeypatch, **dotenv: str):
    from app.core.config import DbTarget, Settings

    for k in DB_KEYS:
        monkeypatch.delenv(k, raising=False)
    s = Settings()
    s._dotenv = dict(dotenv)
    t = s.db_target()
    assert isinstance(t, DbTarget)
    return t


def test_db_target_reads_separate_keys(monkeypatch):
    t = _db_target(
        monkeypatch, DB_HOST="db.example", DB_PORT="3307", DB_NAME="other_db", DB_USER="u2", DB_PASSWORD=" p#w=d "
    )
    assert (t.host, t.port, t.database, t.user, t.password) == ("db.example", 3307, "other_db", "u2", " p#w=d ")


def test_db_target_defaults(monkeypatch):
    t = _db_target(monkeypatch)
    assert (t.host, t.port, t.database, t.user, t.password) == ("127.0.0.1", 3306, "sndb", "appuser", "")


def test_db_target_legacy_url_still_works_and_single_keys_win(monkeypatch):
    url = "jdbc:mysql://10.0.0.5:3310/legacy?useUnicode=true&characterEncoding=utf8"
    t = _db_target(monkeypatch, DB_URL=url, DB_USER="appuser", DB_PASSWORD="x")
    assert (t.host, t.port, t.database) == ("10.0.0.5", 3310, "legacy")
    t = _db_target(monkeypatch, DB_URL=url, DB_NAME="sndb_b", DB_HOST="10.0.0.6")
    assert (t.host, t.port, t.database) == ("10.0.0.6", 3310, "sndb_b")
    t = _db_target(monkeypatch, DB_URL="mysql://me:p%40ss@h1/d1")
    assert (t.host, t.port, t.database, t.user, t.password) == ("h1", 3306, "d1", "me", "p@ss")


def test_db_target_env_overrides_dotenv(monkeypatch):
    from app.core.config import Settings

    for k in DB_KEYS:
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("DB_NAME", "from_env")
    s = Settings()
    s._dotenv = {"DB_NAME": "from_file"}
    assert s.db_target().database == "from_env"
