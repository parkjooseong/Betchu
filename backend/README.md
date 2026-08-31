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
