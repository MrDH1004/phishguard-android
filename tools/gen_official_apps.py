"""tools/data/official_packages.json -> app/src/main/java/kr/phishguard/app/scan/OfficialApps.kt

데이터: Google Play(hl=ko, gl=KR) 상세 페이지에서 패키지·개발자를 확인한 공식 앱 목록 (2026-11-14 수집).
사용: py tools/gen_official_apps.py
"""
import io
import json
import os
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'tools', 'data', 'official_packages.json')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'kr', 'phishguard', 'app', 'scan', 'OfficialApps.kt')


# 일반 낱말이라 이름만으로 공식 앱이라 볼 수 없는 별칭 (이름 도용 판정에서 제외)
GENERIC = {'다음', '나무', '마블', '주파수', '우편', '투혼', '오네', '줌', 'qv', 'isp', 'hug', '미러링'}


def norm(label):
    return ''.join(label.lower().split())


def kstr(s):
    bs = chr(92)
    return '"' + s.replace(bs, bs * 2).replace('"', bs + '"').replace('$', bs + '$') + '"'


def main():
    with io.open(SRC, encoding='utf-8') as f:
        data = json.load(f)
    data = [e for e in data if e.get('verified')]
    pkgs = sorted({e['package'].lower() for e in data})
    remote = sorted({e['package'].lower() for e in data if e['category'] == 'remote'})
    labels = defaultdict(set)
    for e in data:
        for l in e['labels']:
            n = norm(l)
            if len(n) >= 2 and n not in GENERIC:
                labels[n].add(e['package'].lower())
    lines = [
        'package kr.phishguard.app.scan',
        '',
        '// 자동 생성: tools/gen_official_apps.py (원본 tools/data/official_packages.json). 직접 고치지 말 것.',
        '// Google Play에서 패키지·개발자를 확인한 공식 앱 %d개 (원격제어 %d개).' % (len(pkgs), len(remote)),
        '',
        'object OfficialApps {',
        '    /** 공식 패키지명 (소문자) */',
        '    val PACKAGES: Set<String> = setOf(',
    ]
    lines += ['        %s,' % kstr(p) for p in pkgs]
    lines += ['    )', '', '    /** 공식 원격제어·원격지원 앱 패키지명 (소문자) */', '    val REMOTE: Set<String> = setOf(']
    lines += ['        %s,' % kstr(p) for p in remote]
    lines += ['    )', '', '    /** 정규화한 표시 이름(소문자·공백 제거) → 그 이름을 쓰는 공식 패키지들 (소문자) */',
              '    val LABELS: Map<String, Set<String>> = mapOf(']
    for k in sorted(labels):
        lines.append('        %s to setOf(%s),' % (kstr(k), ', '.join(kstr(p) for p in sorted(labels[k]))))
    lines += ['    )', '}', '']
    with io.open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))
    print('packages', len(pkgs), 'remote', len(remote), 'labels', len(labels))


if __name__ == '__main__':
    main()
