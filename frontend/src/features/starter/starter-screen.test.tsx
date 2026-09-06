import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import mockSafeAreaContext from 'react-native-safe-area-context/jest/mock';

import { getStarterCatalog, getStarterPreview, StarterPreview } from './api';
import { StarterScreen } from './starter-screen';
import { catalogFixture, previewFixture } from './test-fixtures';

jest.mock('./api', () => ({ getStarterCatalog: jest.fn(), getStarterPreview: jest.fn() }));
jest.mock('react-native-safe-area-context', () => mockSafeAreaContext);

const catalogMock = jest.mocked(getStarterCatalog);
const previewMock = jest.mocked(getStarterPreview);

async function renderScreen() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  await render(
    <QueryClientProvider client={client}>
      <StarterScreen />
    </QueryClientProvider>,
  );
}

async function chooseWave() {
  await fireEvent.press(await screen.findByRole('radio', { name: '파도 배츄' }));
  await fireEvent.changeText(screen.getByLabelText('배츄 이름'), '  말랑이  ');
}

beforeEach(() => {
  jest.clearAllMocks();
  catalogMock.mockResolvedValue(catalogFixture);
  previewMock.mockResolvedValue(previewFixture);
});

it('loads four server-provided starters and keeps preview results absent before submitting', async () => {
  await renderScreen();
  expect(await screen.findByRole('radio', { name: '파도 배츄' })).not.toBeChecked();
  expect(screen.getAllByRole('radio')).toHaveLength(4);
  expect(screen.getByText('미리보기')).toBeOnTheScreen();
  expect(screen.queryByText('말랑이, 반가워!')).not.toBeOnTheScreen();
  expect(previewMock).not.toHaveBeenCalled();
});

it('validates missing selection and invalid names before making a request', async () => {
  await renderScreen();
  const submit = await screen.findByRole('button', { name: '내 배츄 미리보기 →' });
  await fireEvent.press(submit);
  expect(screen.getByText('마음에 드는 배츄를 한 종류 골라 주세요.')).toBeOnTheScreen();
  expect(screen.getByText('배츄를 부를 이름을 입력해 주세요.')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('radio', { name: '파도 배츄' }));
  await fireEvent.changeText(screen.getByLabelText('배츄 이름'), '열한글자이상인배츄이름');
  await fireEvent.press(submit);
  expect(screen.getByText('이름은 1~10자로 입력해 주세요.')).toBeOnTheScreen();
  await fireEvent.changeText(screen.getByLabelText('배츄 이름'), '배\u200B츄');
  await fireEvent.press(submit);
  expect(screen.getByText(/보이지 않는 제어 문자/)).toBeOnTheScreen();
  expect(previewMock).not.toHaveBeenCalled();
});

it('waits for the real response, blocks changes in flight, and preserves selections for editing', async () => {
  let finish!: (preview: StarterPreview) => void;
  previewMock.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  await renderScreen();
  await chooseWave();
  await fireEvent.press(screen.getByRole('button', { name: '내 배츄 미리보기 →' }));
  await waitFor(() =>
    expect(previewMock).toHaveBeenCalledWith(
      { species: 'WAVE', name: '말랑이' },
      expect.anything(),
    ),
  );
  expect(screen.getByLabelText('배츄 이름')).toBeDisabled();
  expect(screen.getByRole('radio', { name: '별빛 배츄' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '미리보기 불러오는 중…' })).toBeDisabled();
  expect(screen.queryByText('말랑이, 반가워!')).not.toBeOnTheScreen();
  await act(() => {
    finish(previewFixture);
  });
  expect(await screen.findByText('말랑이, 반가워!')).toBeOnTheScreen();
  expect(screen.getByText('알 · EGG')).toBeOnTheScreen();
  expect(screen.getByText('700')).toBeOnTheScreen();
  expect(screen.getByText('0 / 80 XP')).toBeOnTheScreen();
  expect(screen.getByText(/실제 배츄 생성과 코인 지급은 이루어지지 않아요/)).toBeOnTheScreen();
  expect(screen.getByText(/새 알 선택은 후속 P2/)).toBeOnTheScreen();

  await fireEvent.press(screen.getByRole('button', { name: '선택으로 돌아가기' }));
  expect(screen.getByLabelText('배츄 이름')).toHaveDisplayValue('말랑이');
  expect(screen.getByRole('radio', { name: '파도 배츄' })).toBeChecked();
  await fireEvent.press(screen.getByRole('radio', { name: '숲 배츄' }));
  await fireEvent.changeText(screen.getByLabelText('배츄 이름'), '새싹이');
  previewMock.mockResolvedValueOnce({
    ...previewFixture,
    starter: catalogFixture.starters[3],
    name: '새싹이',
  });
  await fireEvent.press(screen.getByRole('button', { name: '내 배츄 미리보기 →' }));
  expect(await screen.findByText('새싹이, 반가워!')).toBeOnTheScreen();
  expect(screen.queryByText('말랑이, 반가워!')).not.toBeOnTheScreen();
});

it('retains input when the server rejects a preview and retries the same selection', async () => {
  previewMock.mockRejectedValueOnce(new Error('종류와 이름을 확인해 주세요.'));
  await renderScreen();
  await chooseWave();
  await fireEvent.press(screen.getByRole('button', { name: '내 배츄 미리보기 →' }));
  expect(await screen.findByText('종류와 이름을 확인해 주세요.')).toBeOnTheScreen();
  expect(screen.getByLabelText('배츄 이름')).toHaveDisplayValue('말랑이');
  expect(screen.queryByText('말랑이, 반가워!')).not.toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: '미리보기 다시 시도' }));
  expect(await screen.findByText('말랑이, 반가워!')).toBeOnTheScreen();
  expect(previewMock).toHaveBeenCalledTimes(2);
});

it('shows a recoverable catalog error without silently substituting local starters', async () => {
  catalogMock.mockRejectedValueOnce(new Error('서버에 연결할 수 없어요.'));
  await renderScreen();
  expect(await screen.findByText('서버에 연결할 수 없어요.')).toBeOnTheScreen();
  expect(screen.queryAllByRole('radio')).toHaveLength(0);
  await fireEvent.press(screen.getByRole('button', { name: '목록 다시 불러오기' }));
  expect(await screen.findByRole('radio', { name: '별빛 배츄' })).toBeOnTheScreen();
  expect(catalogMock).toHaveBeenCalledTimes(2);
});
