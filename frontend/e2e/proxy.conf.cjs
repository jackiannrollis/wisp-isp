module.exports = {
  '/api': {
    target: process.env.E2E_API_URL ?? 'http://127.0.0.1:28080',
    secure: false,
    changeOrigin: true,
  },
};
