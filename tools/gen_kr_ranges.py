"""APNIC 할당 통계에서 한국(KR) IPv4 대역을 뽑아 app/src/main/assets/kr_ipv4.txt 로 저장한다.

사용: python tools/gen_kr_ranges.py [통계 파일 경로]
  경로를 주지 않으면 APNIC에서 최신 파일을 내려받는다. 표준 라이브러리만 쓴다.
출력: 줄마다 CIDR 하나 (겹치거나 이어지는 대역은 합침). '#' 줄은 주석.
"""
import datetime
import ipaddress
import pathlib
import sys
import urllib.request

SOURCE = "https://ftp.apnic.net/stats/apnic/delegated-apnic-latest"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "kr_ipv4.txt"


def read_stats(path):
    if path:
        with open(path, encoding="utf-8") as f:
            return f.read(), path
    with urllib.request.urlopen(SOURCE, timeout=60) as r:
        return r.read().decode("utf-8"), SOURCE


def kr_networks(text):
    nets = []
    stats_date = None
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        parts = line.split("|")
        # 버전 줄: 2|apnic|YYYYMMDD|...
        if stats_date is None and len(parts) > 2 and parts[0] == "2" and parts[1] == "apnic":
            stats_date = parts[2]
            continue
        if len(parts) < 7 or parts[1] != "KR" or parts[2] != "ipv4":
            continue
        if parts[6] not in ("allocated", "assigned"):
            continue
        start = ipaddress.IPv4Address(parts[3])
        count = int(parts[4])
        end = ipaddress.IPv4Address(int(start) + count - 1)
        nets.extend(ipaddress.summarize_address_range(start, end))
    return list(ipaddress.collapse_addresses(nets)), stats_date


def main():
    text, source = read_stats(sys.argv[1] if len(sys.argv) > 1 else None)
    nets, stats_date = kr_networks(text)
    if not nets:
        sys.exit("KR 대역을 찾지 못했다: 입력 파일 확인")
    total = sum(n.num_addresses for n in nets)
    header = [
        "# 한국(KR) IPv4 대역 - 해외 전송 차단용",
        f"# source: {SOURCE}",
        f"# stats date: {stats_date}",
        f"# generated: {datetime.date.today().isoformat()} by tools/gen_kr_ranges.py",
        f"# prefixes: {len(nets)}, addresses: {total}",
    ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(header + [str(n) for n in nets]) + "\n")
    print(f"{OUT}: {len(nets)} prefixes, {total} addresses ({source})")


if __name__ == "__main__":
    main()
