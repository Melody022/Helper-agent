#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成架构图的两张 SVG。

画风：白底、细灰边、深色字——颜色只做极少点缀（列头一条 3px 竖条）。
不堆色块，因为色块一多文字对比度就掉下来了。

坐标全部手控（Mermaid 的自动布局在 27 节点 / 39 边这种规模上会拉成一长条）。

用法：python docs/assets/gen-flow-svg.py
输出：docs/assets/flow-1-decision.svg、docs/assets/flow-2-execution.svg
"""

import io
import os

FONT = "-apple-system, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif"

# ── 配色：只有灰阶 + 极少的点缀色 ──────────────────────────────
INK = "#1f2328"          # 主文字（近黑）
INK2 = "#444c56"         # 次要文字
INK3 = "#6e7781"         # 更淡的说明
LINE = "#d0d7de"         # 边框
LINE2 = "#e6eaee"        # 更淡的分隔
WHITE = "#ffffff"
PANEL = "#fafbfc"
PANEL_LINE = "#e1e5ea"

# 列头点缀色（只用在 3px 竖条上）
A_GRAY = "#9aa4ae"
A_GREEN = "#4f8f4f"
A_BLUE = "#4a7fc1"
A_PURPLE = "#8a63b0"
A_ORANGE = "#c8873a"


class Svg:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.parts = [f'<rect width="{w}" height="{h}" fill="{WHITE}"/>']

    def _add(self, s):
        self.parts.append(s)

    def box(self, x, y, w, h, lines, *, font_size=14.5, radius=7,
            line_gap=21, fill=WHITE, stroke=LINE, accent=None,
            title_size=None, dash=False):
        """一个白底细灰边的方框。accent 传颜色时，左侧画一条 3px 竖条。"""
        dash_attr = ' stroke-dasharray="4 4"' if dash else ''
        self._add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" '
                  f'fill="{fill}" stroke="{stroke}" stroke-width="1.2"{dash_attr}/>')
        if accent:
            r = radius
            self._add(f'<path d="M{x+r},{y} L{x+r},{y+h} " stroke="{accent}" '
                      f'stroke-width="3" stroke-linecap="round"/>')

        n = len(lines)
        total = (n - 1) * line_gap
        start = y + h / 2 - total / 2 + font_size * 0.36
        for i, ln in enumerate(lines):
            if i == 0:
                size = title_size or font_size
                weight, color = "600", INK
            else:
                size = font_size - 1.5
                weight, color = "400", INK2
            self._add(f'<text x="{x + w / 2}" y="{start + i * line_gap:.1f}" '
                      f'font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
                      f'fill="{color}" text-anchor="middle">{ln}</text>')

    def header(self, x, y, w, h, title, sub, accent):
        """列头：白底 + 左侧色条。副标题单独排，避免和标题挤一行。"""
        self._add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="7" '
                  f'fill="{WHITE}" stroke="{LINE}" stroke-width="1.2"/>')
        self._add(f'<path d="M{x + 7},{y} L{x + 7},{y + h}" stroke="{accent}" '
                  f'stroke-width="3" stroke-linecap="round"/>')
        self._add(f'<text x="{x + w / 2}" y="{y + 26}" font-family="{FONT}" '
                  f'font-size="14" font-weight="600" fill="{INK}" '
                  f'text-anchor="middle">{title}</text>')
        self._add(f'<text x="{x + w / 2}" y="{y + 46}" font-family="{FONT}" '
                  f'font-size="13" fill="{INK3}" text-anchor="middle">{sub}</text>')

    def poly(self, pts, color=LINE, dash=False, width=1.3, marker=True):
        d = " ".join(f"{p[0]},{p[1]}" for p in pts)
        dash_attr = ' stroke-dasharray="4 4"' if dash else ''
        m = ' marker-end="url(#arrow)"' if marker else ''
        self._add(f'<polyline points="{d}" fill="none" stroke="{color}" '
                  f'stroke-width="{width}"{dash_attr}{m}/>')

    def label(self, x, y, text, color=INK3, size=12.5, anchor="middle"):
        self._add(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
                  f'fill="{color}" text-anchor="{anchor}">{text}</text>')

    def title(self, x, y, text, size=17, color=INK):
        self._add(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
                  f'font-weight="700" fill="{color}">{text}</text>')

    def note(self, x, y, text, size=13, color=INK3):
        self._add(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
                  f'fill="{color}">{text}</text>')

    def render(self):
        defs = ('<defs>'
                '<marker id="arrow" viewBox="0 0 10 10" refX="8.5" refY="5" '
                'markerWidth="6" markerHeight="6" orient="auto-start-reverse">'
                f'<path d="M 0 1 L 9 5 L 0 9 z" fill="{INK3}"/></marker>'
                '</defs>')
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" '
                f'width="{self.w}" height="{self.h}">{defs}' + "".join(self.parts) + '</svg>')


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# ══════════════════════════════════════════════════════════════════
# 图 1：判定段（起点 → ①~⑧）
# ══════════════════════════════════════════════════════════════════
def flow1():
    W, H = 1230, 1010
    s = Svg(W, H)
    s.title(40, 42, "一条消息从进来到分派")
    s.note(40, 66, "①~⑦ 任何一步提前定案，都不继续往下判 —— 统一产出决策汇到 ⑧")

    LX, LW, LH = 60, 220, 56
    RX, RW = 610, 530

    ys, y = {}, 96
    for key in ["START", "ID", "CMD", "SCAN", "RESOLVE", "CLASSIFY", "MATCH"]:
        if key == "SCAN":
            y += 44
        ys[key] = y
        y += LH + 30
    ys["DISPATCH"] = y + 34

    s.box(LX, ys["START"], LW, 44, ["起点", "用户发一句话"])
    s.box(LX, ys["ID"], LW, LH, ["① 身份检查", "查 MySQL 的 ai_user 表"])
    s.box(LX, ys["CMD"], LW, LH, ["② 系统命令", "是不是 /reset 这类指令"])
    s.box(LX, ys["SCAN"], LW, LH, ["③ 开场扫描", "读粘性 + 关键词 + 跨域判定"], line_gap=22)
    s.box(LX, ys["RESOLVE"], LW, LH, ["⑤ 指代消解", "把「那台」补成完整问题"])
    s.box(LX, ys["CLASSIFY"], LW, LH, ["⑥ 意图分类", "关键词 → 小模型 → 云端"])
    s.box(LX, ys["MATCH"], LW, LH, ["⑦ 匹配助手", "注册表 + 角色白名单"])

    kx, kw = LX + LW + 70, 214
    s.box(kx, ys["SCAN"], kw, LH, ["④ 关键词定案", "和粘性里的意图比对"])

    dw, dh = 264, 60
    dx = LX + (LW - dw) / 2
    dy = ys["DISPATCH"]
    s.box(dx, dy, dw, dh, ["⑧ 分派", "推 SSE + 决定走哪条执行路"],
          fill=PANEL, stroke="#b9c2cc")

    # 右侧面板
    panel = [
        ("① 身份", "未登录 / 账号不存在 / 账号停用"),
        ("② 命令", "命中 /reset → 清 Redis 粘性"),
        ("③ 跨域", "一句话命中 ≥2 个业务域"),
        ("④ 关键词", "能定案 → 直接用，或沿用粘性"),
        ("③ 粘性", "含「那台 / 它」+ 有上一轮"),
        ("⑥ 短路", "转人工 / 投诉 → 回固定话术"),
    ]
    py = 128
    ph = 40 + len(panel) * 30 + 30
    s.box(RX, py, RW, ph, [], fill=PANEL, stroke=PANEL_LINE, radius=9)
    s._add(f'<text x="{RX + 26}" y="{py + 32}" font-family="{FONT}" font-size="14" '
           f'font-weight="600" fill="{INK}">提前定案的六种情况</text>')
    s._add(f'<line x1="{RX + 26}" y1="{py + 44}" x2="{RX + RW - 26}" y2="{py + 44}" '
           f'stroke="{LINE2}" stroke-width="1"/>')
    for i, (tag, desc) in enumerate(panel):
        iy = py + 72 + i * 30
        s._add(f'<text x="{RX + 26}" y="{iy}" font-family="{FONT}" font-size="12.5" '
               f'font-weight="600" fill="{INK}" >{tag}</text>')
        s._add(f'<text x="{RX + 108}" y="{iy}" font-family="{FONT}" font-size="12.5" '
               f'fill="{INK2}">{esc(desc)}</text>')
    s._add(f'<text x="{RX + 26}" y="{py + ph - 16}" font-family="{FONT}" '
           f'font-size="12.5" fill="{INK3}">以上都不继续往下判，统一汇到 ⑧</text>')

    # 连线
    cx = LX + LW / 2
    s.poly([(cx, ys["START"] + 44), (cx, ys["ID"])], marker=False)
    s.poly([(cx, ys["ID"] + LH), (cx, ys["CMD"])], marker=False)
    s.label(cx + 26, (ys["ID"] + LH + ys["CMD"]) / 2 + 4, "通过", anchor="start")
    s.poly([(cx, ys["CMD"] + LH), (cx, ys["SCAN"])], marker=False)
    s.label(cx + 26, (ys["CMD"] + LH + ys["SCAN"]) / 2 + 4, "没命中", anchor="start")
    s.poly([(cx, ys["SCAN"] + LH), (cx, ys["RESOLVE"])], marker=False)
    s.label(cx + 26, (ys["SCAN"] + LH + ys["RESOLVE"]) / 2 + 4, "都不成立", anchor="start")
    s.poly([(cx, ys["RESOLVE"] + LH), (cx, ys["CLASSIFY"])], marker=False)
    s.poly([(cx, ys["CLASSIFY"] + LH), (cx, ys["MATCH"])], marker=False)
    s.label(cx + 26, (ys["CLASSIFY"] + LH + ys["MATCH"]) / 2 + 4, "正常", anchor="start")
    s.poly([(cx, ys["MATCH"] + LH), (cx, dy)], marker=False)

    s.poly([(LX + LW, ys["SCAN"] + 26), (kx, ys["SCAN"] + 26)], marker=False)
    s.label((LX + LW + kx) / 2, ys["SCAN"] + 18, "关键词定案", size=11)
    s.poly([(kx + kw, ys["SCAN"] + LH / 2), (RX, ys["SCAN"] + LH / 2)], marker=False, dash=True)

    for key in ["ID", "CMD", "CLASSIFY"]:
        yy = ys[key] + LH / 2
        s.poly([(LX + LW, yy), (RX, yy)], marker=False, dash=True)

    s.poly([(RX + RW / 2, py + ph), (RX + RW / 2, dy + dh / 2), (dx + dw, dy + dh / 2)],
           marker=False)
    return s.render()


# ══════════════════════════════════════════════════════════════════
# 图 2：执行段（⑧ → ⑨⑩⑪ → 汇聚）
# ══════════════════════════════════════════════════════════════════
def flow2():
    W, H = 1450, 1080
    s = Svg(W, H)
    s.title(40, 42, "分派之后：四条互斥的路")
    s.note(40, 66, "每次只走其中一条；跑完都汇到同一个地方收尾")

    DW, DH = 264, 60
    dx, dy = (W - DW) / 2, 96
    s.box(dx, dy, DW, DH, ["⑧ 分派", "短路 / 知识类 / 跨域 / 单域"],
          fill=PANEL, stroke="#b9c2cc")

    COLS = [
        (40, 232, A_GRAY, "短路", "不调模型，回一句话",
         [["直接返回", "(转人工 / 投诉 / 命令 /", "身份拦截都走这条)"]]),
        (316, 288, A_GREEN, "⑨ 知识线作答", "不是 Agent，是写死的流水线",
         [["① 混合召回 20 条", "关键词 BM25 + 语义 KNN"],
          ["② 精排打分", "交叉编码器"],
          ["③ 证据闸", "分够不够回答？"],
          ["④ 回填本体", "摘要 → 完整表格"],
          ["⑤ 生成答案", "标 [1][2] 角标"],
          ["⑥ 自评", "本地小模型核对"]]),
        (648, 288, A_BLUE, "⑩ 跨域作答", "真并行，三个域同时跑",
         [["① 确定跑哪几个域", "最多 3 个"],
          ["② 三个域同时跑", "设备 / 租赁 / 知识"],
          ["③ 汇总", "合成一条回答"]]),
        (980, 288, A_PURPLE, "⑪ 单域作答", "这才是 Agent（ReAct 循环）",
         [["① 取工具白名单", "角色决定看得到哪些"],
          ["② 问模型：要调工具吗？", ""],
          ["③ 执行工具", "查业务库"],
          ["④ 出答案", "模型说够了就收敛"]]),
    ]

    col_top, step_h, step_gap = 196, 66, 14
    bottoms = []

    for x, w, accent, name, sub, steps in COLS:
        s.header(x, col_top, w, 66, name, sub, accent)
        y = col_top + 66 + 22
        step_ys = []
        for lines in steps:
            lines = [l for l in lines if l]
            s.box(x, y, w, step_h, lines, font_size=14, line_gap=19)
            step_ys.append(y)
            y += step_h + step_gap
        bottoms.append((x + w / 2, y - step_gap))
        s.poly([(x + w / 2, dy + DH), (x + w / 2, col_top)], marker=False)

        if "单域" in name:
            lx = x + w + 14
            y2 = step_ys[1] + step_h / 2
            y3 = step_ys[2] + step_h / 2
            s.poly([(x + w, y3), (lx, y3), (lx, y2), (x + w, y2)], color=INK3, width=1.3)
            s.label(lx + 5, (y2 + y3) / 2 + 4, "结果丢回", size=10.5, anchor="start")

    merge_y = max(b[1] for b in bottoms) + 68
    mw, mh = 640, 84
    mx = (W - mw) / 2
    s.box(mx, merge_y, mw, mh, [], fill=PANEL, stroke=PANEL_LINE, radius=9)
    s._add(f'<text x="{mx + mw / 2}" y="{merge_y + 34}" font-family="{FONT}" '
           f'font-size="13.5" font-weight="600" fill="{INK}" text-anchor="middle">'
           f'汇聚：所有路径都回到这里</text>')
    s._add(f'<text x="{mx + mw / 2}" y="{merge_y + 58}" font-family="{FONT}" '
           f'font-size="12.5" fill="{INK2}" text-anchor="middle">'
           f'存本轮用户消息 → 认不出意图就记数据飞轮 → 存助手回复 → 返回前端</text>')
    for bx, by in bottoms:
        s.poly([(bx, by), (bx, merge_y - 16), (mx + mw / 2, merge_y - 16),
                (mx + mw / 2, merge_y)], marker=False)

    fy = merge_y + mh + 26
    s.box(W / 2 - 52, fy, 104, 42, ["结束"], font_size=13.5)
    s.poly([(W / 2, merge_y + mh), (W / 2, fy)], marker=False)

    return s.render()


def main():
    out = os.path.dirname(os.path.abspath(__file__))
    for name, svg in [("flow-1-decision.svg", flow1()), ("flow-2-execution.svg", flow2())]:
        io.open(os.path.join(out, name), "w", encoding="utf-8").write(svg)
        print(f"ok {name}")


if __name__ == "__main__":
    main()
