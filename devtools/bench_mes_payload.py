"""接口传输大小 POC：按 mes_client.canonical + gzip_bytes 的同一做法组包，测体积、压缩耗时、本机 HTTP 往返。

只用标准库：python devtools/bench_mes_payload.py
- 报文按《MES 对接接口文档》① 现协议：批次头含 toOrgCode，明细 12 个字段，MES 在响应中回执整批结果。
- 数据 A（典型）：同一 PI、连续流水、同物料，对应「按流水顺序认领」的真实组包。
- 数据 B（最坏）：跨组织转出批次，随机 36 进制流水、单据、附件项次、物料、客户，几乎无重复。
- 原始 JSON（默认）与 gzip 6（组织开启「gzip 压缩」）两种请求体都测。
结论见 docs/接口传输大小 POC 报告.md。
"""

import gzip
import hashlib
import json
import random
import statistics
import threading
import time
import tracemalloc
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZES = (500, 1000, 2000, 5000, 10000, 20000, 50000)
B36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def canonical(o) -> bytes:
    return json.dumps(o, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def gz(raw: bytes, level: int = 6) -> bytes:
    return gzip.compress(raw, compresslevel=level, mtime=0)


def typical_item(i: int) -> dict:
    s = f"{i + 1:06d}"
    return {
        "sn": f"HX100{s}A",
        "rev": 1,
        "serial": s,
        "serialDec": str(i + 1),
        "serialPi": "000001",
        "pi": "000001",
        "billNo": f"VT20260900{1 + i // 20000:02d}",
        "billSeq": 1 + (i // 5000) % 8,
        "attachNo": None,
        "materialCode": "9148363",
        "length": "100",
        "customerCode": "0001",
    }


def worst_item(rnd: random.Random) -> dict:
    s = "".join(rnd.choice(B36) for _ in range(8))
    length = rnd.choice(["100", "10.50", "0100"])
    return {
        "sn": f"SDX{length}{s}Z",
        "rev": rnd.randint(1, 5),
        "serial": s,
        "serialDec": str(rnd.randint(1, 36**8)),
        "serialPi": f"{rnd.randint(1, 999999):06d}",
        "pi": "000001",
        "billNo": f"VT2026{rnd.randint(0, 999999):06d}",
        "billSeq": rnd.randint(1, 40),
        "attachNo": f"A{rnd.randint(1, 99):02d}",
        "materialCode": str(rnd.randint(1000000, 9999999)),
        "length": length,
        "customerCode": f"{rnd.randint(1, 9999):04d}",
    }


def batch(items: list[dict], to_org: str | None = None) -> dict:
    return {
        "schemaVersion": "1.0",
        "batchNo": "PS-110-20261026-000123",
        "batchType": "TRANSFER_OUT" if to_org else "NEW",
        "orgCode": "110",
        "toOrgCode": to_org,
        "pi": "000001",
        "createdAt": "2026-10-26 11:47:05.000",
        "itemsDigest": "sha256:" + hashlib.sha256(canonical(items)).hexdigest(),
        "items": items,
    }


def receipt(batch_no: str) -> dict:
    return {"batchNo": batch_no, "code": "OK", "message": "已处理", "processedAt": "2026-10-26 11:47:07.215"}


def parse(body: bytes, gzipped: bool):
    return json.loads(gzip.decompress(body) if gzipped else body)


def timed(fn, k: int = 5):
    xs, r = [], None
    for _ in range(k):
        a = time.perf_counter()
        r = fn()
        xs.append(time.perf_counter() - a)
    return statistics.median(xs) * 1000, r


class MockMes(BaseHTTPRequestHandler):
    """模拟 MES：按 Content-Encoding 解压 → 解析 → 响应中回执整批结果（不落库）。"""

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        obj = parse(body, self.headers.get("Content-Encoding") == "gzip")
        out = canonical(receipt(obj["batchNo"]))
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


def peak_mb(fn) -> float:
    tracemalloc.start()
    fn()
    peak = tracemalloc.get_traced_memory()[1] / 2**20
    tracemalloc.stop()
    return peak


def main() -> None:
    srv = ThreadingHTTPServer(("127.0.0.1", 0), MockMes)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{srv.server_port}/"

    def post(body: bytes, gzipped: bool):
        h = {"Content-Type": "application/json"} | ({"Content-Encoding": "gzip"} if gzipped else {})
        with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=h)) as r:
            return len(r.read())

    print("数据 A（典型）")
    print(
        "枚数\t原始B\tgz1 B\tgz6 B\tgz9 B\tgz1 ms\tgz6 ms\tgz9 ms\t"
        "解析ms(原始/gz6)\t往返ms(原始/gz6)\t解析峰值MB(原始/gz6)\t响应B"
    )
    for n in SIZES:
        raw = canonical(batch([typical_item(i) for i in range(n)]))
        lv = {L: timed(lambda L=L, raw=raw: gz(raw, L)) for L in (1, 6, 9)}
        z6 = lv[6][1]
        dec = [timed(lambda b=b, g=g: parse(b, g))[0] for b, g in ((raw, False), (z6, True))]
        rt = [timed(lambda b=b, g=g: post(b, g))[0] for b, g in ((raw, False), (z6, True))]
        mem = [peak_mb(lambda b=b, g=g: parse(b, g)) for b, g in ((raw, False), (z6, True))]
        print(
            n,
            len(raw),
            *(len(lv[L][1]) for L in (1, 6, 9)),
            *(f"{lv[L][0]:.1f}" for L in (1, 6, 9)),
            "/".join(f"{x:.1f}" for x in dec),
            "/".join(f"{x:.1f}" for x in rt),
            "/".join(f"{x:.1f}" for x in mem),
            post(z6, True),
            sep="\t",
        )

    print("\n数据 B（最坏，跨组织转出批次）")
    print("枚数\t原始B\tgz6 B\t压缩比\tgz6 ms")
    rnd = random.Random(7)
    for n in (1000, 5000, 10000, 20000, 50000):
        raw = canonical(batch([worst_item(rnd) for _ in range(n)], to_org="120"))
        ms, z = timed(lambda raw=raw: gz(raw, 6))
        print(n, len(raw), len(z), f"{len(raw) / len(z):.1f}", f"{ms:.1f}", sep="\t")
    srv.shutdown()


if __name__ == "__main__":
    main()
