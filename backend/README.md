# BETCHU Backend

Java 21과 Spring Boot 4.1로 구성한 BETCHU API 서버입니다.

## 처음 실행

루트에서 로컬 서비스를 먼저 실행합니다.

```bash
docker compose -f infra/compose.yaml up -d
```

그 다음 백엔드를 실행합니다.

```bash
./gradlew bootRun
```

Windows에서는 `gradlew.bat bootRun`을 사용합니다.

기본 확인 URL은 다음과 같습니다.

```text
GET http://localhost:8080/api/v1/system/health
GET http://localhost:8080/actuator/health
```

## 스타팅 배츄 미리보기

첫 기능으로 로그인 없이 스타팅 배츄 4종과 공통 능력치·성장 기준을 조회하고, 종류와 이름을 미리 확인할 수 있다.

```text
GET /api/v1/monsters/starters
POST /api/v1/monsters/starter-preview
Content-Type: application/json

{"species":"STARLIGHT","name":"별이"}
```

미리보기 응답의 `previewOnly`는 `true`이며, `starter`, 정규화된 `name`, `stage: EGG`, `recognizedSuccessCount: 0`, `stats`, `growthMilestones`를 반환한다. 실제 회원·배츄·지갑을 생성하거나 코인·성공 수를 변경하지 않는다. 실제 생성 API와 로그인·커플 연결은 후속 구현이다.

- 종은 `STARLIGHT`, `WAVE`, `SUNSET`, `FOREST` 중 하나다. 네 종 모두 레벨 1, HP 100, ATK 10, 전투력 700, XP 0, 다음 레벨 필요 XP 80으로 시작한다.
- 이름은 NFC 정규화와 Unicode `White_Space` 앞뒤 제거 후 1~10 Unicode 코드 포인트다. 내부 줄바꿈·제어 문자(`Cc`)·포맷 문자(`Cf`)는 거절한다.
- 요청은 `species`, `name` 두 문자열만 받는다. 잘못된 종류·이름·JSON은 `400 application/problem+json`, `errorCode: INVALID_STARTER_SELECTION`으로 응답하며 입력값은 오류에 노출하지 않는다.
- 튜토리얼 성공은 인정 성공 0회를 유지하며 부화한다. 일반 퀘스트의 첫 인정 성공도 부화 조건이다. 20·40·60·80회 기준은 기획안 15절을 따르며, 80회 새 알 선택은 후속 P2 기능이다.
- `CORS_ALLOWED_ORIGINS`에 지정한 웹 출처만 위 두 경로를 호출할 수 있다. 기본값은 `http://localhost:8081,http://localhost:19006`이다. `BETCHU_SECURITY_ENABLED=true`에서도 위 GET/POST만 추가 공개하고 나머지 API 인증 정책은 유지한다.

공유 계약은 [`packages/api-contract/openapi.yaml`](../packages/api-contract/openapi.yaml)을 기준으로 한다.

### Docker 없이 미리보기 확인

현재 두 미리보기 API는 DB를 사용하지 않는다. Docker가 없다면 아래처럼 해당 실행 프로세스에만 DB 자동 설정을 제외해 확인할 수 있다. 설정 파일은 바꾸지 않으며, 이후 회원·배츄 저장이나 정산 기능 검증에는 정상 DB 실행 환경을 사용해야 한다.

```bash
./gradlew bootRun --args='--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration'
```

Windows PowerShell에서는 `./gradlew` 대신 `./gradlew.bat`을 사용한다. 서버는 동일한 `http://localhost:8080`에서 실행된다.

## 품질 검사

```bash
./gradlew spotlessCheck test integrationTest
```

Docker가 없는 환경에서는 Testcontainers 통합 테스트가 자동으로 건너뛰어진다.

## 설정 원칙

- 모든 영구 상태와 원장은 PostgreSQL을 기준으로 한다.
- DB 변경은 `src/main/resources/db/migration`의 Flyway 파일로만 적용한다.
- 실제 비밀값은 `.env`나 운영 secret manager에 저장하고 Git에 커밋하지 않는다.
- 서버 시각은 UTC로 저장하고 주간·일일 정책은 `Asia/Seoul` 기준으로 계산한다.
