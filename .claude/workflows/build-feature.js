export const meta = {
  name: 'build-feature',
  description: '사용자가 승인한 설계안을 파일 집합별로 구현하고 테스트한 뒤 통합 요약을 보고한다. 커밋·푸시·병합은 하지 않는다',
}

// 초안(검증 전). plan-feature의 design 결과를 사용자가 확인·수정한 뒤 args로 넘긴다.
// args: {
//   approved: true,                 // 사용자 승인 표시. true가 아니면 실행하지 않는다
//   runId: 'ex-complete-01',        // 실행마다 고유한 짧은 식별자(영문 소문자·숫자·하이픈). Date.now/Math.random은 스크립트에서 쓸 수 없어 args로 받는다
//   plan: { summary, fileSets: [{ name, role: 'backend'|'frontend'|'migration', files: [...], instructions }], migration: { needed, number, ownerFileSet }, tests: [...] },
//   parallelWrites?: false          // true는 에이전트 격리(worktree)가 실제로 적용되는 것을 확인한 뒤에만 사용한다
// }
// 미검증 항목: ① agent() 옵션 중 model 이름 ② worktree 격리 옵션(문서에 없음) — 확인 전에는 쓰기를 순차 실행한다.
const MODEL = { build: 'sonnet', test: 'sonnet' }

if (!args || args.approved !== true || !args.plan || !args.runId) {
  return { error: 'args.approved=true, args.runId, args.plan이 모두 필요합니다. plan-feature 결과를 사용자가 승인한 뒤에 실행하세요' }
}
if (!/^[a-z0-9-]{3,30}$/.test(args.runId)) {
  return { error: 'args.runId는 영문 소문자·숫자·하이픈 3~30자여야 합니다' }
}

const plan = args.plan
const migrationOwner = plan.migration && plan.migration.needed ? plan.migration.ownerFileSet : null

const rules =
  '공통 규칙: 지정된 파일 집합 밖의 파일은 수정하지 않는다. 커밋·푸시·브랜치 변경을 하지 않는다. CLAUDE.md, HISTORY.md, STATS.md는 수정하지 않는다. ' +
  '적용된 Flyway 마이그레이션은 수정하지 않는다. 로컬 개발 DB(3306)와 backend/.env를 건드리지 않고 ./gradlew bootRun을 쓰지 않는다. ' +
  '새 파일은 5개 이내. 확정 결정(CLAUDE.md)과 충돌하면 구현하지 말고 보고한다.'

function buildPrompt(set) {
  const mig =
    set.name === migrationOwner
      ? '이 집합만 Flyway 마이그레이션을 쓴다. 번호는 ' + (plan.migration.number || '(지정 없음: 중단하고 보고)') + '.'
      : '이 집합은 마이그레이션 파일을 만들지 않는다.'
  return (
    '너는 맞자리 ' + set.role + ' 구현 담당이다.\n' + rules + '\n' + mig + '\n' +
    '전체 계획 요약: ' + plan.summary + '\n' +
    '이 집합: ' + set.name + '\n허용 파일: ' + set.files.join(', ') + '\n지시:\n' + set.instructions + '\n' +
    '끝나면 변경한 파일 목록, 구현 요약, 확인하지 못한 것, 계획과 달라진 점을 보고한다.'
  )
}

const schema = {
  type: 'object',
  required: ['set', 'changedFiles', 'summary', 'deviations'],
  properties: {
    set: { type: 'string' },
    changedFiles: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
    deviations: { type: 'array', items: { type: 'string' } },
  },
}

phase('구현')
let results = []
if (args.parallelWrites === true) {
  // 격리가 확인된 경우에만. 서로 다른 파일 집합이므로 동시에 실행한다.
  results = await parallel(
    plan.fileSets.map(set => agent(buildPrompt(set), { label: '구현: ' + set.name, model: MODEL.build, schema })),
  )
} else {
  // 기본: 격리를 확인하기 전이므로 순차 실행(같은 작업 트리에서 충돌 방지).
  for (const set of plan.fileSets) {
    results.push(await agent(buildPrompt(set), { label: '구현: ' + set.name, model: MODEL.build, schema }))
  }
}

phase('테스트')
// MySQL 통합 테스트는 실행마다 고유한 DB 이름·컨테이너·포트를 쓴다(이름은 반드시 _it로 끝나야 안전장치를 통과한다).
const dbName = 'matjari_' + args.runId.replace(/-/g, '_') + '_it'
const tests = await agent(
  '너는 맞자리 테스트 담당이다. 코드를 고치지 않는다(실패하면 원인만 보고).\n' +
    '실행: ① 백엔드 ./gradlew cleanTest test (MatJari/backend) ② 프론트 npx tsc --noEmit 과 npm run build (MatJari/frontend) ③ 계획의 추가 테스트: ' + JSON.stringify(plan.tests || []) + '\n' +
    '실제 MySQL 통합 테스트가 필요하면 임시 컨테이너(이름 matjari-tmp-' + args.runId + ', 빈 포트 사용)를 띄우고 MATJARI_IT_REQUIRED=true MATJARI_IT_JDBC_URL=jdbc:mysql://localhost:<포트>/' + dbName + ' MATJARI_IT_USER=root MATJARI_IT_PASSWORD=<임시 비밀번호> 로 돌린 뒤 컨테이너를 삭제한다. 다른 실행과 같은 DB 이름·포트를 쓰지 않는다.\n' +
    '보고: 명령별 통과/실패와 건수, 실패 테스트 이름과 원인, 정리한 임시 컨테이너.',
  {
    label: '테스트',
    model: MODEL.test,
    schema: {
      type: 'object',
      required: ['passed', 'details'],
      properties: {
        passed: { type: 'boolean' },
        details: { type: 'array', items: { type: 'string' } },
        failures: { type: 'array', items: { type: 'string' } },
      },
    },
  },
)

phase('통합 요약')
const summary = await agent(
  '아래 구현·테스트 결과와 현재 git 상태(git status --short, git diff --stat)를 읽고 통합 요약을 만든다. 파일을 수정하지 않는다. 계획과 달라진 점, 위험, 사용자 확인이 필요한 항목, 병합 전에 해야 할 일(리뷰, 문서 동기화)을 정리한다.\n\n' +
    '구현 결과:\n' + JSON.stringify(results.filter(Boolean), null, 2) + '\n\n테스트 결과:\n' + JSON.stringify(tests, null, 2),
  { label: '통합 요약', model: MODEL.test },
)

// 여기서 끝낸다. 리뷰(verify-feature)와 사용자 확인 후에만 커밋·병합한다.
return { runId: args.runId, parallelWrites: args.parallelWrites === true, results: results.filter(Boolean), tests, summary }
