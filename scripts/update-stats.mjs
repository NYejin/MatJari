#!/usr/bin/env node
// STATS.md 통계 생성기. 외부 의존성 없음. 실패해도 종료 코드 0.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const NA = '측정 불가';
const HEADER = '<!-- 자동 생성 파일: scripts/update-stats.mjs가 통째로 재생성합니다. 수동 편집 금지 -->';
const VOLATILE = ['- 마지막 갱신:', '- 기준 커밋:'];

const warn = (m) => console.error(`[update-stats] ${m}`);
const p = (...a) => path.join(ROOT, ...a);

function git(args) {
  try {
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 10000 }).trim();
  } catch {
    return null;
  }
}

function walk(dir, exts, out = []) {
  let entries;
  try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return null; }
  for (const e of entries) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) walk(f, exts, out);
    else if (exts.includes(path.extname(e.name).toLowerCase())) out.push(f);
  }
  return out;
}

function readText(f) {
  try { return readFileSync(f, 'utf8'); } catch { return ''; }
}

function countLines(text) {
  if (!text) return 0;
  const n = text.split(/\r\n|\r|\n/).length;
  return /(\r\n|\r|\n)$/.test(text) ? n - 1 : n;
}

// exts=null 이면 모든 파일
function stat(dir, exts) {
  const files = walk(dir, exts ?? [''], []);
  return files;
}
function allFiles(dir, out = []) {
  let entries;
  try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return null; }
  for (const e of entries) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) allFiles(f, out); else out.push(f);
  }
  return out;
}
function summarize(files) {
  if (!files) return null;
  let lines = 0;
  for (const f of files) lines += countLines(readText(f));
  return { files: files.length, lines };
}
const fmt = (s) => (s ? `${s.files}개 / ${s.lines.toLocaleString('en-US')}줄` : NA);

function section(title, rows, head = ['항목', '값']) {
  return [`## ${title}`, '', `| ${head.join(' | ')} |`, `|${head.map(() => '---').join('|')}|`,
    ...rows.map((r) => `| ${r.join(' | ')} |`), ''];
}

const lines = [HEADER, '', '# 프로젝트 통계', ''];

try {
  // 코드 규모
  const be = (sub) => summarize(allFiles(p('MatJari', 'backend', 'src', sub)));
  const beJava = (sub) => {
    const fs = allFiles(p('MatJari', 'backend', 'src', sub));
    return summarize(fs && fs.filter((f) => f.endsWith('.java')));
  };
  const fe = (exts) => {
    const fs = allFiles(p('MatJari', 'frontend', 'src'));
    return summarize(fs && fs.filter((f) => exts.includes(path.extname(f).toLowerCase())));
  };
  lines.push(...section('코드 규모', [
    ['백엔드 main 전체', fmt(be('main'))],
    ['백엔드 main java', fmt(beJava('main'))],
    ['백엔드 test 전체', fmt(be('test'))],
    ['백엔드 test java', fmt(beJava('test'))],
    ['프론트 src ts', fmt(fe(['.ts']))],
    ['프론트 src tsx', fmt(fe(['.tsx']))],
    ['프론트 src css', fmt(fe(['.css']))],
    ['프론트 src ts+tsx+css 합계', fmt(fe(['.ts', '.tsx', '.css']))],
  ]));

  // 엔드포인트
  const javaMain = allFiles(p('MatJari', 'backend', 'src', 'main'));
  let epRow = NA;
  if (javaMain) {
    const counts = { Get: 0, Post: 0, Put: 0, Patch: 0, Delete: 0, ReqMethod: 0 };
    for (const f of javaMain.filter((x) => x.endsWith('.java'))) {
      const t = readText(f);
      if (!/@(Rest)?Controller\b/.test(t)) continue;
      const nc = t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
      for (const m of nc.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping\b/g)) counts[m[1]]++;
      for (const m of nc.matchAll(/@RequestMapping\b/g)) {
        const rest = nc.slice(m.index, m.index + 600);
        const upto = rest.search(/\{/);
        const head = upto >= 0 ? rest.slice(0, upto) : rest;
        if (!/\b(class|interface|record)\b/.test(head)) counts.ReqMethod++; // 메서드 레벨만
      }
    }
    const total = counts.Get + counts.Post + counts.Put + counts.Patch + counts.Delete + counts.ReqMethod;
    epRow = `${total}개 (GET ${counts.Get}, POST ${counts.Post}, PUT ${counts.Put}, PATCH ${counts.Patch}, DELETE ${counts.Delete}, 메서드 레벨 RequestMapping ${counts.ReqMethod})`;
  }
  lines.push(...section('API', [['엔드포인트 수 (컨트롤러 메서드 매핑)', epRow]]));

  // Flyway
  let flyRows = [['V*.sql 개수', NA], ['최신 번호', NA]];
  try {
    const names = readdirSync(p('MatJari', 'backend', 'src', 'main', 'resources', 'db', 'migration')).filter((n) => /^V\d+__.*\.sql$/.test(n));
    const nums = names.map((n) => parseInt(n.match(/^V(\d+)__/)[1], 10));
    flyRows = [['V*.sql 개수', `${names.length}개`], ['최신 번호', nums.length ? `V${Math.max(...nums)}` : '없음']];
  } catch { /* 측정 불가 유지 */ }
  lines.push(...section('Flyway 마이그레이션', flyRows));

  // 테스트
  let testRows = [['테스트 수', '미측정']];
  try {
    const dir = p('MatJari', 'backend', 'build', 'test-results', 'test');
    const xmls = readdirSync(dir).filter((n) => n.endsWith('.xml'));
    if (xmls.length) {
      const sum = { tests: 0, skipped: 0, failures: 0, errors: 0 };
      let latest = 0;
      for (const n of xmls) {
        const f = path.join(dir, n);
        latest = Math.max(latest, statSync(f).mtimeMs);
        const head = readText(f).slice(0, 2000);
        const tag = head.match(/<testsuite\b[^>]*>/);
        if (!tag) continue;
        for (const k of Object.keys(sum)) {
          const m = tag[0].match(new RegExp(`\\b${k}="(\\d+)"`));
          if (m) sum[k] += parseInt(m[1], 10);
        }
      }
      testRows = [
        ['결과 파일 수', `${xmls.length}개`],
        ['tests', sum.tests], ['skipped', sum.skipped], ['failures', sum.failures], ['errors', sum.errors],
        ['측정 시각 (결과 파일 최신 수정 시각, KST)', kst(new Date(latest))],
      ];
    }
  } catch { /* 미측정 유지 */ }
  lines.push(...section('테스트 (Gradle 결과 파일 기준, 오래된 값일 수 있음)', testRows));

  // git
  const commits = git(['rev-list', '--count', 'HEAD']);
  const merges = git(['log', '--merges', '--grep', '^Merge pull request', '--oneline']);
  const remote = git(['branch', '-r']);
  const local = git(['branch', '--format=%(refname:short)']);
  const cnt = (s, filter = () => true) => (s === null ? NA : `${s.split(/\r?\n/).map((x) => x.trim()).filter((x) => x && filter(x)).length}개`);
  lines.push(...section('Git', [
    ['전체 커밋 수', commits === null ? NA : `${commits}개`],
    ['병합 PR 수', cnt(merges)],
    ['원격 브랜치 수 (HEAD 제외)', cnt(remote, (x) => !x.includes('->') && !/\/HEAD$/.test(x))],
    ['로컬 브랜치 수', cnt(local)],
  ]));

  // README 체크리스트
  let rd = [['체크리스트', NA]];
  if (existsSync(p('README.md'))) {
    const t = readText(p('README.md'));
    const done = (t.match(/^\s*[-*] \[[xX]\]/gm) || []).length;
    const open = (t.match(/^\s*[-*] \[ \]/gm) || []).length;
    const total = done + open;
    rd = [['완료 `- [x]`', `${done}개`], ['미완료 `- [ ]`', `${open}개`], ['전체', `${total}개`],
      ['완료율', total ? `${((done / total) * 100).toFixed(1)}%` : NA]];
  }
  lines.push(...section('README 체크리스트', rd));

  // 갱신 정보 (VOLATILE 접두사로 시작해야 본문 비교에서 제외됨)
  const sha = git(['rev-parse', '--short', 'HEAD']);
  const br = git(['rev-parse', '--abbrev-ref', 'HEAD']);
  lines.push('## 갱신 정보', '',
    `- 마지막 갱신: ${kst(new Date())} (KST)`,
    `- 기준 커밋: ${sha ?? NA} (${br ?? NA})`, '');
} catch (e) {
  warn(`통계 수집 중 오류: ${e?.message ?? e}`);
  process.exit(0);
}

function kst(d) {
  const k = new Date(d.getTime() + 9 * 3600 * 1000);
  const z = (n) => String(n).padStart(2, '0');
  return `${k.getUTCFullYear()}-${z(k.getUTCMonth() + 1)}-${z(k.getUTCDate())} ${z(k.getUTCHours())}:${z(k.getUTCMinutes())}`;
}

try {
  const out = lines.join('\n');
  const target = p('STATS.md');
  const body = (s) => s.replace(/\r\n/g, '\n').split('\n').filter((l) => !VOLATILE.some((v) => l.startsWith(v))).join('\n');
  if (existsSync(target) && body(readText(target)) === body(out)) process.exit(0);
  writeFileSync(target, out, 'utf8');
} catch (e) {
  warn(`STATS.md 쓰기 실패: ${e?.message ?? e}`);
}
process.exit(0);
