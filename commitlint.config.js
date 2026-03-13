module.exports = {
    extends: ['@commitlint/config-conventional'],
    rules: {
        'type-enum': [2, 'always', [
            'feat',      // 새 기능
            'fix',       // 버그 수정
            'refactor',  // 리팩토링
            'test',      // 테스트
            'docs',      // 문서
            'chore',     // 설정 변경
            'style',     // 코드 스타일
        ]],
        'subject-max-length': [2, 'always', 72],
        'subject-case': [0],  // ← 추가 (0 = 비활성화)
    },
};