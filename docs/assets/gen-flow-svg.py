#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成架构图的两张 SVG。

为什么要自己画而不是用 Mermaid：Mermaid 的自动布局在 27 个节点 / 39 条边上
会把图拉成 1515x3171 的一长条，塞进页面就成了一团糊。这里坐标全部手控，
布局照着 ASCII 线框图来，但用真正的方框和连线。

用法：python docs/assets/gen-flow-svg.py
输出：docs/assets/flow-1-decision.svg、docs/assets/flow-2-execution.svg
"""

import io
import os

FONT = "-apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif"
MONO = "ui-monospace, Consolas, monospace"

# 配色
C_SPINE = ("#eef4ff", "#3b6fd4")      # 主干节点
C_BRANCH = ("#fff8e6", "#d6a419")     # 分支 / 决策
C_PANEL = ("#f6f8fa", "#8b949e")      # 说明面板
C_KNOW = ("#e8f6e8", "#4a9e4a")       # 知识线
C_CROSS = ("#e8f1fd", "#4a7fc1")      # 跨域
C_AGENT = ("#f4ecfa", "#8b5fb8")      # 单域
C_END = ("#f0f0f0", "#666666")        # 结束
C_DISPATCH = ("#ffe8cc", "#d4841a")   # 分派（高亮）


class Svg:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.parts = []

    def _add(self, s):
        self.parts.append(s)

    def box(self, x, y, w, h, lines, style, *, bold_first=True,
            font_size=13, radius=8, align="center", line_gap=19, dash=False):
        fill, stroke = style
        dash_attr = ' stroke-dasharray="5 4"' if dash else ''
        self._add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" '
                  f'fill="{fill}" stroke="{stroke}" stroke-width="1.6"{dash_attr}/>')
        n = len(lines)
        total = (n - 1) * line_gap
        start = y + h / 2 - total / 2 + font_size * 0.36
        if align == "left":
            tx, anchor = x + 14, "start"
        else:
            tx, anchor = x + w / 2, "middle"
        for i, ln in enumerate(lines):
            weight = "600" if (i == 0 and bold_first) else "400"
            color = "#1f2328" if (i == 0 and bold_first) else "#57606a"
            self._add(f'<text x="{tx}" y="{start + i * line_gap:.1f}" font-family="{FONT}" '
                      f'font-size="{font_size}" font-weight="{weight}" fill="{color}" '
                      f'text-anchor="{anchor}">{ln}</text>')

    def poly(self, pts, color="#8b949e", dash=False, width=1.6, marker="arrow"):
        d = " ".join(f"{p[0]},{p[1]}" for p in pts)
        dash_attr = ' stroke-dasharray="5 4"' if dash else ''
        m = f' marker-end="url(#{marker})"' if marker else ''
        self._add(f'<polyline points="{d}" fill="none" stroke="{color}" '
                  f'stroke-width="{width}"{dash_attr}{m}/>')

    def label(self, x, y, text, color="#57606a", size=11.5, anchor="middle"):
        self._add(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
                  f'fill="{color}" text-anchor="{anchor}">{text}</text>')

    def title(self, x, y, text, size=17):
        self._add(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
                  f'font-weight="700" fill="#1f2328">{text}</text>')

    def render(self):
        defs = ('<defs>'
                '<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" '
                'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
                '<path d="M 0 0 L 10 5 L 0 10 z" fill="#8b949e"/></marker>'
                '</defs>')
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" '
                f'width="{self.w}" height="{self.h}">{defs}' + "".join(self.parts) + '</svg>')


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# ══════════════════════════════════════════════════════════════════
# 图 1：判定段（起点 → ①~⑧）
# ══════════════════════════════════════════════════════════════════
def flow1():
    W, H = 1180, 1010
    s = Svg(W, H)
    s.title(40, 40, "① 判定段：一句话进来，先过七道判定，全部汇到 ⑧ 分派")

    LX, LW, LH = 60, 200, 52           # 主干列
    RX, RW = 600, 540                  # 右侧"提前定案"面板

    ys = {}
    y = 80
    for key in ["START", "ID", "CMD", "SCAN", "RESOLVE", "CLASSIFY", "MATCH"]:
        if key == "SCAN":
            y += 40                     # ③ 要给 ④ 留位置
        ys[key] = y
        y += LH + 32

    ys["DISPATCH"] = y + 30

    # 主干节点
    s.box(LX, ys["START"], LW, 44, ["起 点", "用户发一句话"], C_END, font_size=12.5)
    s.box(LX, ys["ID"], LW, LH, ["① 身份检查", "查 MySQL 的 ai_user 表"], C_SPINE)
    s.box(LX, ys["CMD"], LW, LH, ["② 系统命令", "是不是 /reset 这类指令"], C_SPINE)
    s.box(LX, ys["SCAN"], LW, LH, ["③ 开场扫描", "读粘性 + 关键词 + 跨域判定", "都是零成本，不叫模型"], C_SPINE, font_size=12.5)
    s.box(LX, ys["RESOLVE"], LW, LH, ["⑤ 指代消解", "把「那台」补成完整问题", "⚠️ 要叫一次本地小模型"], C_SPINE, font_size=12.5)
    s.box(LX, ys["CLASSIFY"], LW, LH, ["⑥ 意图分类", "关键词 → 本地小模型 → 云端"], C_SPINE, font_size=12.5)
    s.box(LX, ys["MATCH"], LW, LH, ["⑦ 匹配助手", "能力注册表 + 角色白名单"], C_SPINE, font_size=12.5)

    # ④ 关键词定案（③ 的右侧分支）
    kx, ky, kw, kh = LX + LW + 70, ys["SCAN"], 210, LH
    s.box(kx, ky, kw, kh, ["④ 关键词定案", "和粘性里的意图比对"], C_SPINE, font_size=12.5)

    # ⑧ 分派
    dw, dh = 260, 62
    dx, dy = LX + (LW - dw) / 2, ys["DISPATCH"]
    s.box(dx, dy, dw, dh, ["⑧ 分 派  ◆", "推 SSE + 决定走哪条执行路"], C_DISPATCH, font_size=13.5)

    # 右侧面板（高度按内容算，别留一大片空白）
    panel_lines = [
        "① 未登录 / 账号不存在 / 账号停用",
        "② 命中 /reset → 清 Redis 粘性 + 回一句",
        "③ 一句话命中 ≥2 个业务域 → 走跨域",
        "④ 关键词能定案 → 直接用，或沿用粘性",
        "③ 含「那台 / 它」+ 有上一轮 → 沿用上轮助手",
        "⑥ 转人工 / 投诉 → 回固定话术",
    ]
    py = 150
    ph = 30 + 34 + len(panel_lines) * 27 + 34
    s.box(RX, py, RW, ph, [], C_PANEL, radius=10, dash=True)
    s._add(f'<text x="{RX + 24}" y="{py + 34}" font-family="{FONT}" font-size="14" '
           f'font-weight="700" fill="#1f2328">提前定案的六种情况</text>')
    for i, ln in enumerate(panel_lines):
        s._add(f'<text x="{RX + 24}" y="{py + 66 + i * 27}" font-family="{FONT}" '
               f'font-size="13" fill="#57606a">{esc(ln)}</text>')
    s._add(f'<text x="{RX + 24}" y="{py + 66 + len(panel_lines) * 27 + 6}" '
           f'font-family="{FONT}" font-size="12" fill="#8b949e">'
           f'以上都产出决策，不继续往下判 —— 统一汇到 ⑧</text>')

    # ── 连线 ──
    cx = LX + LW / 2
    s.poly([(cx, ys["START"] + 44), (cx, ys["ID"])])
    s.poly([(cx, ys["ID"] + LH), (cx, ys["CMD"])])
    s.label(cx + 30, (ys["ID"] + LH + ys["CMD"]) / 2 + 4, "通过", anchor="start")
    s.poly([(cx, ys["CMD"] + LH), (cx, ys["SCAN"])])
    s.label(cx + 30, (ys["CMD"] + LH + ys["SCAN"]) / 2 + 4, "没命中", anchor="start")
    s.poly([(cx, ys["SCAN"] + LH), (cx, ys["RESOLVE"])])
    s.label(cx + 30, (ys["SCAN"] + LH + ys["RESOLVE"]) / 2 + 4, "都不成立", anchor="start")
    s.poly([(cx, ys["RESOLVE"] + LH), (cx, ys["CLASSIFY"])])
    s.poly([(cx, ys["CLASSIFY"] + LH), (cx, ys["MATCH"])])
    s.label(cx + 30, (ys["CLASSIFY"] + LH + ys["MATCH"]) / 2 + 4, "正常", anchor="start")
    s.poly([(cx, ys["MATCH"] + LH), (cx, dy)])

    # ③ → ④
    s.poly([(LX + LW, ys["SCAN"] + 26), (kx, ys["SCAN"] + 26)])
    s.label((LX + LW + kx) / 2, ys["SCAN"] + 18, "关键词定案", size=11)
    # ④ → 面板
    s.poly([(kx + kw, ky + kh / 2), (RX, ky + kh / 2)], dash=True)

    # 主干各点 → 面板（虚线，表示"产出决策"）
    for key in ["ID", "CMD", "SCAN", "CLASSIFY"]:
        yy = ys[key] + LH / 2
        s.poly([(LX + LW, yy), (RX, yy)], dash=True)

    # 面板 → ⑧
    s.poly([(RX + RW / 2, py + ph), (RX + RW / 2, dy + dh / 2), (dx + dw, dy + dh / 2)])

    return s.render()


# ══════════════════════════════════════════════════════════════════
# 图 2：执行段（⑧ → ⑨⑩⑪ → 汇聚）
# ══════════════════════════════════════════════════════════════════
def flow2():
    W, H = 1450, 1080
    s = Svg(W, H)
    s.title(40, 40, "② 执行段：⑧ 分派之后，四条互斥的路")

    DW, DH = 280, 62
    dx, dy = (W - DW) / 2, 80
    s.box(dx, dy, DW, DH, ["⑧ 分 派  ◆", "短路 / 知识类 / 跨域 / 单域"], C_DISPATCH, font_size=13.5)

    COLS = [
        (40, 230, C_END, "短路",
         ["不调模型，回一句话"],
         [["直接返回", "（转人工 / 投诉 / 命令 /", "身份拦截都走这条）"]]),
        (320, 290, C_KNOW, "⑨ 知识线作答",
         ["⚠️ 不是 Agent", "是代码写死的流水线"],
         [["① 混合召回 20 条", "关键词 BM25 + 语义 KNN"],
          ["② 精排打分", "交叉编码器"],
          ["③ 证据闸", "分够不够回答？"],
          ["④ 回填本体", "摘要 → 完整表格"],
          ["⑤ 生成答案", "标 [1][2] 角标"],
          ["⑥ 自评", "本地小模型核对"]]),
        (660, 290, C_CROSS, "⑩ 跨域作答",
         ["⚠️ 真并行", "三个域同时跑"],
         [["① 确定跑哪几个域", "最多 3 个"],
          ["② 三个域同时跑", "设备 / 租赁 / 知识"],
          ["③ 汇总", "合成一条回答"]]),
        (1000, 300, C_AGENT, "⑪ 单域作答",
         ["⚠️ 这才是 Agent", "ReAct 循环"],
         [["① 取工具白名单", "角色决定看得到哪些"],
          ["② 问模型：要调工具吗？", "↺ 循环点"],
          ["③ 执行工具", "查业务库"],
          ["④ 出答案", "模型说够了就收敛"]]),
    ]

    col_top = 210
    step_h = 62
    step_gap = 16
    bottoms = []

    for x, w, style, name, subs, steps in COLS:
        s.box(x, col_top, w, 66, [name] + subs, style, font_size=13)
        y = col_top + 66 + 26
        step_ys = []
        for lines in steps:
            s.box(x, y, w, step_h, lines, ("#ffffff", style[1]), font_size=12.5, line_gap=17)
            step_ys.append(y)
            y += step_h + step_gap
        bottoms.append((x + w / 2, y - step_gap))
        # 从分派下来的竖线
        s.poly([(x + w / 2, dy + DH), (x + w / 2, col_top)])

        # ⑪ 是 ReAct 循环：③ 执行工具 → 结果丢回 → ② 问模型，把回边画出来
        if "单域" in name:
            lx = x + w + 16                     # 回边走在列的右侧
            y2_top = step_ys[1] + step_h / 2    # ② 问模型
            y3_bot = step_ys[2] + step_h / 2    # ③ 执行工具
            s.poly([(x + w, y3_bot), (lx, y3_bot), (lx, y2_top), (x + w, y2_top)],
                   color=style[1], width=1.8)
            s.label(lx + 6, (y2_top + y3_bot) / 2 + 4, "结果丢回给模型", size=10.5, anchor="start")

    # 汇聚
    merge_y = max(b[1] for b in bottoms) + 70
    mw, mh = 620, 96
    mx = (W - mw) / 2
    s.box(mx, merge_y, mw, mh,
          ["汇聚：所有路径都回到这里",
           "存本轮用户消息 → 认不出意图就记数据飞轮 → 存助手回复 → 返回前端"],
          C_PANEL, font_size=13)
    for bx, by in bottoms:
        s.poly([(bx, by), (bx, merge_y - 18), (mx + mw / 2, merge_y - 18), (mx + mw / 2, merge_y)])

    s.box(mx + mw / 2 - 60, merge_y + mh + 26, 120, 44, ["⏹ 结 束"], C_END, font_size=13)
    s.poly([(mx + mw / 2, merge_y + mh), (mx + mw / 2, merge_y + mh + 26)])

    # 图例（跟着标题走，别甩到右边缘被切掉）
    s._add(f'<text x="{40}" y="{68}" font-family="{FONT}" font-size="12.5" '
           f'fill="#8b949e">四条路互斥 —— 每次只走其中一条。'
           f'颜色区分：绿=知识线，蓝=跨域，紫=单域，灰=短路</text>')

    return s.render()


def main():
    out = os.path.dirname(os.path.abspath(__file__))
    for name, svg in [("flow-1-decision.svg", flow1()), ("flow-2-execution.svg", flow2())]:
        path = os.path.join(out, name)
        io.open(path, "w", encoding="utf-8").write(svg)
        print(f"生成 {name}  ({len(svg)} 字节)")


if __name__ == "__main__":
    main()
