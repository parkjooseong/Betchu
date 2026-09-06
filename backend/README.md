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

미리보기 응답의 `previewOnly`는 `true`이며, `starter`, 정규화된 `name`, `stage: EGG`, `recognizedSuccessCount: 0`, `stats`, `growthMilestones`를 반환한다. 이 미리보기는 회원·배츄·지갑을 생성하거나 코인·성공 수를 변경하지 않는다.

- 종은 `STARLIGHT`, `WAVE`, `SUNSET`, `FOREST` 중 하나다. 네 종 모두 레벨 1, HP 100, ATK 10, 전투력 700, XP 0, 다음 레벨 필요 XP 80으로 시작한다.
- 이름은 NFC 정규화와 Unicode `White_Space` 앞뒤 제거 후 1~10 Unicode 코드 포인트다. 내부 줄바꿈·제어 문자(`Cc`)·포맷 문자(`Cf`)는 거절한다.
- 요청은 `species`, `name` 두 문자열만 받는다. 잘못된 종류·이름·JSON은 `400 application/problem+json`, `errorCode: INVALID_STARTER_SELECTION`으로 응답하며 입력값은 오류에 노출하지 않는다.
- 튜토리얼 성공은 인정 성공 0회를 유지하며 부화한다. 일반 퀘스트의 첫 인정 성공도 부화 조건이다. 20·40·60·80회 기준은 기획안 15절을 따르며, 80회 새 알 선택은 후속 P2 기능이다.
- `CORS_ALLOWED_ORIGINS`에 지정한 웹 출처만 위 두 경로를 호출할 수 있다. 기본값은 `http://localhost:8081,http://localhost:19006`이다. 공개 경로를 제외한 API는 항상 세션 인증을 요구하며, 이전 `BETCHU_SECURITY_ENABLED=false` 설정으로 인증을 해제할 수 없다.

공유 계약은 [`packages/api-contract/openapi.yaml`](../packages/api-contract/openapi.yaml)을 기준으로 한다.

## 내 배츄 등록과 홈

아래 API는 활성 계정과 현재 필수 정책 동의를 확인한다. 첫 등록에는 두 사람이 모두 연결을 확정한 현재 커플이 필요하다.

```text
POST /api/v1/monsters/starter
Authorization: Bearer <accessToken>
Idempotency-Key: <UUID>
Content-Type: application/json

{"species":"STARLIGHT","name":"별이"}
```

계정당 스타터 한 마리, 계정 성장 정보, 현재 배츄 참조와 최초 응답을 한 트랜잭션으로 저장하고 `201 MonsterView`를 반환한다. 같은 요청 키와 정규화된 종류·이름으로 재시도하면 최초 응답을 다시 받는다. 이후 이름을 바꾸거나 커플 관계를 종료해도 최초 응답은 유지된다. 같은 키로 다른 입력을 보내면 `409 IDEMPOTENCY_KEY_REUSED`, 새 키로 다시 선택하면 `409 STARTER_ALREADY_EXISTS`다. 등록으로 추가 코인이나 경험치를 지급하지 않는다.

```text
GET /api/v1/monsters/me
GET /api/v1/monsters/partner
PATCH /api/v1/monsters/me/name
Content-Type: application/json

{"name":"새이름"}

GET /api/v1/home
```

- 내 배츄 조회는 `{monster: MonsterView | null}`이다. 이름 변경은 무료이며 미리보기와 같은 Unicode 이름 규칙을 적용한다. 아직 배츄가 없으면 이름 변경은 `404 MONSTER_NOT_FOUND`다.
- `MonsterView`는 저장된 성장 단계와 계정 능력치를 반환한다. 튜토리얼 성공 후에는 인정 성공 0회를 유지하며 `BABY` 단계와 도달한 `HATCH`를 표시한다. 일반 퀘스트 성장 보상과 장비 지급 API는 아직 없으며 `masteryRewards`는 빈 배열, 장비 보너스는 0이다.
- 상대 배츄는 현재 유효한 커플 관계에서만 조회할 수 있다. 연결이 없거나 끝났거나 차단되면 `409 COUPLE_REQUIRED`다. 내 배츄·성장·지갑은 관계 종료와 재연결 후에도 유지된다.
- 홈은 서버 시각, 현재 연결, 내 지갑과 배츄, 허용된 상대 프로필과 배츄를 반환한다. 상대 지갑이나 초안 내용은 포함하지 않는다. `ownDraftCount`는 현재 커플에서 내가 작성한 초안만 세며, 연결이 없으면 0이다. 공유 퀘스트 상태 수는 저장된 튜토리얼의 승인 대기·진행·결과 확인 상태를 포함한다.
- 관계 종료와 게임 쓰기는 동일한 PostgreSQL 트랜잭션 잠금을 사용한다. 종료된 관계에 새 스타터나 초안이 뒤늦게 저장되는 일을 방지한다.

관련 마이그레이션은 `V5__monster_progression.sql`과 `V6__quest_drafts.sql`이다. 게임 통합 테스트는 실제 PostgreSQL에서 원자성, 동시에 들어온 요청, 관계 종료·재연결 보존과 홈 공개 범위를 검사한다.

```bash
./gradlew test --tests com.betchu.backend.game.GameControllerTest
./gradlew integrationTest --tests com.betchu.backend.game.MonsterProgressionIntegrationTest
```

## 작성자 전용 퀘스트 초안

- `POST /quests`는 `Idempotency-Key`로 개인 자유 입력 초안을 생성한다. `GET /quests?status=DRAFT`는 현재 관계의 본인 초안만 커서 방식으로 조회한다. 기본 20개, 최대 50개다.
- `GET /quests/{questId}`, `PATCH /quests/{questId}/draft`, `DELETE /quests/{questId}/draft`는 작성자와 현재 관계를 검사한다. 타인·이전 관계·없는 초안은 모두 404다. 수정은 본문의 `expectedRowVersion`, 폐기는 같은 이름의 쿼리와 멱등 키가 필요하다.
- 초안에는 제목·카테고리·성공 조건·난이도·제안 코인·수행 마감·결과 확인 시작·최소 수행 시간·인증 방식의 9개 항목을 저장한다. 난이도는 1~4, 제안 코인은 0/100C, 인증은 `NONE`만 지원한다. 튜토리얼 완료 전에도 작성할 수 있으며 코인·XP·슬롯을 변경하지 않는다.
- 제목 1~80자, 성공 조건 1~1,000자, 최소 수행 시간 0~10,080분은 초기 구현의 입력 제한이다. NFC 정규화와 Unicode 공백 제거를 적용하며 정상 Unicode 코드 포인트를 센다. 성공 조건의 내부 LF 외 제어 문자와 고립 surrogate는 허용하지 않는다.
- 날짜는 명시적 UTC 오프셋이 필요하고 UTC 기준 2000년 이상 2101년 미만이어야 한다. PostgreSQL 마이크로초 정밀도로 맞춘 후 결과 확인 시작이 수행 마감보다 뒤인지 검사한다. 초안은 과거 날짜도 허용한다.
- 동일 생성 요청은 최초 응답을 재현하며 후속 수정을 덮어쓰지 않는다. 폐기 시 본문과 최초 응답 본문을 삭제한다. 이미 폐기한 초안의 생성 재시도는 404이며, 같은 폐기 요청은 버전을 다시 증가시키지 않는다.

## 관계 종료 후 초안 정리

연결 종료·차단은 먼저 관계 접근 차단과 정리 작업·대상 목록을 커밋한다. 이후 작업 처리기가 초안을 항목별 트랜잭션으로 취소한다. 처리 실패는 다음 실행에서 재시도하며 이미 확정한 접근 차단을 되돌리지 않는다. 모든 대상의 정리가 끝날 때까지 새 연결을 막는다.

`POST /couples/me/end`와 `/block`은 `PROCESSING` 또는 `COMPLETED`를 반환한다. `GET /couples/me/end-status`는 최신 작업 상태를 반환하며, 정책 재동의 중에도 이용할 수 있다. 반환하는 대상·처리 건수는 본인이 작성한 정리 대상만 집계하고 전체 작업 상태는 별도로 표시한다. 이전 멱등 키는 이전 관계에만 적용된다.

처리기는 기본 5초 간격, 보관 기한 삭제는 1시간 간격으로 실행한다. 관계 종료 30일이 지난 본문과 최초 생성 응답은 취소 작업이 아직 실패 중이어도 삭제한다. 개인 본문 내보내기 API는 아직 구현하지 않았다. 일반 API에서는 종료 즉시 이전 관계 초안을 조회할 수 없다.

```bash
./gradlew test --tests com.betchu.backend.quests.QuestControllerTest --tests com.betchu.backend.quests.QuestValidationTest
./gradlew integrationTest --tests com.betchu.backend.quests.QuestDraftIntegrationTest
```

일반 퀘스트 제출·승인·정산과 사진 인증은 후속 구현이다. 전체 요청·응답은 [공통 API 계약](../packages/api-contract/README.md)을 따른다.

## 튜토리얼 정산과 관계 종료

튜토리얼은 계정당 한 번 진행하는 고정 시스템 퀘스트다. 상대가 시작을 승인할 때 작성자의 실제 사용 가능 코인 100C를 잠금 코인으로 옮긴다. 요청 재시도와 동시 승인은 잠금을 중복하지 않으며, 잔액 부족 응답에는 상대 잔액을 포함하지 않는다.

- 상대의 최종 성공 승인으로 원금 100C와 보너스 100C를 돌려주고 XP 10을 지급한다. 계정 레벨 50에서는 추가 XP 없이 보너스와 부화를 처리한다. 알은 `BABY`로 부화하며 `HATCH` 보상 기록은 한 번만 저장한다. 인정 성공 수·스트릭·케미·일일 보상 한도는 변경하지 않는다.
- 승인 후 실패·무효·합의 취소·관계 종료는 실제 잠긴 원금만 돌려준다. 성공 보너스·XP·부화는 지급하지 않는다. 승인 전 종료에는 코인 이동이나 정산 행을 만들지 않는다.
- 종료 상태, 지갑, 단계별 코인 원장, 퀘스트당 유일한 정산, XP, 부화 기록과 `tutorial_completed_at`은 한 트랜잭션으로 확정한다. 중간 실패는 전부 롤백하고 재시도할 수 있다.
- 승인 거절·승인 기한 만료·취소도 튜토리얼 완료로 기록하여 다시 만들 수 없게 한다. 이는 이번 구현에서 정한 일회성 온보딩 정책이며 성공 보상을 의미하지 않는다. 튜토리얼을 만들지 않은 사용자는 관계 종료만으로 완료 처리하지 않는다.
- 연결 종료는 열려 있는 튜토리얼을 정리 작업에 함께 기록하고 접근을 먼저 차단한다. 처리기는 실제 저장된 관계 종료 시각을 기준으로, 승인 전 또는 수행 마감 전이면 `CANCELED_RELATIONSHIP_ENDED`, 승인 후 수행 마감 이상이면 `INVALID`와 `RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL`로 정산한다. 작업이 늦어져도 분류는 바뀌지 않는다.
- 종료된 관계의 튜토리얼 API 접근은 즉시 차단한다. 관계 종료 30일 뒤에는 튜토리얼 응답 스냅샷도 삭제하며 정산 재시도에 필요한 고정 템플릿·원장·시각 기록은 유지한다. 본인 기록 내보내기 API는 아직 구현하지 않았다.

```bash
./gradlew integrationTest --tests com.betchu.backend.tutorial.TutorialSettlementIntegrationTest
```

## Google 로그인과 계정 활성화

인증·정책 동의·지갑은 PostgreSQL에 저장하므로 서버 실행에는 PostgreSQL이 필요하다. Docker Compose 또는 별도 PostgreSQL 18에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 연결한다. 이전 미리보기 단계의 DB 자동 설정 제외 실행 명령은 사용하지 않는다.

환경변수는 `.env.example`을 참고해 실행 프로세스에 제공한다. Spring Boot와 Gradle은 `.env` 파일을 자동으로 읽지 않는다. 비밀값을 저장소에 커밋하지 않는다.

- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`: Google Cloud의 웹 애플리케이션 OAuth 클라이언트. `GOOGLE_CALLBACK_URI`를 Google 승인 리디렉션 URI에 정확히 등록한다. 기본 로컬 콜백은 `http://localhost:8080/api/v1/auth/oauth/google/callback`이다.
- `JWT_SIGNING_SECRET`: 암호학적 난수 32바이트 이상을 Base64로 인코딩한 서버 전용 서명키. 미설정이면 토큰을 발급하지 않으며, 형식이 잘못되면 서버 시작에 실패한다.
- `OAUTH_REDIRECT_URIS`: 로그인 후 앱으로 돌아갈 주소의 정확한 허용 목록. 기본값은 `betchu://auth/callback,http://localhost:8081/auth/callback`이다. 기기·웹 호스트를 바꾸면 해당 주소와 `CORS_ALLOWED_ORIGINS`를 명시적으로 설정한다.
- `BETCHU_INVITE_HMAC_SECRET`: 커플 초대 코드 검증용 별도 난수 키이며 Base64로 인코딩한 32바이트 이상을 사용한다.

`GET /auth/providers`에서 외부 로그인 설정과 가입 준비 상태를 확인한다. Google 클라이언트나 서명키가 없으면 `enabled=false`이며 로그인 시작은 `503 AUTH_UNAVAILABLE`이다. 테스트 로그인이나 개발용 인증 우회는 제공하지 않는다.

1. `POST /auth/login/start`에 `provider: GOOGLE`, 허용된 `redirectUri`를 보낸다. 앱은 반환된 `loginId`, `loginSecret`을 보관하고 `authorizationUrl`을 연다.
2. 서버 콜백에서 일회용 `state`, PKCE와 Google 서명·발급자·대상 클라이언트·만료·`nonce`를 검증한다. 앱에는 `loginId`, `status=SUCCESS|FAILED`를 전달하며 성공한 콜백에만 최대 2분 유효한 일회용 `handoffCode`를 추가한다. 액세스·리프레시 토큰과 `loginSecret`은 리디렉션에 넣지 않는다.
3. 앱이 `POST /auth/login`에 `loginId`, 로컬에 보관한 `loginSecret`, 콜백으로 받은 `handoffCode`를 함께 보내면 검증 완료 시도만 원자적으로 소비하고 토큰과 `user`를 반환한다. 콜백 URL의 인증 코드는 앱에서 즉시 제거한다. 로그인 시작 비밀값만 가진 장치가 다른 장치에서 완료한 로그인을 가져갈 수 없다. 신규 사용자는 `PENDING_ELIGIBILITY` 상태이며 지갑이 없다.
4. `POST /onboarding/age-eligibility`의 `eligible: true`와 `PUT /consents/{policyType}`의 현재 `policyVersionId`, `locale: ko-KR` 동의를 모두 완료하면 계정을 활성화한다. 지갑과 `SIGNUP_GRANT` 1,000C 원장은 한 트랜잭션에서 계정당 한 번만 생성한다.
5. 일반 API는 `Authorization: Bearer <accessToken>`을 사용한다. 액세스 토큰은 5분이며 `POST /auth/refresh`는 리프레시 토큰을 매번 회전한다. `POST /auth/logout`은 해당 세션의 액세스·리프레시 토큰을 즉시 무효화한다.

리프레시 토큰과 OAuth 시도 비밀값·상태값·콜백 교환 코드는 해시로 저장한다. 미완료 OAuth 시도는 10분 만료 후, 콜백 교환 코드는 최대 2분 만료 후 정리하며 임시 사용자·프로필은 생성 23시간 이후 1분 주기로 정리한다. 연령 기준 미달 또는 확인 거절(`false`/`null`)이면 제한 세션과 임시 사용자를 즉시 삭제한다. 활성 계정을 이 호출로 삭제할 수 없다.

일반 요청마다 사용자 상태와 현재 필수 동의를 확인한다. 정책이 바뀌면 `/users/me`, 정책 재동의, 로그아웃, 커플 종료·차단을 제외한 일반 기능은 `403 ONBOARDING_REQUIRED`로 제한한다. 리프레시 토큰을 갱신해도 제한을 우회할 수 없다.

## 승인된 정책 문서 등록

정책 본문이나 승인되지 않은 정책을 임의로 생성하지 않는다. `V2__auth_accounts.sql`은 정책 테이블만 만들며 활성 정책을 기본 삽입하지 않는다. 운영 검토를 마친 HTTPS 문서 URL·버전·로케일·시행 시각을 `policy_versions`에 등록하고 배포 절차에서 활성화해야 한다. `TERMS`, `PRIVACY`는 필수, `EVIDENCE_OPTIONAL`은 선택이다. 정책 유형·로케일별 활성 버전은 하나만 허용한다.

현재 시행 중인 한국어 `TERMS`와 `PRIVACY`가 모두 없으면 `GET /policy-versions/current`는 `ready=false`이며 가입이 활성화되지 않는다. 문서 교체 시 새 버전으로 등록하고 기존 동의는 보존한다. 클라이언트는 현재 문서 URL을 열어 사용자가 확인한 정확한 버전만 전송한다.

Google 인증 흐름은 [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)와 [서버 OAuth 흐름](https://developers.google.com/identity/protocols/oauth2/web-server)을 따른다.

## 품질 검사

```bash
./gradlew spotlessCheck test integrationTest
```

통합 테스트는 Testcontainers를 기본으로 사용한다. 별도 테스트 전용 PostgreSQL이 있으면 `BETCHU_TEST_DB_URL`, `BETCHU_TEST_DB_USERNAME`, `BETCHU_TEST_DB_PASSWORD`를 설정해 실제 DB에서 실행할 수 있다. 운영 DB를 지정하지 않는다. 별도 테스트 DB와 Docker가 모두 없을 때만 건너뛴다.

```bash
./gradlew integrationTest --tests com.betchu.backend.auth.AuthAccountIntegrationTest
```

## 설정 원칙

- 모든 영구 상태와 원장은 PostgreSQL을 기준으로 한다.
- DB 변경은 `src/main/resources/db/migration`의 Flyway 파일로만 적용한다.
- 실제 비밀값은 `.env`나 운영 secret manager에 저장하고 Git에 커밋하지 않는다.
- 서버 시각은 UTC로 저장하고 주간·일일 정책은 `Asia/Seoul` 기준으로 계산한다.
