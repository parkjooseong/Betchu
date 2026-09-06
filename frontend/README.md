# BETCHU Frontend

Expo SDK 57과 React Native 0.86으로 구성한 BETCHU 모바일 앱입니다.

## 현재 구현 범위

`/`에서 실제 Google 로그인, 연령·정책 동의, 커플 초대·상호 확인·연결 종료·차단을 진행합니다. Google 설정이 없다면 가입 준비 상태와 재확인 버튼을 보여줍니다. Google이 연결된 경우 정책이 준비되지 않아도 기존 계정 로그인은 허용하며, 신규 활성화·초대는 잠그고 기존 관계 종료·차단은 계속 제공합니다. 계정·약관·연결 상태를 임의로 만들지 않습니다.

- Google 로그인은 서버의 일회용 로그인 ID·비밀값을 발급받고 공식 로그인 화면을 엽니다. `/auth/callback`에서는 시작한 로그인 ID와 만료 시간을 확인하고, 성공 콜백으로만 전달된 일회용 `handoffCode`를 함께 제출해 세션을 교환합니다. 이 코드는 저장하지 않으며 웹 콜백의 쿼리 문자열은 즉시 주소에서 제거합니다.
- 만 14세 이상 여부는 직접 확인하며 생년월일을 받지 않습니다. 정책은 게시된 원문 링크와 버전을 보여주며, 필수·선택 동의를 기본 체크하지 않습니다. 서버가 `ACTIVE`로 응답할 때만 가입 완료와 실제 내 잔액을 표시합니다.
- 초대 상대를 먼저 확인하고 두 사람이 각각 확인해야 연결됩니다. 초대 코드 발급·재발급·취소, 만료 오류·재시도, 연결 종료·차단 확인을 제공합니다. 대기 중이고 앱이 전경일 때만 5초 간격으로 갱신합니다.
- 활성 계정의 정책 재동의 중에는 초대와 파트너 조회를 잠그고, 연결 종료·차단은 별도 안전 동작으로 계속 제공합니다.
- `/preview`에서는 로그인 없이 네 종류의 스타팅 배츄를 고르고 이름을 입력한 뒤 **미리보기**를 확인합니다.

- `GET /api/v1/monsters/starters`: 종류, 이름 규칙, 초기 능력치, 성장 기준 조회
- `POST /api/v1/monsters/starter-preview`: 종류와 이름을 서버에서 검증한 후 미리보기 반환
- 이름은 NFC 정규화와 양끝 Unicode 공백 제거 후 1~10 Unicode code point로 제한하며, 제어·서식 문자와 내부 줄바꿈을 거절합니다.
- 네 종류 모두 같은 알 모습, Lv.1 / HP 100 / ATK 10 / 전투력 700 / XP 0/80으로 시작합니다. 종별 색상은 선택 표식에만 사용합니다.
- 목록·미리보기 로딩, 입력 오류, 서버 오류, 10초 응답 제한과 재시도를 처리합니다. 돌아가기와 다시 고르기에서 입력을 유지합니다.

미리보기는 계정 활성화·커플 연결·배츄 저장·코인 지급을 수행하지 않습니다. 앱을 새로 열면 미리보기 선택과 이름은 초기화됩니다. 실제 스타터 생성·퀘스트·지갑 내역은 후속 구현 범위입니다. 팔레트와 알 일러스트는 최종 아트 납품 전 사용하는 개발용 디자인입니다.

## 로그인 정보 저장

- 네이티브: OAuth 시도 비밀값과 갱신 토큰은 기기 전용 SecureStore에 저장하며 접근 토큰은 메모리에만 둡니다.
- 웹: 접근·갱신 토큰은 메모리에만 유지하므로 새로고침하면 다시 로그인합니다. OAuth 이동 전후에 필요한 일회용 시도 정보만 탭별 `sessionStorage`에 잠시 보관하며 해당 시도의 완료·실패·취소·만료 시 삭제합니다. 다른 시도의 잘못된 콜백으로 현재 로그인 정보를 삭제하지 않습니다. `localStorage`는 사용하지 않습니다.
- 동시 401 응답은 한 번의 토큰 갱신으로 처리합니다. 로그아웃·세션 만료 시 사용자 상태와 Query 캐시를 지우며, 늦게 도착한 응답이 계정을 복구하지 못하도록 세션 세대를 검사합니다. 저장소 오류를 숨기지 않습니다.

## 요구 환경

- Node.js 24 LTS
- Corepack
- pnpm 10.34.5
- Android Studio 또는 iOS용 macOS·Xcode

## 처음 실행

```bash
corepack enable
corepack install
cp .env.example .env.local
pnpm install --frozen-lockfile
pnpm start
```

Android Emulator에서 로컬 백엔드에 연결할 때는 `.env.local`의 API 주소를
`http://10.0.2.2:8080/api/v1`로 변경합니다.

현재 앱 구현은 `frontend` 브랜치, 대응 API 구현은 `backend` 브랜치에 있습니다. 동시에 실행하려면 저장소 루트에서 별도 worktree를 준비합니다. 기존 worktree가 있으면 생성 명령을 생략합니다.

```bash
# Betchu repository root, frontend branch
git worktree add ../Betchu-backend backend

# Separate terminal: real onboarding requires PostgreSQL and backend configuration
cd ../Betchu-backend/backend
./gradlew bootRun

# Frontend terminal
cd frontend
corepack pnpm web
```

Windows에서는 `./gradlew` 대신 `./gradlew.bat`을 사용합니다. 실제 가입·연결에는 `backend` 브랜치 README에 정의한 PostgreSQL, JWT 서명 키, Google OAuth, 게시된 정책 설정이 필요합니다. OAuth 비밀값과 서명 키는 백엔드에만 설정하며 `EXPO_PUBLIC_` 환경변수에 넣지 않습니다. 설정이 없다면 미리보기와 가입 준비 상태를 확인할 수 있지만 실제 로그인은 진행할 수 없습니다.

웹 개발 서버는 `http://localhost:8081`입니다. 프론트엔드 반환 주소는 웹 `http://localhost:8081/auth/callback`, 네이티브 `betchu://auth/callback`이며 백엔드 허용 목록과 일치해야 합니다. Google에 등록하는 OAuth 콜백은 백엔드의 별도 Google 콜백 주소입니다. 네이티브 OAuth는 `betchu` 스킴을 등록한 개발 빌드 또는 설치 앱에서 확인합니다.

실기기에서는 `EXPO_PUBLIC_API_BASE_URL`에 개발 PC의 LAN 주소를 사용하고 같은 네트워크에서 연결합니다. 환경변수를 변경한 뒤 Expo를 다시 시작합니다. 화면 경로를 추가한 직후에는 Expo 서버를 시작해 `.expo/types/router.d.ts`를 다시 생성한 뒤 타입 검사를 실행합니다.

## 품질 검사

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm format:check
```

## 주요 구조

```text
src/app          Expo Router 화면
src/api          OpenAPI 기반 API client와 생성 타입
src/config       공개 환경변수 검증
src/providers    QueryClient 등 앱 전역 provider
src/theme        디자인 토큰
src/features/starter  스타팅 배츄 선택·미리보기와 검증 테스트
src/features/onboarding  로그인·정책 동의·커플 연결, 세션 저장과 검증 테스트
```

`EXPO_PUBLIC_` 변수는 앱 번들에 포함되므로 비밀값을 넣지 않습니다.
