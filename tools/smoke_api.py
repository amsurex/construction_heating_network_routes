#!/usr/bin/env python3
"""Проверка HTTP → PostgreSQL jobs → RoutingEngine → GeoJSON + независимый отчёт.
Только стандартная библиотека Python, multipart передаётся с диска блоками.
"""
import argparse
import http.client
import json
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode, urlsplit


def connection(url):
    cls = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    return cls(url.hostname, url.port, timeout=300)


def request(url, method, path, body=None, headers=None):
    conn = connection(url)
    try:
        conn.request(method, url.path.rstrip("/")+path, body, headers or {})
        response = conn.getresponse()
        return response.status, response.read(), dict(response.getheaders())
    finally:
        conn.close()


def upload(url, file, mode, resource, pin_tie_in, exclude_tie_in, exclude_restriction):
    boundary = "lct-"+uuid.uuid4().hex
    prefix = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"input.geojson\"\r\n"
              "Content-Type: application/geo+json\r\n\r\n").encode()
    suffix = f"\r\n--{boundary}--\r\n".encode()
    conn = connection(url)
    try:
        query = urlencode({"mode": mode, "resource": resource, "pin_tie_in": pin_tie_in,
                           "exclude_tie_in": exclude_tie_in, "exclude_restriction": exclude_restriction})
        conn.putrequest("POST", url.path.rstrip("/")+"/api/v1/jobs?"+query)
        conn.putheader("Content-Type", "multipart/form-data; boundary="+boundary)
        conn.putheader("Content-Length", str(len(prefix)+file.stat().st_size+len(suffix)))
        conn.endheaders(); conn.send(prefix)
        with file.open("rb") as stream:
            for block in iter(lambda: stream.read(1024*1024), b""): conn.send(block)
        conn.send(suffix)
        response = conn.getresponse(); body = response.read()
        if response.status != 202: raise RuntimeError(f"Upload {response.status}: {body.decode()}")
        return json.loads(body)["jobId"]
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--input", type=Path, default=Path("data/samples/dataset.geojson"))
    parser.add_argument("--output", type=Path, default=Path("data/results/api"))
    parser.add_argument("--mode", choices=["PLAN_2D", "DEPTH_3D"], default="PLAN_2D")
    parser.add_argument("--resource", choices=["heat", "water", "cable"], default="heat")
    parser.add_argument("--pin-tie-in", default="")
    parser.add_argument("--exclude-tie-in", default="")
    parser.add_argument("--exclude-restriction", default="")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--allow-invalid", action="store_true", help="Сохранить нарушения валидатора без ненулевого exit code")
    args = parser.parse_args(); url = urlsplit(args.url)
    deadline = time.monotonic()+args.timeout
    while True:
        try:
            status, _, _ = request(url, "GET", "/actuator/health")
            if status == 200: break
        except (OSError, http.client.HTTPException): pass
        if time.monotonic() > deadline: raise RuntimeError("Сервис не стал готов")
        time.sleep(1)
    status, body, _ = request(url, "GET", "/v3/api-docs")
    assert status == 200 and "/api/v1/jobs" in json.loads(body)["paths"], "Swagger unavailable"
    status, _, _ = request(url, "GET", "/swagger-ui/index.html")
    assert status == 200, "Swagger UI unavailable"
    started = time.monotonic()
    job = upload(url, args.input, args.mode, args.resource, args.pin_tie_in,
                 args.exclude_tie_in, args.exclude_restriction)
    print("jobId="+job, flush=True)
    while True:
        status, body, _ = request(url, "GET", "/api/v1/jobs/"+job)
        assert status == 200, body
        data = json.loads(body)
        if data["status"] == "FAILED": raise RuntimeError(data)
        if data["status"] == "DONE": break
        if time.monotonic() > deadline: raise RuntimeError("Таймаут расчёта")
        time.sleep(1)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/"job.json").write_text(json.dumps(data, ensure_ascii=False, indent=2))
    status, result, headers = request(url, "GET", "/api/v1/jobs/"+job+"/result")
    assert status == 200 and headers.get("Content-Type", "").startswith("application/geo+json")
    (args.output/"result.geojson").write_bytes(result)
    assert json.loads(result)["type"] == "FeatureCollection"
    status, report, _ = request(url, "GET", "/api/v1/jobs/"+job+"/validation")
    assert status == 200
    (args.output/"validation.json").write_bytes(report)
    report = json.loads(report)
    print(json.dumps({"jobId": job, "seconds": round(time.monotonic()-started, 2),
                      "resultBytes": len(result), "validationErrors": report["errorCount"]}))
    if not args.allow_invalid and not report["valid"]: raise SystemExit(2)


if __name__ == "__main__": main()
