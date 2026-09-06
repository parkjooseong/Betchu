# BETCHU Frontend

Expo SDK 57과 React Native 0.86으로 구성한 BETCHU 모바일 앱입니다.

## 현재 구현 범위

첫 화면에서 서버가 제공하는 네 종류의 스타팅 배츄를 고르고 이름을 입력한 뒤 **미리보기**를 확인합니다.

- `GET /api/v1/monsters/starters`: 종류, 이름 규칙, 초기 능력치, 성장 기준 조회
- `POST /api/v1/monsters/starter-preview`: 종류와 이름을 서버에서 검증한 후 미리보기 반환
- 이름은 NFC 정규화와 양끝 Unicode 공백 제거 후 1~10 Unicode code point로 제한하며, 제어·서식 문자와 내부 줄바꿈을 거절합니다.
- 네 종류 모두 같은 알 모습, Lv.1 / HP 100 / ATK 10 / 전투력 700 / XP 0/80으로 시작합니다. 종별 색상은 선택 표식에만 사용합니다.
- 목록·미리보기 로딩, 입력 오류, 서버 오류, 10초 응답 제한과 재시도를 처리합니다. 돌아가기와 다시 고르기에서 입력을 유지합니다.

미리보기는 계정 활성화·커플 연결·배츄 저장·코인 지급을 수행하지 않습니다. 앱을 새로 열면 선택과 이름은 초기화됩니다. 로그인·실제 스타터 생성·퀘스트·지갑은 후속 구현 범위입니다. 팔레트와 알 일러스트는 최종 아트 납품 전 사용하는 개발용 디자인입니다.

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

# Separate terminal: public preview API, no Docker/database required
cd ../Betchu-backend/backend
./gradlew bootRun --args='--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration'

# Frontend terminal
cd frontend
corepack pnpm web
```

Windows에서는 `./gradlew` 대신 `./gradlew.bat`을 사용합니다. 위 명령은 해당 실행에서만 DB 자동 설정을 제외합니다. 향후 저장 기능을 구현할 때는 정상 DB 환경에서 검증해야 합니다. 웹 개발 서버는 `http://localhost:8081`이며 백엔드 기본 CORS 설정이 이 출처의 API 호출을 허용합니다. 실기기에서는 `EXPO_PUBLIC_API_BASE_URL`에 개발 PC의 LAN 주소를 사용하고 같은 네트워크에서 연결합니다. 환경변수를 변경한 뒤 Expo를 다시 시작합니다.

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
```

`EXPO_PUBLIC_` 변수는 앱 번들에 포함되므로 비밀값을 넣지 않습니다.
