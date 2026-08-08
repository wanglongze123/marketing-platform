#!/usr/bin/env node
/**
 * 校验 src/contracts/enums.ts 与 mp-common 的 Java 枚举逐值一致。
 *
 * 为什么要有这个脚本：前端把状态判断建立在字符串比对上，写错一个 GRANT_SUCCESS
 * 不会报错，只会显示错的状态。后端加一个枚举值时，前端静默漏渲染。靠人核对必然漏
 * —— 这与后端 ShapeFreezeTest 的理由相同：约束每次改动都要重新成立。
 *
 * 顺序也要一致：取值顺序在两侧都表达状态机推进方向，不同即说明有一侧改过。
 *
 * 用法：node scripts/check-enums.mjs   （退出码 0 = 一致）
 */
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '../..')
const JAVA_ENUM_DIR = join(REPO, 'mp-common/src/main/java/com/mp/common/enums')
const TS_FILE = join(REPO, 'web/src/contracts/enums.ts')

/** Java 枚举名 -> TS 常量名 */
const MAPPING = {
  PayStatus: 'PAY_STATUS',
  GrantStatus: 'GRANT_STATUS',
  ItemGrantStatus: 'ITEM_GRANT_STATUS',
  RefundStatus: 'REFUND_STATUS',
  RetStatus: 'RET_STATUS',
  OpStatus: 'OP_STATUS',
  OpType: 'OP_TYPE',
  ActivityStatus: 'ACTIVITY_STATUS',
  TaskStatus: 'TASK_STATUS',
  TaskType: 'TASK_TYPE',
  RelationStatus: 'RELATION_STATUS',
  StockStatus: 'STOCK_STATUS',
  QuotaStatus: 'QUOTA_STATUS',
  QualifyReason: 'QUALIFY_REASON',
}

/** 从 Java 源码抽枚举常量。去注释后取 enum 体内的大写标识符 */
function parseJavaEnum(src, typeName) {
  const withoutComments = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*/g, '')
  const start = withoutComments.indexOf(`enum ${typeName}`)
  if (start < 0) return null
  const open = withoutComments.indexOf('{', start)
  if (open < 0) return null

  // 取到与 open 配对的 }，避免枚举体内嵌套结构截断
  let depth = 0
  let end = -1
  for (let i = open; i < withoutComments.length; i++) {
    const c = withoutComments[i]
    if (c === '{') depth++
    else if (c === '}') {
      depth--
      if (depth === 0) {
        end = i
        break
      }
    }
  }
  if (end < 0) return null

  const body = withoutComments.slice(open + 1, end)
  // 常量区止于第一个 ';'（其后是方法/字段），无 ';' 则整体都是常量
  const constPart = body.includes(';') ? body.slice(0, body.indexOf(';')) : body
  return constPart
    .split(',')
    .map((s) => s.trim())
    // 带构造参数的形式 FOO(1) 取标识符部分
    .map((s) => (s.match(/^([A-Z][A-Z0-9_]*)\s*(\(|$)/) ?? [])[1])
    .filter(Boolean)
}

/** 从 TS 源码抽 `export const NAME = [...] as const` 的字符串项 */
function parseTsConst(src, constName) {
  const re = new RegExp(`export const ${constName}\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*as const`)
  const m = src.match(re)
  if (!m) return null
  return [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1])
}

const tsSrc = readFileSync(TS_FILE, 'utf8')
const javaFiles = readdirSync(JAVA_ENUM_DIR).filter((f) => f.endsWith('.java'))

let failed = 0
const pass = (msg) => console.log(`  ✓ ${msg}`)
const fail = (msg) => {
  console.log(`  ✗ ${msg}`)
  failed++
}

console.log('='.repeat(62))
console.log('枚举镜像一致性：web/src/contracts/enums.ts vs mp-common')
console.log('='.repeat(62))

for (const [javaName, tsName] of Object.entries(MAPPING)) {
  const file = javaFiles.find((f) => f === `${javaName}.java`)
  if (!file) {
    fail(`Java 枚举不存在: ${javaName}.java —— 是否已被重命名或删除？`)
    continue
  }
  const javaValues = parseJavaEnum(readFileSync(join(JAVA_ENUM_DIR, file), 'utf8'), javaName)
  const tsValues = parseTsConst(tsSrc, tsName)

  if (!javaValues) {
    fail(`解析 Java 枚举失败: ${javaName}`)
    continue
  }
  if (!tsValues) {
    fail(`TS 缺少常量: ${tsName}（对应 ${javaName}）`)
    continue
  }
  const same =
    javaValues.length === tsValues.length &&
    javaValues.every((v, i) => v === tsValues[i])
  if (same) {
    pass(`${javaName} ↔ ${tsName}（${javaValues.length} 项，顺序一致）`)
  } else {
    fail(
      `${javaName} ↔ ${tsName} 不一致\n` +
        `      Java: ${JSON.stringify(javaValues)}\n` +
        `      TS  : ${JSON.stringify(tsValues)}`
    )
  }
}

// ErrorCode 是 final class + String 常量，不是 enum，映射在 errorCode.ts 里单独校验
const NOT_ENUM = new Set(['ErrorCode.java'])

// 未纳入 MAPPING 的 Java 枚举：可能是新加的，前端漏了镜像
const known = new Set(Object.keys(MAPPING).map((n) => `${n}.java`))
const unmapped = javaFiles.filter((f) => !known.has(f) && !NOT_ENUM.has(f))
if (unmapped.length === 0) {
  pass('mp-common 中无未镜像的枚举')
} else {
  fail(`存在未镜像的 Java 枚举: ${unmapped.join(', ')} —— 新加枚举须同步到 enums.ts 与 MAPPING`)
}

// 关键不变量：主单终态与明细终态不得重叠（对应后端 ShapeFreezeTest 的同名断言）
const grant = parseTsConst(tsSrc, 'GRANT_STATUS') ?? []
const item = parseTsConst(tsSrc, 'ITEM_GRANT_STATUS') ?? []
const overlapTerminal = ['SUCCESS', 'FAILED', 'UNKNOWN'].filter((v) => grant.includes(v))
if (overlapTerminal.length === 0) {
  pass('GrantStatus 不含明细终态（SUCCESS/FAILED/UNKNOWN）')
} else {
  fail(`GrantStatus 混入了明细终态: ${overlapTerminal.join(', ')}`)
}
const prefixedInItem = item.filter((v) => v.startsWith('GRANT_'))
if (prefixedInItem.length === 0) {
  pass('ItemGrantStatus 不含 GRANT_ 前缀取值')
} else {
  fail(`ItemGrantStatus 混入了主单终态: ${prefixedInItem.join(', ')}`)
}

// RetStatus.FAIL 与 OpStatus.FAILED 的拼写差异必须保留
const ret = parseTsConst(tsSrc, 'RET_STATUS') ?? []
const op = parseTsConst(tsSrc, 'OP_STATUS') ?? []
if (ret.includes('FAIL') && !ret.includes('FAILED') && op.includes('FAILED') && !op.includes('FAIL')) {
  pass('RetStatus=FAIL / OpStatus=FAILED 拼写差异保留')
} else {
  fail('RetStatus 与 OpStatus 的失败值拼写被归一 —— 两者语义不同，不得对齐')
}

// ---- ErrorCode：Java 是 final class + String 常量，单独比对 ----
const javaErrSrc = readFileSync(join(JAVA_ENUM_DIR, 'ErrorCode.java'), 'utf8')
const javaCodes = new Set(
  [...javaErrSrc.matchAll(/String\s+[A-Z_]+\s*=\s*"(\d+)"/g)].map((m) => Number(m[1]))
)
const tsErrSrc = readFileSync(join(REPO, 'web/src/contracts/errorCode.ts'), 'utf8')
const tsErrBlock = tsErrSrc.match(/export const ERROR_CODE = \{([\s\S]*?)\} as const/)
const tsCodes = new Set(
  tsErrBlock ? [...tsErrBlock[1].matchAll(/:\s*(\d+)\s*,/g)].map((m) => Number(m[1])) : []
)
tsCodes.delete(0) // SUCCESS=0 是响应壳约定，Java 侧不在 ErrorCode 里

const missingInTs = [...javaCodes].filter((c) => !tsCodes.has(c))
const extraInTs = [...tsCodes].filter((c) => !javaCodes.has(c))
if (missingInTs.length === 0 && extraInTs.length === 0) {
  pass(`ErrorCode ↔ ERROR_CODE（${javaCodes.size} 个码）`)
} else {
  fail(
    `ErrorCode 不一致` +
      (missingInTs.length ? `  TS 缺: ${missingInTs.join(', ')}` : '') +
      (extraInTs.length ? `  TS 多（本项目不新造码）: ${extraInTs.join(', ')}` : '')
  )
}

// 每个码都要有面向用户与面向开发的两份文案，漏了会在页面上渲染出裸数字
for (const [name, re] of [
  ['ERROR_CODE_TEXT', /export const ERROR_CODE_TEXT[\s\S]*?\n\}/],
  ['ERROR_CODE_DEV_TEXT', /export const ERROR_CODE_DEV_TEXT[\s\S]*?\n\}/],
]) {
  const block = tsErrSrc.match(re)
  const have = new Set(
    block ? [...block[0].matchAll(/^\s*(\d+):/gm)].map((m) => Number(m[1])) : []
  )
  const miss = [...javaCodes].filter((c) => !have.has(c))
  if (miss.length === 0) pass(`${name} 覆盖全部错误码`)
  else fail(`${name} 缺少文案: ${miss.join(', ')}`)
}

console.log()
console.log('='.repeat(62))
if (failed === 0) {
  console.log(`✓ 全部通过`)
  process.exit(0)
}
console.log(`✗ ${failed} 项不一致`)
process.exit(1)
