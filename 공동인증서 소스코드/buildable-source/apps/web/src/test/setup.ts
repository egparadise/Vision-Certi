import '@testing-library/jest-dom';

// Polyfill URL.createObjectURL and revokeObjectURL for tests
if (typeof URL !== 'undefined') {
  URL.createObjectURL = () => 'blob:mock-url';
  URL.revokeObjectURL = () => {};
}
if (typeof window !== 'undefined' && window.URL) {
  window.URL.createObjectURL = () => 'blob:mock-url';
  window.URL.revokeObjectURL = () => {};
}
