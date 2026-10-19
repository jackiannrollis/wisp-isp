import { defineConfig } from 'vitest/config';

// Extra Vitest settings for both apps (angular.json test.options.runnerConfig).
// @material/material-color-utilities 0.4.0 ships ESM with an extensionless relative import, which
// bundlers resolve but plain Node does not; letting Vite transform it fixes that under Vitest.
export default defineConfig({
  test: {
    server: {
      deps: {
        inline: ['@material/material-color-utilities'],
      },
    },
  },
});
