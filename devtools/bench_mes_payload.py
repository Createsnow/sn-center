"""接口传输大小 POC：按 mes_client.canonical + gzip_bytes 的同一做法组包，测体积、压缩耗时、本机 HTTP 往返。

只用标准库：python devtools/bench_mes_payload.py
- 数据 A（典型）：同一 PI、连续流水、同物料，对应「按流水顺序认领」的真实组包。
- 数据 B（最坏）：随机 36 进制流水、单据、物料、客户，并带转移字段。
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
        "pushType": "NEW",
        "sn": f"HX100{s}A",
        "rev": 1,
        "serial": s,
        "serialDec": str(i + 1),
        "serialPi": "000001",
        "pi": "000001",
        "billNo": f"VT20260900{1 + i // 20000:02d}",
        "billSeq": 1 + (i // 5000) % 8,
        "materialCode": "9148363",
        "length": "100",
        "customerCode": "0001",
    }


def worst_item(rnd: random.Random) -> dict:
    s = "".join(rnd.choice(B36) for _ in range(8))
    length = rnd.choice(["100", "10.50", "0100"])
    return {
        "pushType": rnd.choice(["NEW", "REASSIGN"]),
        "sn": f"SDX{length}{s}Z",
        "rev": rnd.randint(1, 5),
        "serial": s,
        "serialDec": str(rnd.randint(1, 36**8)),
        "serialPi": f"{rnd.randint(1, 999999):06d}",
        "pi": f"{rnd.randint(1, 999999):06d}",
        "billNo": f"VT2026{rnd.randint(0, 999999):06d}",
        "billSeq": rnd.randint(1, 40),
        "materialCode": str(rnd.randint(1000000, 9999999)),
        "length": length,
        "customerCode": f"{rnd.randint(1, 9999):04d}",
        "transferKind": "ORG",
        "transferDocNo": f"TR{rnd.randint(0, 99999999):08d}",
        "fromOrgCode": str(rnd.randint(100, 999)),
    }


def batch(items: list[dict]) -> dict:
    return {
        "schemaVersion": "1.0",
        "batchNo": "PS-110-20261019-000123",
        "batchType": "DELIVERY",
        "orgCode": "110",
        "pi": "000001",
        "count": len(items),
        "createdAt": "2026-10-19T02:37:00Z",
        "itemsDigest": "sha256:" + hashlib.sha256(canonical(items)).hexdigest(),
        "items": items,
    }


def receipt(n: int) -> dict:
    return {
        "schemaVersion": "1.0",
        "batchNo": "PS-110-20261019-000123",
        "outcome": "PROCESSED",
        "results": [{"sn": f"HX100{i + 1:06d}A", "rev": 1, "result": "ACCEPTED"} for i in range(n)],
    }


def timed(fn, k: int = 5):
    xs, r = [], None
    for _ in range(k):
        a = time.perf_counter()
        r = fn()
        xs.append(time.perf_counter() - a)
    return statistics.median(xs) * 1000, r


class MockMes(BaseHTTPRequestHandler):
    """模拟 MES：解压 → 解析 → 逐枚回执 → gzip 应答（不落库）。"""

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        obj = json.loads(gzip.decompress(body))
        out = gz(canonical(receipt(obj["count"])))
        self.send_response(200)
        self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


def main() -> None:
    srv = ThreadingHTTPServer(("127.0.0.1", 0), MockMes)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{srv.server_port}/"

    def post(body: bytes):
        req = urllib.request.Request(
            url, data=body, headers={"Content-Encoding": "gzip", "Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req) as r:
            return json.loads(gzip.decompress(r.read()))

    print("数据 A（典型）")
    print("枚数\t未压缩B\tgz1 B\tgz6 B\tgz9 B\tgz1 ms\tgz6 ms\tgz9 ms\t解压解析ms\t往返ms\t解析峰值MB\t回执gz B")
    for n in SIZES:
        raw = canonical(batch([typical_item(i) for i in range(n)]))
        lv = {L: timed(lambda L=L, raw=raw: gz(raw, L)) for L in (1, 6, 9)}
        z6 = lv[6][1]
        dec_ms, _ = timed(lambda z6=z6: json.loads(gzip.decompress(z6)))
        rt_ms, _ = timed(lambda z6=z6: post(z6))
        tracemalloc.start()
        json.loads(gzip.decompress(z6))
        peak = tracemalloc.get_traced_memory()[1] / 2**20
        tracemalloc.stop()
        rc = len(gz(canonical(receipt(n))))
        print(
            n,
            len(raw),
            *(len(lv[L][1]) for L in (1, 6, 9)),
            *(f"{lv[L][0]:.1f}" for L in (1, 6, 9)),
            f"{dec_ms:.1f}",
            f"{rt_ms:.1f}",
            f"{peak:.1f}",
            rc,
            sep="\t",
        )

    print("\n数据 B（最坏）")
    print("枚数\t未压缩B\tgz6 B\t压缩比\tgz6 ms")
    rnd = random.Random(7)
    for n in (1000, 5000, 10000, 20000):
        raw = canonical(batch([worst_item(rnd) for _ in range(n)]))
        ms, z = timed(lambda raw=raw: gz(raw, 6))
        print(n, len(raw), len(z), f"{len(raw) / len(z):.1f}", f"{ms:.1f}", sep="\t")
    srv.shutdown()


if __name__ == "__main__":
    main()
