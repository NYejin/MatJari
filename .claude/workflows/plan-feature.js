export const meta = {
  name: 'plan-feature',
  description: '읽기 전용 병렬 탐색 후 설계안 1건을 보고서로 만든다. 구현하지 않고 사용자 승인에서 끝낸다',
}

// 초안(검증 전). 사용법: "plan-feature를 feature='교환 수락 완료 처리' 로 실행" 처럼 요청하면 args가 구조화되어 넘어온다.
// args: { feature: string, areas?: string[] }
// 미검증 항목: agent() 옵션 중 model 이름(문서에 옵션명이 없다). /workflow-authoring 스킬로 확인한 뒤 수정한다.
const MODEL = { explore: 'haiku', design: 'opus' }

if (!args || !args.feature) {
  return { error: 'args.feature(설계할 기능 설명)가 필요합니다' }
}

const areas = args.areas ?? [
  'MatJari/backend 코드(엔티티·서비스·리포지토리·컨트롤러)',
  'MatJari/backend 테스트와 마이그레이션(db/migration)',
  'MatJari/frontend 코드(pages·components·api·types)',
  '산출물 문서와 CLAUDE.md의 확정 결정(08_ERD 설계 문서, 04 요구사항 문서 포함)',
  'wiki/ 페이지(결정 근거·함정)',
]

phase('탐색')
const findings = await pipeline(areas, area =>
  agent(
    '너는 읽기 전용 탐색 담당이다. 파일을 수정하거나 만들지 않는다.\n' +
      '기능: ' + args.feature + '\n' +
      '탐색 영역: ' + area + '\n' +
      '이 기능과 관련된 기존 코드·결정·제약·함정을 찾아 결론만 간결하게 보고한다. 파일 경로와 줄 번호를 붙이고, 확인하지 못한 것은 미확인으로 쓴다. CLAUDE.md의 확정 결정과 충돌하는 점이 있으면 반드시 적는다.',
    {
      label: '탐색: ' + area.slice(0, 24),
      model: MODEL.explore,
      schema: {
        type: 'object',
        required: ['area', 'summary', 'files', 'constraints'],
        properties: {
          area: { type: 'string' },
          summary: { type: 'string' },
          files: { type: 'array', items: { type: 'string' } },
          constraints: { type: 'array', items: { type: 'string' } },
        },
      },
    },
  ),
)

phase('설계')
const design = await agent(
  '너는 맞자리의 설계 담당이다. 아래 탐색 결과만 근거로 "' + args.feature + '"의 설계안 1건을 만든다. 구현하지 않는다.\n' +
    '규칙: ① 쓰기 병렬이 가능하도록 서로 겹치지 않는 파일 집합으로 나눈다 ② Flyway 마이그레이션은 한 파일 집합에만 두고 번호를 지금 배정한다(현재 최신 번호는 탐색 결과로 확인) ③ 확정 결정에 어긋나는 제안은 하지 않는다 ④ 사용자가 결정해야 할 질문은 추천안과 이유를 붙여 openQuestions에 모두 적는다(가정하지 않는다).\n\n' +
    '탐색 결과:\n' + JSON.stringify(findings.filter(Boolean), null, 2),
  {
    label: '설계안',
    model: MODEL.design,
    schema: {
      type: 'object',
      required: ['summary', 'fileSets', 'migration', 'tests', 'openQuestions', 'risks'],
      properties: {
        summary: { type: 'string' },
        fileSets: {
          type: 'array',
          items: {
            type: 'object',
            required: ['name', 'role', 'files', 'instructions'],
            properties: {
              name: { type: 'string' },
              role: { type: 'string' },
              files: { type: 'array', items: { type: 'string' } },
              instructions: { type: 'string' },
            },
          },
        },
        migration: {
          type: 'object',
          required: ['needed'],
          properties: {
            needed: { type: 'boolean' },
            number: { type: 'string' },
            ownerFileSet: { type: 'string' },
            description: { type: 'string' },
          },
        },
        tests: { type: 'array', items: { type: 'string' } },
        openQuestions: {
          type: 'array',
          items: {
            type: 'object',
            required: ['question', 'recommendation', 'reason'],
            properties: { question: { type: 'string' }, recommendation: { type: 'string' }, reason: { type: 'string' } },
          },
        },
        risks: { type: 'array', items: { type: 'string' } },
      },
    },
  },
)

// 여기서 끝낸다. 설계안을 사용자가 확인하고 openQuestions에 답한 뒤에만 build-feature를 실행한다.
return { feature: args.feature, design, explored: findings.filter(Boolean).length }
