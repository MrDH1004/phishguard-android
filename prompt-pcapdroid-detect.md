# 작업 프롬프트: PCAPdroid 악성 탐지 로직 개선

## 경로
- **원본 (읽기 전용, 수정 금지)**: `E:\AIWork\RNDAIProjects\detectnet\PCAPdroid`
  - upstream `https://github.com/emanuele-f/PCAPdroid` @ `135b32d` (2026-11-02, v2.0.2), 서브모듈 포함 클론
- **작업 폴더 (여기서만 수정)**: `E:\AIWork\RNDAIProjects\detectnet\pcapdroid-detect`

## 0. 작업 폴더 준비
원본 저장소에서 worktree로 분기한다 (원본 작업트리는 건드리지 않음).
```bash
cd /e/AIWork/RNDAIProjects/detectnet/PCAPdroid
git worktree add ../pcapdroid-detect -b detect-improve
cd ../pcapdroid-detect
git submodule update --init --recursive
```
이후 모든 수정·빌드·커밋은 `pcapdroid-detect`에서만 한다. 작업 시작 전 `git status --short --branch`로 브랜치가 `detect-improve`인지 확인.

## 배경 (사전 분석 결과)
탐지는 공개 위협 피드 3종(Maltrail 도메인, Emerging Threats IP, DigitalSide IP)에 연결의 dst IP / 도메인을 정확 매칭하고, 매칭 시 VPN 모드에서 차단까지 한다.
핵심 파일:
- `app/src/main/jni/core/blacklist.c` — 리스트 로드(`blacklist_load_file`), 매칭(`blacklist_match_domain` 등)
- `app/src/main/jni/core/pcapdroid.c` — 판정(`pd_new_connection`, `check_domain_block_rules`), DNS 응답 파싱(`process_dns_reply`), 리로드(`load_new_blacklists`, `apply_malware_whitelist_cb`)
- `app/src/main/jni/core/capture_vpn.c` — 차단 집행, DNS 스푸핑
- `app/src/main/java/com/emanuelef/remote_capture/Blacklists.java` — 피드 목록·다운로드
- `app/src/main/java/com/emanuelef/remote_capture/Utils.java` — `downloadFile`
- `app/src/main/java/com/emanuelef/remote_capture/model/MatchList.java` — `matchesHost` (네이티브 `blacklist_match_domain`과 **동기화 필수**)
- 테스트: `app/src/main/jni/tests/test/blacklist.c`, `dpi.c` (네이티브), `app/src/test/java` (Robolectric, `./gradlew test`)

## 할 일 (순서대로, 한 작업 = 한 커밋)

### 1. 버그 수정 (작은 것부터)
1. **루프백 필터 바이트오더** — `blacklist.c` `blacklist_load_file`의 `ip_addr.ipv4 == 0x7F000001` 비교. `ndpi_parse_ip_string`은 `inet_pton` 사용 → 네트워크 바이트오더. `htonl(0x7F000001)`로 수정. (0, 0xFFFFFFFF는 대칭이라 무관)
2. **잘린 리스트 저장** — `Utils.downloadFile`에서 읽기 도중 `SocketTimeoutException`을 잡고도 `has_contents`면 rename함. 타임아웃/예외 시 실패로 처리하고 `.tmp` 삭제, 기존 파일 유지. HTTP 응답코드 200 확인도 추가.
3. **루트 모드 화이트리스트 해제 미반영** — `pcapdroid.c` `apply_malware_whitelist_cb`에서 `pd->firewall.bl`이 NULL이면 `to_block` 재계산이 안 됨. firewall.bl 유무와 무관하게 `recompute_conn_block_cb`(또는 동등 로직)로 `to_block`을 재계산하도록 수정. `recompute_conn_block_cb`가 fw_bl NULL을 안전하게 처리하는지 확인.

### 2. DNS 응답 파서 보강 (`process_dns_reply`)
현재: 압축 포인터를 `*reply == 0xc0`만 인식, 이름을 레이블 길이가 아닌 0x00 탐색으로 스킵.
- 레이블 길이 기반 이름 스킵 함수 작성: `(b & 0xC0) == 0xC0`이면 2바이트 포인터로 종료, 그 외 `b`가 길이(≤63), 0이면 종료. 매 단계 남은 길이 경계 검사.
- question/answer 섹션 모두 이 함수 사용. answer RR은 TYPE/CLASS/TTL/RDLENGTH 10바이트 경계 검사 후 A/AAAA만 LRU 등록.
- 네이티브 테스트 추가 (`tests/test/dpi.c` 또는 신규): `0xc1` 포인터, 다중 answer, CNAME 체인 뒤 A, 잘린 패킷 케이스. 기존 fuzz 타깃(`tests/fuzz`)이 있으면 함께 돌려서 크래시 없는지 확인.

### 3. 서브도메인 매칭 개선
현재: 완전 일치 + 마지막 2레이블만 → `b.evil.com` 등록 시 `a.b.evil.com` 미탐.
- `blacklist_match_domain`: 호스트에서 왼쪽 레이블을 하나씩 떼며 모든 상위 접미사(최소 2레이블까지)를 해시 조회. (`a.b.evil.com` → `a.b.evil.com`, `b.evil.com`, `evil.com`)
- **같은 의미로** `MatchList.matchesHost`(Java)도 수정 — 방화벽/복호화 목록도 이 함수를 공유하므로 의미 변화(상위 도메인 규칙이 더 넓게 적용)를 커밋 메시지와 결과 보고에 명시.
- `tests/test/blacklist.c`에 케이스 추가, Java 쪽은 `app/src/test/java`에 MatchList 테스트 추가.
- PSL(Public Suffix List) 도입은 이번 범위 아님 — `co.uk` 같은 접미사 단독 등록이 없는 한 위 방식으로 `x.evil.co.uk` → `evil.co.uk` 매칭됨을 테스트로 확인.

### 4. 피드 추가
`Blacklists.java`의 주석 후보:
- `https://github.com/StevenBlack/hosts` (raw: `https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts`) — **hosts 포맷**(`0.0.0.0 domain`)이라 현재 로더로는 깨짐.
- `https://phishing.army/download/phishing_army_blocklist.txt` — 순수 도메인 목록.
- `https://snort.org/downloads/ip-block-list` — 순수 IP 목록.

작업:
- 각 URL을 실제로 받아 포맷·크기·라이선스를 먼저 확인하고 결과를 보고 (추측 금지). 접근 불가/라이선스 문제면 추가하지 말고 보고.
- hosts 포맷 지원: `blacklist_load_file`에 hosts 라인 파싱(`0.0.0.0`/`127.0.0.1` + 공백 + 도메인, 인라인 `#` 주석 제거, `localhost` 류 제외) 추가. 기존 `BlacklistDescriptor.Type`을 늘리기보다 로더에서 자동 인식하는 쪽 우선 검토 후 결정.
- 메모리 영향: 규칙 수 증가 시 로드 시간·메모리를 로그(`Blacklists loaded in ... sec`, 규칙 수)로 측정해 보고.

## 빌드/검증
- 각 커밋마다: 네이티브 테스트(`app/src/main/jni/tests` README 절차) + `./gradlew test` + `./gradlew assembleStandardDebug` (또는 `assembleWithoutUsharkDebug`) 통과.
- 빌드 환경(NDK 28.2.13676358, CMake 3.22.1, JDK 17)이 없으면 설치 상태를 확인해 보고하고, 임의로 버전을 바꾸지 말 것.
- 수동 확인 포인트(가능하면 에뮬레이터): `internetbadguys.com`, `0.0.0.1` 탐지 유지.

## 규칙
- 원본 폴더(`PCAPdroid`)는 절대 수정하지 말 것. 비교용으로만 읽기.
- 한 번에 한 가지만 변경, 변경마다 테스트 추가 후 커밋.
- 실패 시 추측으로 고치지 말고 로그/테스트 출력으로 원인 확인. 같은 문제 3회 실패하면 멈추고 보고.
- push/PR은 하지 말 것 (로컬 커밋까지만).
- 완료 시 작업별로: 변경 파일, 테스트 결과(실제 출력), 의미 변화, 남은 이슈를 정리해 보고.
