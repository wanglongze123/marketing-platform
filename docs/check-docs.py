#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文档一致性校验：把「机器能核对的东西」自动查一遍。

用法：python3 check-docs.py
退出码：0 = 全通过，1 = 有不一致

两类校验：
  A 文档之间：Flyway 编号与撞号、四分类枚举、幂等键公式、退避序列、
    退出标准编号连续性、依赖方向规则、重复段落。
  B 文档与代码：module 清单、Flyway 脚本名、依赖版本号、HTTP 端点。

B 类以仓库实际状态为基准。文档之间互相比对存在盲区 —— 两份同时写错时
「一致」但都不对（mp-api-mock 曾在两份文档中同时缺失，V1 才发现）。

查不了的：语义矛盾（同一件事两处给了不同做法）。那个只能靠人读。
"""
import os
import re
import sys
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS_DIR = os.path.join(ROOT, 'docs')

DOCS = {
    '技术方案': '营销活动平台-技术方案.md',
    '开发规范': '营销活动平台-开发规范.md',
    '环境与依赖': '营销活动平台-环境与依赖.md',
    '分阶段方案': '营销活动平台-分阶段方案.md',
}

PASSED = []
FAILED = []


def check(cond, msg):
    print(('  ✓ ' if cond else '  ✗ ') + msg)
    (PASSED if cond else FAILED).append(msg)


def section(title):
    print()
    print('=' * 66)
    print(title)
    print('=' * 66)


def read(path):
    """读仓库文件，不存在时返回空串 —— 校验项自行判断缺失是否算失败。"""
    try:
        return open(os.path.join(ROOT, path), encoding='utf-8').read()
    except OSError:
        return ''


def check_against_repo(txt):
    """B 类：文档 vs 仓库实际状态。"""
    section('文档 vs 仓库')

    # module 清单：以根 pom 的 <module> 与 mp-api 子 pom 为准
    declared = set(re.findall(r'<module>([\w-]+)</module>', read('pom.xml')))
    declared |= set(re.findall(r'<module>([\w-]+)</module>', read('mp-api/pom.xml')))
    for name, t in (('开发规范', txt['开发规范']), ('分阶段方案', txt['分阶段方案'])):
        doc = set(re.findall(r'(mp-[a-z-]+)/', t))
        miss, extra = declared - doc, doc - declared
        check(not miss and not extra,
              f'{name} module 清单与 pom 一致（{len(declared)} 个）'
              + (f'  文档漏: {sorted(miss)}' if miss else '')
              + (f'  文档多: {sorted(extra)}' if extra else ''))

    # Flyway 脚本：文档引用的文件名必须真实存在
    on_disk = set()
    for dirpath, _, files in os.walk(ROOT):
        if 'target' in dirpath.split(os.sep):
            continue
        on_disk |= {f for f in files if re.match(r'V\d{4}__.*\.sql$', f)}
    quoted = set(re.findall(r'`(V\d{4}__\w+\.sql)`', ''.join(txt.values())))
    ghost = quoted - on_disk
    check(not ghost, f'文档引用的迁移脚本均存在（{len(quoted)} 个）'
          + (f'  查无此文件: {sorted(ghost)}' if ghost else ''))

    # 依赖版本：《环境与依赖》§2.1 的版本号必须与根 pom 的 properties 一致
    props = dict(re.findall(r'<([\w.-]+)\.version>([\d.]+)</[\w.-]+\.version>', read('pom.xml')))
    env = txt['环境与依赖']
    for key, label in [('spring-boot', 'Spring Boot'), ('dubbo', 'Dubbo'),
                       ('mybatis-plus', 'MyBatis-Plus'), ('flyway', 'Flyway'),
                       ('testcontainers', 'Testcontainers')]:
        want = props.get(key)
        if not want:
            check(False, f'根 pom 未声明 {key}.version')
            continue
        check(want in env, f'{label} 版本与 pom 一致（{want}）')

    # HTTP 端点：文档表格中的路径必须在 controller 中存在。
    # 逐个 controller 解析各自的 @RequestMapping 前缀 —— 拼接后只取第一个前缀会让
    # 后续 controller 的路径全部拼错，表现为「已实现的端点被报成未实现」
    ctl_dir = 'mp-gateway/src/main/java/com/mp/gateway/controller'
    paths = set()
    for f in os.listdir(os.path.join(ROOT, ctl_dir)):
        src = read(os.path.join(ctl_dir, f))
        base = re.search(r'@RequestMapping\("([^"]+)"\)', src)
        prefix = base.group(1) if base else ''
        paths |= {prefix + p
                  for p in re.findall(r'@(?:Get|Post|Put|Delete)Mapping\("([^"]*)"\)', src)}
    quoted_paths = set(re.findall(r'`(/api/[\w/{}-]+)`', txt['分阶段方案']))
    ghost_paths = {p for p in quoted_paths if p not in paths}
    check(not ghost_paths, f'文档列出的 HTTP 端点均已实现（{len(paths)} 个）'
          + (f'  未实现: {sorted(ghost_paths)}' if ghost_paths else ''))

    # V0 脚手架：smoke 端点删除后，文档不得再让人 curl 它
    readme = read('README.md')
    check('/smoke' not in readme, 'README 未引用已删除的 smoke 端点')


def main():
    try:
        txt = {k: open(os.path.join(DOCS_DIR, v), encoding='utf-8').read()
               for k, v in DOCS.items()}
    except FileNotFoundError as e:
        print(f'找不到文档：{e.filename}')
        return 1
    allt = ''.join(txt.values())

    print('=' * 66)
    print('Flyway')
    print('=' * 66)
    names = sorted(set(re.findall(r'`(V\d{4}__\w+\.sql)`', allt)))
    dup = [v for v, c in collections.Counter(n[:5] for n in names).items() if c > 1]
    check(not dup, f'版本号无重复（{len(names)} 个脚本）' + (f'  撞号: {dup}' if dup else ''))
    bad = [n for n in names if not re.match(r'V[0-3][0-3]\d{2}__', n)]
    check(not bad, '脚本名符合 V<阶段><库><序号>' + (f'  违规: {bad}' if bad else ''))
    seeds = [n for n in names if 'seed' in n]
    check(all(re.match(r'V\d\d9\d__', s) for s in seeds), f'seed 落在 9x 段: {seeds}')

    print()
    print('=' * 66)
    print('枚举')
    print('=' * 66)
    r = set(re.findall(r'SUCCESS\s*/\s*(FAIL|FAILED)\s*/\s*PROCESSING', allt))
    check(r == {'FAIL'}, f'四分类失败值统一为 FAIL  实测={r}')
    check('ItemGrantStatus' in txt['开发规范'], '五个结果类枚举已在开发规范列出')
    check('RetStatus' in txt['分阶段方案'], '分阶段方案使用 RetStatus 命名')

    print()
    print('=' * 66)
    print('幂等键公式')
    print('=' * 66)
    for name, pat in [
        ('grantOpNo', r"grantOpNo\s*=\s*([^\n|，,。`]*_G_[^\n|，,。`]*)"),
        ('refundNo', r"refundNo\s*=\s*([^\n|，,。`]*_R_[^\n|，,。`]*)"),
        ('revokeNo', r"revokeNo\s*=\s*([^\n|，,。`]*_V_[^\n|，,。`]*)"),
        ('followerGrantNo', r"followerGrantNo\s*=\s*([^\n|，,。`]*_FL'?)"),
        ('sponsorFlowNo', r"sponsorFlowNo\s*=\s*([^\n|，,。`]*_SP'?)"),
    ]:
        v = set(x.strip() for x in re.findall(pat, allt))
        check(len(v) == 1, f'{name} 公式唯一: {v}')

    # 支付回调键：排除「状态进幂等键」那行反例
    forms = set()
    for t in txt.values():
        for line in t.split('\n'):
            if '状态进幂等键' in line:
                continue
            forms |= set(re.sub(r'\s+', '', x)
                         for x in re.findall(r"tradeNo\s*\+\s*(?:'_'\s*\+\s*)?notifySeq", line))
    check(forms == {"tradeNo+'_'+notifySeq"}, f'支付回调键带分隔符: {forms}')

    print()
    print('=' * 66)
    print('其他约定')
    print('=' * 66)
    for kind, exp in [('短退避', '1s→5s→30s'), ('长退避', '30s→2m→10m')]:
        v = set(re.sub(r'\s+', '', x) for x in re.findall(
            kind + r'[^\n|。]{0,8}?([\dsm]+\s*→\s*[\dsm]+\s*→\s*[\dsm]+)', allt))
        check(v == {exp}, f'{kind} 统一: {v}')

    check('跨层向下' in txt['开发规范'], '依赖方向已写明「跨层向下允许」')
    check(txt['开发规范'].count('已合入 `main` 的脚本不得修改') == 1, 'Flyway 历史脚本规则未重复')

    # §7.3 的条目数写在标题里，与表格行数容易脱节
    m = re.search(r'### 7\.3 V1 明知不对但先这么做的(\S+?)处(.*?)####', txt['分阶段方案'], re.S)
    if m:
        cn = {'七': 7, '八': 8, '九': 9, '十': 10, '十一': 11, '十二': 12}
        rows = len(re.findall(r'^\| \d+ \|', m.group(2), re.M))
        check(cn.get(m.group(1)) == rows,
              f'§7.3 标题条目数与表格行数一致（{rows} 行）'
              + ('' if cn.get(m.group(1)) == rows else f'  标题写「{m.group(1)}」'))
    else:
        check(False, '§7.3 章节未找到')

    # review 闸已于 V1 取消（《分阶段方案》§4.8）。若某处又写回「review 通过方可合入」，
    # 与《开发规范》§2 的「合入前提：CI 全绿」直接矛盾 —— 两条并存时不知道该信哪条。
    gate = [k for k, t in txt.items() if re.search(r'review\s*通过.{0,6}方?可?合入', t)]
    check(not gate, 'review 未被重新写成合入闸'
          + (f'  出现于: {gate}' if gate else ''))

    print()
    print('=' * 66)
    print('退出标准编号连续性')
    print('=' * 66)
    for sec, pat in [('V0', r'### 3\.6 V0 退出标准(.*?)\n---'),
                     ('V1', r'### 4\.7 V1 退出标准(.*?)### 4\.8')]:
        m = re.search(pat, txt['分阶段方案'], re.S)
        if not m:
            check(False, f'{sec} 退出标准章节未找到')
            continue
        nums = [int(x) for x in re.findall(r'^(\d+)\.', m.group(1), re.M)]
        check(nums == list(range(1, len(nums) + 1)),
              f'{sec} 编号连续 (1-{len(nums)})' + ('' if nums == list(range(1, len(nums) + 1)) else f'  实测={nums}'))

    check_against_repo(txt)

    total = len(PASSED) + len(FAILED)
    print()
    print('=' * 66)
    if FAILED:
        print(f'✗ {len(FAILED)}/{total} 项不一致：')
        for f in FAILED:
            print(f'    - {f}')
        print('=' * 66)
        return 1
    print(f'✓ 全部通过（{total} 项）')
    print('=' * 66)
    return 0


if __name__ == '__main__':
    sys.exit(main())
