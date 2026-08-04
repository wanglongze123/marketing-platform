#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文档一致性校验：把「机器能核对的东西」自动查一遍。

用法：python3 check-docs.py
退出码：0 = 全通过，1 = 有不一致

覆盖 17 项：Flyway 编号与撞号、四分类枚举、幂等键公式、退避序列、
module 清单、退出标准编号连续性、依赖方向规则、重复段落。

查不了的：语义矛盾（同一件事两处给了不同做法）。那个只能靠人读。
"""
import re
import sys
import collections

DOCS = {
    '技术方案': '营销活动平台-技术方案.md',
    '开发规范': '营销活动平台-开发规范.md',
    '环境与依赖': '营销活动平台-环境与依赖.md',
    '分阶段方案': '营销活动平台-分阶段方案.md',
}

FAILED = []


def check(cond, msg):
    print(('  ✓ ' if cond else '  ✗ ') + msg)
    if not cond:
        FAILED.append(msg)


def main():
    try:
        txt = {k: open(v, encoding='utf-8').read() for k, v in DOCS.items()}
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

    m1 = set(re.findall(r'(mp-[a-z-]+)/', txt['开发规范']))
    m2 = set(re.findall(r'(mp-[a-z-]+)/', txt['分阶段方案']))
    check(m1 == m2, f'module 清单两份一致（{len(m1)} 个）'
          + ('' if m1 == m2 else f'  差异: {m1 ^ m2}'))

    check('跨层向下' in txt['开发规范'], '依赖方向已写明「跨层向下允许」')
    check(txt['开发规范'].count('已合入 `main` 的脚本不得修改') == 1, 'Flyway 历史脚本规则未重复')

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

    print()
    print('=' * 66)
    if FAILED:
        print(f'✗ {len(FAILED)} 项不一致：')
        for f in FAILED:
            print(f'    - {f}')
        print('=' * 66)
        return 1
    print('✓ 全部通过（17 项）')
    print('=' * 66)
    return 0


if __name__ == '__main__':
    sys.exit(main())
