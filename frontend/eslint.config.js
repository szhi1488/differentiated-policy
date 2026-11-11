import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  { ignores: ['dist/**', 'node_modules/**', '.research/**', '.local-cache/**'] },
  js.configs.recommended,
  ...vue.configs['flat/essential'],
  { files: ['src/**/*.{js,vue}'], languageOptions: { globals: globals.browser } },
  { files: ['tests/**/*.js'], languageOptions: { globals: globals.browser } },
  {
    files: ['*.config.js', 'scripts/**/*.js'],
    languageOptions: { globals: globals.nodeBuiltin },
  },
  {
    files: ['**/*.{js,vue}'],
    rules: {
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }],
      eqeqeq: ['error', 'always'],
      curly: ['error', 'all'],
      'vue/no-mutating-props': 'error',
    },
  },
  {
    files: ['src/**/*.{test,spec}.js'],
    rules: {
      'no-restricted-syntax': [
        'error',
        { selector: 'Program', message: '请将测试放在 tests/ 下。' },
      ],
    },
  },
  {
    files: ['src/{api,composables,constants,utils}/**/*.js'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/views/**', '**/layouts/**'],
              message: '公共逻辑不得依赖页面或工作台布局。',
            },
          ],
        },
      ],
    },
  },
]
