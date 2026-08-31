# BETCHU Frontend

Expo SDK 57과 React Native 0.86으로 구성한 BETCHU 모바일 앱입니다.

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
```

`EXPO_PUBLIC_` 변수는 앱 번들에 포함되므로 비밀값을 넣지 않습니다.
