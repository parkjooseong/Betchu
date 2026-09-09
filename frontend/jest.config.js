module.exports = {
  preset: 'jest-expo',
  // Babel helpers are hoisted privately by pnpm's isolated node_modules layout.
  modulePaths: ['<rootDir>/node_modules/.pnpm/node_modules'],
  testPathIgnorePatterns: ['/node_modules/', '/dist/'],
  collectCoverageFrom: ['src/**/*.{ts,tsx}', '!src/api/generated/**'],
};
