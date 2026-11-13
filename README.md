# Codex Reset Notifier

[Codex Resets](https://codex-resets.com/)에 올라오는 Codex 사용량 리셋 소식을 Discord 채널로 보내주는 알림 프로그램입니다.

정해진 시각마다 Codex Resets 공개 API를 조회해서, 새 리셋 기록이나 리셋 예정 공지가 생겼을 때만 Discord 웹훅으로 메시지를 보냅니다.
변화가 없으면 메시지를 보내지 않습니다.

- Discord 봇 계정 없이 **채널 웹훅**만 있으면 됩니다.
- AI를 호출하지 않으므로 실행 중 토큰이 들지 않습니다.
- 외부에서 접속할 포트를 열 필요가 없습니다.
- Docker Compose로 띄우면 서버가 재부팅돼도 자동으로 다시 실행됩니다.

## 알림 예시

```text
**Codex 리셋 예정 공지**
유형: 사용량 리셋 (regular)
공지 시각: 2026-09-22 13:31 KST
예정 시각: 2026-09-23 15:59 KST

(공지 원문)
원문: <https://x.com/...>
출처: <https://codex-resets.com/>
```

알림 종류는 세 가지입니다.

| 종류 | 설명 | API 필드 |
|---|---|---|
| 리셋 기록 업데이트 | 실제로 리셋이 이루어진 기록 | `latest_reset`, 리셋 이력 |
| 리셋 예정 공지 | 리셋 예정 시각이 공지된 상태 | `scheduled_reset` |
| 리셋 가능성 예측 | 사이트가 자체적으로 분류한 리셋 가능성 | `active_watch` |

어디까지 받을지는 설정 파일에서 고를 수 있습니다.

## 필요한 것

- Docker Engine과 Docker Compose 플러그인이 설치된 Linux 서버 (Ubuntu, Raspberry Pi OS 등)
- 알림을 받을 Discord 채널의 웹훅 URL

Java와 Gradle은 Docker 이미지 빌드 과정에서 준비되므로 서버에 따로 설치하지 않아도 됩니다.

## 빠르게 시작하기

### 1. 소스 받기

```sh
git clone https://github.com/MCshin00/codex-reset-notifier.git
cd codex-reset-notifier
```

### 2. Discord 웹훅 만들기

Discord 채널에서 **채널 편집 → 연동 → 웹훅 → 새 웹훅**을 만들고 **웹훅 URL 복사**를 누릅니다.

### 3. 웹훅 주소 입력

```sh
cp .env.example .env
chmod 600 .env
nano .env
```

```dotenv
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/실제ID/실제토큰
```

웹훅 URL은 그 자체로 채널에 글을 쓸 수 있는 권한입니다. `.env`는 `.gitignore`에 포함되어 있으니 커밋하지 마세요.

### 4. 설정 확인

`config/application.properties`에서 조회 간격과 알림 범위를 정합니다. 기본값은 다음과 같습니다.

```properties
poll.interval.minutes=30
notify.scope=reset-and-scheduled
display.time.zone=Asia/Seoul
```

### 5. 실행

```sh
docker compose up -d --build
docker compose logs -f notifier
```

로그에 다음 조회 시각이 나오면 정상적으로 대기 중입니다. `Ctrl+C`로 로그 보기를 끝내도 컨테이너는 계속 실행됩니다.

```text
실행 시작. 조회 간격: 30분, 알림 범위: RESET_AND_SCHEDULED
다음 조회: ... 22:00:00 KST
```

서버 재부팅 후에도 자동으로 올라오게 하려면 Docker 서비스 자체도 부팅 시 시작되도록 설정해 둡니다.

```sh
sudo systemctl enable docker.service containerd.service
```

## 설정

설정은 `config/application.properties` 파일에서 읽습니다. 각 옵션 설명은 파일 안의 주석에도 적혀 있습니다.

| 설정 | 기본값 | 설명 |
|---|---|---|
| `poll.interval.minutes` | `30` | 조회 간격(분), 1~1440 |
| `notify.scope` | `reset-and-scheduled` | 알림 범위 |
| `display.time.zone` | `Asia/Seoul` | 조회 시각 기준이자 메시지에 표시할 시간대 |

### 조회 간격

실행한 시점부터 N분이 아니라, **시계 기준으로 N분 단위 시각**에 조회합니다.

| 설정값 | 21:28에 시작했을 때 |
|---|---|
| `15` | 21:30 → 21:45 → 22:00 |
| `30` | 21:30 → 22:00 → 22:30 |
| `60` | 22:00 → 23:00 → 00:00 |

### 알림 범위

| `notify.scope` | 알림 대상 |
|---|---|
| `reset-only` | 실제 리셋 기록만 |
| `reset-and-scheduled` | 실제 리셋 + 리셋 예정 공지 |
| `all` | 실제 리셋 + 리셋 예정 공지 + 리셋 가능성 예측 |

### 설정 바꾸기

설정 파일을 수정한 뒤 컨테이너만 재시작하면 됩니다. 다시 빌드할 필요는 없고, 알림 기록도 유지됩니다.

```sh
nano config/application.properties
docker compose restart notifier
```

웹훅 주소(`.env`)를 바꿨다면 컨테이너를 다시 만들어야 합니다.

```sh
docker compose up -d --force-recreate notifier
```

## 동작 방식

이미 보낸 공지의 식별값을 Docker 볼륨(`notifier-data`)의 `/data/state.json`에 저장해 두고, 매 조회 결과와 비교합니다.

- **처음 실행할 때**: 과거 리셋 기록은 저장만 하고 보내지 않습니다. 알림 범위에 포함되는 현재 예정 공지나 예측이 있으면 한 번 보냅니다.
- **그 이후**: 새 리셋 기록, 새 예정 공지, 예정 시각 변경, 새 예측 내용이 생겼을 때만 보냅니다.
- 예정 공지가 실제 리셋 기록으로 바뀌면 리셋 기록 알림을 따로 보냅니다.
- 전송에 실패하면 보낸 것으로 기록하지 않고 다음 조회 때 다시 시도합니다.
- 공지 원문의 `@everyone` 같은 문자열로 멘션이 울리지 않도록 막아 두었습니다.

### 알림 기록 초기화

처음 실행한 상태로 되돌리려면 기록 파일을 지우고 다시 시작합니다. 설정은 그대로 유지됩니다.

```sh
docker compose stop notifier
docker compose run --rm --no-deps --entrypoint sh notifier -c 'rm -f /data/state.json'
docker compose up -d notifier
```

`docker compose down -v`도 기록 볼륨을 함께 지웁니다.

## 실행 옵션

| 옵션 | 동작 |
|---|---|
| (없음) | 설정한 간격으로 계속 실행 |
| `--once` | 예약 시각을 기다리지 않고 바로 한 번 조회한 뒤 종료 |
| `--dry-run` | API를 조회해 보낼 메시지를 터미널에 출력만 함. 전송·기록 변경 없음 |
| `--help` | 도움말 |

```sh
docker compose run --rm --no-deps notifier --dry-run
```

## 로컬에서 빌드하기

JDK 17 이상이 필요합니다.

```sh
./gradlew test fatJar
java -jar build/libs/codex-reset-notifier.jar --dry-run
```

Windows에서는 `gradlew.bat test fatJar`를 사용합니다.
JAR로 직접 실행할 때는 `DISCORD_WEBHOOK_URL` 환경변수가 필요하고(`--dry-run` 제외), 설정 파일은 `config/application.properties`, 기록 파일은 `data/state.json`을 사용합니다.
경로는 `CONFIG_FILE`, `STATE_FILE` 환경변수로 바꿀 수 있습니다.

## 프로젝트 구조

```text
src/main/java/dev/codexresets/notifier/
├── Main.java                  실행 루프, 옵션 처리
├── WallClockSchedule.java     시계 기준 조회 시각 계산
├── Config.java                설정 파일·환경변수 읽기
├── NotificationScope.java     알림 범위
├── ResetApiClient.java        Codex Resets API 조회
├── Event.java                 알림 대상 데이터
├── Notifier.java              새 알림 판별, 전송 후 기록
├── DiscordWebhookClient.java  메시지 작성, 웹훅 전송
├── StateStore.java            알림 기록 저장
├── HttpTransport.java         HTTP 요청, 재시도 대기 처리
└── PollException.java         다음 조회에서 재시도할 오류
```

Spring 없이 Java `HttpClient`와 Jackson만 사용합니다.

## 데이터 출처

리셋 정보는 [Codex Resets](https://codex-resets.com/)의 무료 공개 API에서 가져옵니다. 제공자의 요청에 따라 모든 알림에 출처 링크를 붙입니다.

- [API 문서 (Swagger UI)](https://codex-resets.com/api/docs)
- [OpenAPI 명세](https://codex-resets.com/api/openapi.json)
- `GET /api/v1/status`
- `GET /api/v1/resets`
