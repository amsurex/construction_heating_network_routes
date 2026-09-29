#!/usr/bin/env python3
"""Воспроизводимый замер POST /jobs: время, peak JVM heap и размер результата.

Скрипт использует только стандартную библиотеку и отправляет multipart блоками, не читая вход в память.
"""
import argparse
import http.client
import json
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode, urlsplit


def connection(url, timeout):
    cls = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    return cls(url.hostname, url.port, timeout=timeout)


def request(url, timeout, method, path):
    conn = connection(url, timeout)
    try:
        conn.request(method, url.path.rstrip("/") + path)
        response = conn.getresponse()
        return response.status, response.read(), dict(response.getheaders())
    finally:
        conn.close()


def upload(url, timeout, source):
    boundary = "lct-benchmark-" + uuid.uuid4().hex
    prefix = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.geojson\"\r\n"
              "Content-Type: application/geo+json\r\n\r\n").encode()
    suffix = f"\r\n--{boundary}--\r\n".encode()
    conn = connection(url, timeout)
    try:
        query = urlencode({"mode": "PLAN_2D", "resource": "heat"})
        conn.putrequest("POST", url.path.rstrip("/") + "/api/v1/jobs?" + query)
        conn.putheader("Content-Type", "multipart/form-data; boundary=" + boundary)
        conn.putheader("Content-Length", str(len(prefix) + source.stat().st_size + len(suffix)))
        conn.endheaders()
        conn.send(prefix)
        with source.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                conn.send(block)
        conn.send(suffix)
        response = conn.getresponse()
        body = response.read()
        if response.status != 202:
            raise RuntimeError(f"Upload {response.status}: {body.decode(errors='replace')}")
        return json.loads(body)
    finally:
        conn.close()


def metric(url, timeout, name):
    status, body, _ = request(url, timeout, "GET", f"/actuator/metrics/{name}?tag=area:heap")
    if status != 200:
        return None
    return sum(item["value"] for item in json.loads(body).get("measurements", []))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--input", type=Path, default=Path("data/synthetic/large.geojson"))
    parser.add_argument("--report", type=Path, default=Path("data/results/large-benchmark.json"))
    parser.add_argument("--timeout", type=int, default=7200)
    parser.add_argument("--sample-seconds", type=float, default=0.5)
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error(f"Нет файла {args.input}")
    url = urlsplit(args.url)
    status, _, _ = request(url, 30, "GET", "/actuator/health")
    if status != 200:
        raise RuntimeError("Сервис не готов")

    started = time.monotonic()
    state = {}

    def send():
        try:
            state["created"] = upload(url, args.timeout, args.input)
            state["uploadSeconds"] = time.monotonic() - started
        except BaseException as error:  # ошибка передаётся в основной поток
            state["error"] = error

    thread = threading.Thread(target=send, daemon=True)
    thread.start()
    peak_heap = 0
    heap_max = None
    while thread.is_alive():
        value = metric(url, 30, "jvm.memory.used")
        if value is not None:
            peak_heap = max(peak_heap, value)
        heap_max = heap_max or metric(url, 30, "jvm.memory.max")
        thread.join(args.sample_seconds)
    if "error" in state:
        raise state["error"]

    job_id = state["created"]["jobId"]
    deadline = started + args.timeout
    while True:
        status, body, _ = request(url, 30, "GET", "/api/v1/jobs/" + job_id)
        if status != 200:
            raise RuntimeError(f"Job status {status}: {body.decode(errors='replace')}")
        job = json.loads(body)
        value = metric(url, 30, "jvm.memory.used")
        if value is not None:
            peak_heap = max(peak_heap, value)
        if job["status"] in ("DONE", "FAILED", "CANCELLED"):
            break
        if time.monotonic() > deadline:
            raise TimeoutError("Расчёт не завершился за отведённое время")
        time.sleep(args.sample_seconds)

    result_bytes = None
    validation_errors = None
    if job["status"] == "DONE":
        status, result, _ = request(url, args.timeout, "GET", f"/api/v1/jobs/{job_id}/result")
        if status != 200:
            raise RuntimeError(f"Result {status}")
        result_bytes = len(result)
        status, validation, _ = request(url, 30, "GET", f"/api/v1/jobs/{job_id}/validation")
        if status == 200:
            validation_errors = json.loads(validation)["errorCount"]

    report = {
        "jobId": job_id,
        "status": job["status"],
        "inputBytes": args.input.stat().st_size,
        "connectionPoints": state["created"].get("connectionPoints"),
        "estimatedSeconds": state["created"].get("estimatedSeconds"),
        "uploadAndInputValidationSeconds": round(state["uploadSeconds"], 2),
        "totalSeconds": round(time.monotonic() - started, 2),
        "peakHeapBytes": round(peak_heap),
        "heapMaxBytes": round(heap_max) if heap_max is not None else None,
        "resultBytes": result_bytes,
        "resultLimitBytes": 500 * 1024 * 1024,
        "validationErrors": validation_errors,
        "job": job,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if job["status"] != "DONE" or (result_bytes is not None and result_bytes > report["resultLimitBytes"]):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
